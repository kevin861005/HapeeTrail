-- T11-08：距離算法收斂為單一真相。
--
-- 同一個算法原本寫在兩處：探索提示的 distanceM，與 T11-06 為 too_far 附上的實際距離。
-- 兩者必須恆等，否則旅人會看到「探索說 60 公尺、走過去撿卻說還差 70 公尺」——
-- 兩個畫面都是伺服器算的，卻互相打臉。原本只靠一句註解拴住，這裡收成一支函式。
--
-- ⚠️ 刻意**不授權給任何 client 角色**：兩支呼叫端都是 SECURITY DEFINER（以擁有者身分執行），
-- 不需要它上檯面。授權了就等於在契約外多開一支 `POST /rest/v1/rpc/distance_m`——
-- as_wire_ts／as_cursor／parse_cursor 是兩支 SECURITY INVOKER 列表逼出來的例外，
-- 這支沒有那個理由，於是也不必進契約文件的「契約外路徑」揭露。
-- ponytail: 照專案慣例維持空的 search_path，代價是 planner **無法 inline** 帶 SET 子句的
-- SQL 函式，於是探索查詢每個候選列多一次函式呼叫。實測（本機，10 萬次呼叫）：
-- 帶 SET 175–228ms、可 inline 18–27ms ⇒ 每列約多 1.3–1.7µs。MVP 規模（100m 內數十列）
-- 約 0.1ms，看不出來；極端密度（6 萬列擠在 166×90m）的探索由 ~70ms 變 ~110ms。
-- 天花板：熱門地點的便條密度若真的長到那個量級，拿掉這行 SET 即可讓它 inline——
-- 函式本體已全 schema 限定，且兩支呼叫端都是空 search_path 的 SECURITY DEFINER，
-- 呼叫端控制不了它看到的 path。先不拿掉，因為那是動到專案的安全慣例，值得單獨決定。
create function public.distance_m(a extensions.geography, b extensions.geography) returns integer
language sql immutable set search_path = ''   -- st_distance(geography, geography) 為 IMMUTABLE
as $$ select round(extensions.st_distance(a, b))::int $$;

comment on function public.distance_m(extensions.geography, extensions.geography) is
  '兩點間的距離，公尺、四捨五入到整數。探索提示與 too_far 的唯一算法來源。';

-- 新函式預設 EXECUTE 給 PUBLIC，不收回的話 anon／authenticated 都呼叫得到
revoke execute on function public.distance_m(extensions.geography, extensions.geography)
  from public, anon, authenticated;

-- ─── 兩處改呼叫（外部可觀察行為零變化）──────────────────────────────────────
create or replace function public.nearby_notes(
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

  -- 內層挑出 ≤20 筆最近的，外層才組 JSON。where 多一個 audience 條件，
  -- 與 notes_active_location_gix 的新索引條件逐字一致——不一致的話查詢計畫用不到它，
  -- 探索會靜默退化成全表掃描
  return (
    select jsonb_build_object('items', coalesce(jsonb_agg(
             -- 探索提示的唯一建構處：刻意不含 content 與任何作者資訊；
             -- 代號進來是為了地圖 pin 能渲染成作者選的樣式
             jsonb_build_object(
               'id',         h.id,
               'color',      h.color,
               'style',      h.style,
               'coordinate', jsonb_build_object('latitude', h.lat, 'longitude', h.lng),
               'distanceM',  h.distance_m,
               'pickable',   h.pickable,
               'createdAt',  public.as_wire_ts(h.created_at)
             ) order by h.distance_m), '[]'::jsonb))
    from (
      select n.id, n.lat, n.lng, n.color, n.style,
             public.distance_m(n.location, v_loc)            as distance_m,
             extensions.st_dwithin(n.location, v_loc, 50.0)  as pickable,
             n.created_at
      from public.notes n
      where n.picked_up_at is null
        and n.audience = 'anyone'         -- 旅遊紀錄不給任何人看見，含作者自己
        and n.author_id <> v_uid          -- 自己的 pin 由 my-notes 疊圖
        and extensions.st_dwithin(n.location, v_loc, 100.0)
      order by distance_m                 -- 取最近的 20 筆（外層 jsonb_agg 另有自己的排序）
      limit 20
    ) h
  );
end;
$$;

create or replace function public.pickup_note(
  p_note_id uuid,
  p_lat     double precision,
  p_lng     double precision
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  c_max_pickups constant integer  := 60;
  c_window      constant interval := interval '1 hour';
  v_uid   uuid := (select auth.uid());
  v_loc   extensions.geography;
  v_row   public.notes;
  v_oldest timestamptz;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  -- ponytail: advisory 撿取頻率上限，防座標掃描清空城市（獨佔＝破壞性，見 ADR-0003）。
  -- 取「窗內第 60 新的那次撿取」取代 count：它存在 ⇔ 窗內已有 ≥60 次，同時它滑出窗的
  -- 時刻就是計數降回 59、可以再撿的時刻——閘門與建議秒數同一次索引掃描（notes_picker_ix）
  select n.picked_up_at into v_oldest
  from public.notes n
  where n.picked_up_by = v_uid and n.picked_up_at > now() - c_window
  order by n.picked_up_at desc
  offset c_max_pickups - 1 limit 1;
  if found then
    raise exception 'pickup_rate_limited'
      using detail = jsonb_build_object(
        'retryAfterS', ceil(extract(epoch from (v_oldest + c_window - now())))::int)::text;
  end if;
  v_loc := extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography;

  -- 獨佔性的全部保證就是這一個語句：check 與 write 同語句同 row version，無競態窗口
  update public.notes n
     set picked_up_by = v_uid, picked_up_at = now()
   where n.id = p_note_id
     and n.picked_up_at is null
     and n.audience = 'anyone'            -- 旅遊紀錄撿不走（探索看不到它，但 id 未必永不外流）
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
    raise exception 'own_note';          -- 自己的（含自己的旅遊紀錄）：沒有隱藏的必要
  elsif v_row.audience <> 'anyone' then
    -- 對外人，私人便條與不存在的便條回同一個答案。新增一個「這是私人便條」的 token
    -- 等於向外人確認該座標存在一張他看不到的便條
    raise exception 'note_not_found';
  else
    raise exception 'too_far'
      using detail = jsonb_build_object('distanceM', public.distance_m(v_row.location, v_loc))::text;
  end if;
end;
$$;
