-- T11-01：五支 RPC 的回傳形狀改為白名單建構。
-- 黑名單（to_jsonb(v_row) - 'location' - 'author_id' - …）讓往後每個新增的表欄位都
-- 預設上 wire，只靠「有人記得回來減掉它」把關；白名單讓新欄位預設隱形。
-- 對 client 的輸出逐字節不變（jsonb 鍵序由型別本身正規化，值的轉換路徑亦同；
-- setof composite 與 returns table 在 SQL 與 PostgREST 邊界產生相同結果）。
--
-- 形狀各自只在一處定義：便條 = public.note_wire、探索提示 = public.nearby_hint。
-- 往形狀加欄位（T11-02 起每張票都要做）只改型別與其建構處，改不齊會在套用 migration
-- 時直接型別錯誤——不會靜默漂移。

-- ─── 便條上 wire 的形狀（唯一定義處）─────────────────────────────────────────
create type public.note_wire as (
  id           uuid,
  content      text,
  lat          double precision,
  lng          double precision,
  created_at   timestamptz,
  picked_up_at timestamptz
);

-- 唯一建構處。五支 RPC 共用：drop_note / pickup_note 轉 jsonb，兩支列表直接回它。
create function public.as_note_wire(n public.notes) returns public.note_wire
language sql immutable set search_path = ''
as $$ select n.id, n.content, n.lat, n.lng, n.created_at, n.picked_up_at $$;

comment on function public.as_note_wire(public.notes) is
  '便條列 → 上 wire 的形狀。白名單：要露出的欄位必須明列於 public.note_wire。';

-- 兩支列表 RPC 是 SECURITY INVOKER，故 authenticated 需要執行權。
-- 經 PostgREST 會成為 notes 的 computed column，但它回傳的正是已白名單化的公開形狀，
-- 且該角色本來就能直讀自己的列（notes_select_own），未擴大任何讀取面。
revoke execute on function public.as_note_wire(public.notes) from public, anon;
grant  execute on function public.as_note_wire(public.notes) to authenticated;

-- ─── 探索提示的形狀（唯一定義處；只有 nearby_notes 產生它）───────────────────
create type public.nearby_hint as (
  id         uuid,
  lat        double precision,
  lng        double precision,
  distance_m integer,
  pickable   boolean,
  created_at timestamptz
);

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

  return to_jsonb(public.as_note_wire(v_row));
end;
$$;

-- ─── nearby_notes（回傳型別改變，必須先 drop）────────────────────────────────
drop function public.nearby_notes(double precision, double precision);

create function public.nearby_notes(
  p_lat double precision,
  p_lng double precision
) returns setof public.nearby_hint
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

  return query
    select n.id, n.lat, n.lng,
           round(extensions.st_distance(n.location, v_loc))::int,
           extensions.st_dwithin(n.location, v_loc, 50.0),
           n.created_at
    from public.notes n
    where n.picked_up_at is null
      and n.author_id <> v_uid          -- 自己的 pin 由 my-notes 疊圖
      and extensions.st_dwithin(n.location, v_loc, 100.0)
    order by 4                          -- 位置引用避開型別欄位名捕捉
    limit 20;
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

  if found then return to_jsonb(public.as_note_wire(v_row)); end if;

  -- 失敗診斷。此讀取相對 UPDATE 有 race，但只影響回報哪個錯誤碼，不影響獨佔正確性
  select * into v_row from public.notes n where n.id = p_note_id;
  if not found then
    raise exception 'note_not_found';
  elsif v_row.picked_up_by = v_uid then
    return to_jsonb(public.as_note_wire(v_row));   -- 冪等重試：已是你的 = 成功
  elsif v_row.picked_up_at is not null then
    raise exception 'note_taken';
  elsif v_row.author_id = v_uid then
    raise exception 'own_note';
  else
    raise exception 'too_far';
  end if;
end;
$$;

-- ─── my_notes（回傳型別改變，必須先 drop）────────────────────────────────────
drop function public.my_notes(integer, timestamptz, uuid);

create function public.my_notes(
  p_limit             integer     default 50,
  p_before_created_at timestamptz default null,
  p_before_id         uuid        default null
) returns setof public.note_wire
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if (p_before_created_at is null) <> (p_before_id is null) then
    raise exception 'invalid_cursor';
  end if;

  return query
    -- lateral：setof composite 的 RETURN QUERY 要求欄位展開，且只求值一次
    select w.* from public.notes n cross join lateral public.as_note_wire(n) w
    where n.author_id = v_uid
      and (p_before_created_at is null
           or (n.created_at, n.id) < (p_before_created_at, p_before_id))
    order by n.created_at desc, n.id desc
    limit least(greatest(coalesce(p_limit, 50), 1), 100);
end;
$$;

-- ─── my_collection（回傳型別改變，必須先 drop）───────────────────────────────
drop function public.my_collection(integer, timestamptz, uuid);

create function public.my_collection(
  p_limit            integer     default 50,
  p_before_picked_at timestamptz default null,
  p_before_id        uuid        default null
) returns setof public.note_wire
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if (p_before_picked_at is null) <> (p_before_id is null) then
    raise exception 'invalid_cursor';
  end if;

  return query
    -- lateral：setof composite 的 RETURN QUERY 要求欄位展開，且只求值一次
    select w.* from public.notes n cross join lateral public.as_note_wire(n) w
    where n.picked_up_by = v_uid
      and (p_before_picked_at is null
           or (n.picked_up_at, n.id) < (p_before_picked_at, p_before_id))
    order by n.picked_up_at desc, n.id desc
    limit least(greatest(coalesce(p_limit, 50), 1), 100);
end;
$$;

-- ─── Grants（drop 後權限一併消失，照既有慣例逐支收回再授予）──────────────────
revoke execute on function public.nearby_notes(double precision, double precision) from public, anon;
revoke execute on function public.my_notes(integer, timestamptz, uuid) from public, anon;
revoke execute on function public.my_collection(integer, timestamptz, uuid) from public, anon;
grant execute on function public.nearby_notes(double precision, double precision) to authenticated;
grant execute on function public.my_notes(integer, timestamptz, uuid) to authenticated;
grant execute on function public.my_collection(integer, timestamptz, uuid) to authenticated;
