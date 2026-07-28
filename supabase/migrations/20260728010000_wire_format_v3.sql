-- T11-02：wire 格式 v3。鍵名改 camelCase、座標改巢狀物件、時間戳固定六位小數 ＋ Z，
-- 探索結果由裸陣列改為含 items 的 envelope（裸陣列日後加欄位是破壞性變更）。
--
-- 請求端刻意不動：body 鍵名維持 p_ 開頭（PostgREST 規定 body 鍵名 ＝ 函式參數名），
-- 座標參數維持兩個獨立浮點數——它們是函式參數，扁平才拿得到資料庫的型別檢查。
--
-- 形狀承載體由 composite type 改為 jsonb：巢狀物件與「時間戳是格式固定的字串」
-- 都不是關聯型別擅長表達的東西。白名單性質不變——欄位仍逐一明列於單一建構處
-- （便條 = as_note_wire、探索提示 = nearby_notes 內的唯一 jsonb_build_object）。

-- ─── 時間戳的唯一格式化處 ────────────────────────────────────────────────────
-- PostgREST 直出 timestamptz 是 +00:00 且小數位數隨值變動（整秒時無小數），
-- client 得為此寫容錯分支。此處固定為六位小數 ＋ Z。
create function public.as_wire_ts(t timestamptz) returns text
language sql stable set search_path = ''   -- to_char 為 STABLE，故此處不能標 immutable
as $$ select to_char(t at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') $$;

-- 純字串格式化、不碰資料，但仍照慣例收斂授權面（兩支 INVOKER 列表需要 authenticated）
revoke execute on function public.as_wire_ts(timestamptz) from public, anon;
grant  execute on function public.as_wire_ts(timestamptz) to authenticated;

-- ─── 舊形狀退場（回傳型別改變 ⇒ 一律先 drop）─────────────────────────────────
drop function public.nearby_notes(double precision, double precision);
drop function public.my_notes(integer, timestamptz, uuid);
drop function public.my_collection(integer, timestamptz, uuid);
drop function public.as_note_wire(public.notes);
drop type public.note_wire;
drop type public.nearby_hint;

-- ─── 便條上 wire 的形狀（唯一建構處，五支 RPC 共用）──────────────────────────
create function public.as_note_wire(n public.notes) returns jsonb
language sql stable set search_path = ''
as $$
  select jsonb_build_object(
    'id',         n.id,
    'content',    n.content,
    'coordinate', jsonb_build_object('latitude', n.lat, 'longitude', n.lng),
    'createdAt',  public.as_wire_ts(n.created_at),
    'pickedUpAt', public.as_wire_ts(n.picked_up_at)
  )
$$;

comment on function public.as_note_wire(public.notes) is
  '便條列 → 上 wire 的形狀。白名單：要露出的欄位必須明列於此。';

-- 兩支列表 RPC 是 SECURITY INVOKER，故 authenticated 需要執行權。
-- 經 PostgREST 會成為 notes 的 computed column，但它回傳的正是已白名單化的公開形狀，
-- 且該角色本來就能直讀自己的列（notes_select_own），未擴大任何讀取面。
revoke execute on function public.as_note_wire(public.notes) from public, anon;
grant  execute on function public.as_note_wire(public.notes) to authenticated;

-- ─── drop_note ───────────────────────────────────────────────────────────────
create or replace function public.drop_note(
  p_content text,
  p_lat     double precision,
  p_lng     double precision
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_uid     uuid := (select auth.uid());
  v_content text := btrim(p_content);
  v_row     public.notes;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  if v_content is null or v_content = '' then raise exception 'content_empty'; end if;
  if char_length(v_content) > 500 then raise exception 'content_too_long'; end if;
  -- ponytail: advisory 上限（併發下可小幅超越），防匿名帳號灑滿地圖；觀察到濫用再調
  if (select count(*) from public.notes n
      where n.author_id = v_uid and n.picked_up_at is null) >= 50 then
    raise exception 'active_note_limit';
  end if;

  insert into public.notes (author_id, content, lat, lng)
  values (v_uid, v_content, p_lat, p_lng)
  returning * into v_row;

  return public.as_note_wire(v_row);
end;
$$;

-- ─── nearby_notes（回傳 envelope）────────────────────────────────────────────
create function public.nearby_notes(
  p_lat double precision,
  p_lng double precision
) returns jsonb
language plpgsql stable security definer set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_loc extensions.geography;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  v_loc := extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography;

  -- 內層挑出 ≤20 筆最近的（形狀與舊版逐字相同 ⇒ 查詢計畫不變），外層才組 JSON
  return (
    select jsonb_build_object('items', coalesce(jsonb_agg(
             -- 探索提示的唯一建構處：刻意不含 content 與任何作者資訊
             jsonb_build_object(
               'id',         h.id,
               'coordinate', jsonb_build_object('latitude', h.lat, 'longitude', h.lng),
               'distanceM',  h.distance_m,
               'pickable',   h.pickable,
               'createdAt',  public.as_wire_ts(h.created_at)
             ) order by h.distance_m), '[]'::jsonb))
    from (
      select n.id, n.lat, n.lng,
             round(extensions.st_distance(n.location, v_loc))::int as distance_m,
             extensions.st_dwithin(n.location, v_loc, 50.0)        as pickable,
             n.created_at
      from public.notes n
      where n.picked_up_at is null
        and n.author_id <> v_uid          -- 自己的 pin 由 my-notes 疊圖
        and extensions.st_dwithin(n.location, v_loc, 100.0)
      order by 4
      limit 20
    ) h
  );
end;
$$;

-- ─── pickup_note（原子獨佔）──────────────────────────────────────────────────
create or replace function public.pickup_note(
  p_note_id uuid,
  p_lat     double precision,
  p_lng     double precision
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_loc extensions.geography;
  v_row public.notes;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  -- ponytail: advisory 撿取頻率上限，防座標掃描清空城市（獨佔＝破壞性，見 ADR-0003）
  if (select count(*) from public.notes n
      where n.picked_up_by = v_uid
        and n.picked_up_at > now() - interval '1 hour') >= 60 then
    raise exception 'pickup_rate_limited';
  end if;
  v_loc := extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography;

  -- 獨佔性的全部保證就是這一個語句：check 與 write 同語句同 row version，無競態窗口
  update public.notes n
     set picked_up_by = v_uid, picked_up_at = now()
   where n.id = p_note_id
     and n.picked_up_at is null
     and n.author_id <> v_uid
     and extensions.st_dwithin(n.location, v_loc, 50.0)
  returning * into v_row;

  if found then return public.as_note_wire(v_row); end if;

  -- 失敗診斷。此讀取相對 UPDATE 有 race，但只影響回報哪個錯誤碼，不影響獨佔正確性
  select * into v_row from public.notes n where n.id = p_note_id;
  if not found then
    raise exception 'note_not_found';
  elsif v_row.picked_up_by = v_uid then
    return public.as_note_wire(v_row);   -- 冪等重試：已是你的 = 成功
  elsif v_row.picked_up_at is not null then
    raise exception 'note_taken';
  elsif v_row.author_id = v_uid then
    raise exception 'own_note';
  else
    raise exception 'too_far';
  end if;
end;
$$;

-- ─── my_notes ────────────────────────────────────────────────────────────────
-- ponytail: 仍回裸陣列（T11-03 才連同不透明游標一起包成 envelope）
create function public.my_notes(
  p_limit             integer     default 50,
  p_before_created_at timestamptz default null,
  p_before_id         uuid        default null
) returns jsonb
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if (p_before_created_at is null) <> (p_before_id is null) then
    raise exception 'invalid_cursor';
  end if;

  return (
    select coalesce(jsonb_agg(t.w order by t.created_at desc, t.id desc), '[]'::jsonb)
    from (
      select public.as_note_wire(n) as w, n.created_at, n.id
      from public.notes n
      where n.author_id = v_uid
        and (p_before_created_at is null
             or (n.created_at, n.id) < (p_before_created_at, p_before_id))
      order by n.created_at desc, n.id desc
      limit least(greatest(coalesce(p_limit, 50), 1), 100)
    ) t
  );
end;
$$;

-- ─── my_collection ───────────────────────────────────────────────────────────
create function public.my_collection(
  p_limit            integer     default 50,
  p_before_picked_at timestamptz default null,
  p_before_id        uuid        default null
) returns jsonb
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if (p_before_picked_at is null) <> (p_before_id is null) then
    raise exception 'invalid_cursor';
  end if;

  return (
    select coalesce(jsonb_agg(t.w order by t.picked_up_at desc, t.id desc), '[]'::jsonb)
    from (
      select public.as_note_wire(n) as w, n.picked_up_at, n.id
      from public.notes n
      where n.picked_up_by = v_uid
        and (p_before_picked_at is null
             or (n.picked_up_at, n.id) < (p_before_picked_at, p_before_id))
      order by n.picked_up_at desc, n.id desc
      limit least(greatest(coalesce(p_limit, 50), 1), 100)
    ) t
  );
end;
$$;

-- ─── Grants（drop 後權限一併消失，照既有慣例逐支收回再授予）──────────────────
revoke execute on function public.nearby_notes(double precision, double precision) from public, anon;
revoke execute on function public.my_notes(integer, timestamptz, uuid) from public, anon;
revoke execute on function public.my_collection(integer, timestamptz, uuid) from public, anon;
grant execute on function public.nearby_notes(double precision, double precision) to authenticated;
grant execute on function public.my_notes(integer, timestamptz, uuid) to authenticated;
grant execute on function public.my_collection(integer, timestamptz, uuid) to authenticated;
