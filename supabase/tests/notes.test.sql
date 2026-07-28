-- notes RPC/RLS 行為驗證。用法（擇一）：
--   psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" -f supabase/tests/notes.test.sql
--   docker exec -i supabase_db_trailstamp psql -U postgres -d postgres -f - < supabase/tests/notes.test.sql
-- 先 supabase db reset。全部在單一 transaction 內執行、結尾 ROLLBACK，不留任何測試資料。
-- 注意：跨使用者的 id 查詢一律在 reset role（postgres）下先存入 GUC，
-- 再登入目標使用者斷言——RLS 下直接查會拿到 NULL/空集合，測試會變成空測。
\set ON_ERROR_STOP on
begin;

-- ─── 測試工具 ───────────────────────────────────────────────────────────────
create function pg_temp.login(u uuid) returns void language sql as $fn$
  select set_config('request.jwt.claims',
                    json_build_object('sub', u, 'role', 'authenticated')::text, true),
         set_config('role', 'authenticated', true);
$fn$;

-- 期望 sql 丟出指定錯誤（want 可用 like pattern）
create function pg_temp.expect_error(sql text, want text) returns void language plpgsql as $fn$
begin
  begin
    execute sql;
  exception when others then
    if sqlerrm = want or sqlerrm like want then return; end if;
    raise exception 'FAIL: expected error [%], got [%]', want, sqlerrm;
  end;
  raise exception 'FAIL: expected error [%], but call succeeded', want;
end $fn$;

-- ─── 假使用者與座標 ──────────────────────────────────────────────────────────
-- 東京基準點；緯度每度約 110,953m（35.66°N），偏移只動 lat 便於計算
--   30m ≈ 0.00027039、70m ≈ 0.00063090、130m ≈ 0.00117167
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'),  -- A：撿起者
  ('00000000-0000-0000-0000-00000000000b'),  -- B：作者
  ('00000000-0000-0000-0000-00000000000c'),  -- C：50 張上限測試
  ('00000000-0000-0000-0000-00000000000d');  -- D：60 次/時上限測試

-- ─── drop_note：驗證與 trim ─────────────────────────────────────────────────
select pg_temp.login('00000000-0000-0000-0000-00000000000b');

do $$
declare r jsonb;
begin
  r := public.drop_note('  hello from Tokyo  ', 35.6595, 139.7005);
  if r->>'content' <> 'hello from Tokyo' then
    raise exception 'FAIL: btrim not applied, got %', r->>'content';
  end if;
  -- v3 形狀：精確鍵集（不只是「有」，而是「只有」——白名單的價值在此）。
  -- uuid 身分欄位與 location 不得上 wire（T7），舊 snake_case 鍵也不得殘留。
  if (select array_agg(k order by k) from jsonb_object_keys(r) k)
     <> array['content','coordinate','createdAt','id','pickedUpAt'] then
    raise exception 'FAIL: unexpected JSON shape %', r;
  end if;
  -- 座標為巢狀物件
  if (select array_agg(k order by k) from jsonb_object_keys(r->'coordinate') k)
     <> array['latitude','longitude']
     or (r->'coordinate'->>'latitude')::float8 <> 35.6595
     or (r->'coordinate'->>'longitude')::float8 <> 139.7005 then
    raise exception 'FAIL: coordinate not nested/correct: %', r->'coordinate';
  end if;
  -- 時間戳固定六位小數 ＋ Z（client 不必為位數寫容錯分支）
  if (r->>'createdAt') !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$' then
    raise exception 'FAIL: createdAt format %', r->>'createdAt';
  end if;
  if r->'pickedUpAt' <> 'null'::jsonb then
    raise exception 'FAIL: pickedUpAt should be JSON null, got %', r->'pickedUpAt';
  end if;
end $$;

select pg_temp.expect_error($$select public.drop_note('   ', 35.6595, 139.7005)$$, 'content_empty');
select pg_temp.expect_error($$select public.drop_note(repeat('あ', 501), 35.6595, 139.7005)$$, 'content_too_long');
select pg_temp.expect_error($$select public.drop_note('x', 91, 139.7005)$$, 'invalid_coordinates');
select pg_temp.expect_error($$select public.drop_note('x', 35.6595, null)$$, 'invalid_coordinates');

-- 500 字元恰好合法（char_length 算 code point）
do $$ begin perform public.drop_note(repeat('あ', 500), 35.66200, 139.7005); end $$;

-- B 再放一張 70m 處的便條（nearby 可見但不可撿）
do $$ begin perform public.drop_note('at 70m', 35.66013090, 139.7005); end $$;
-- B 放一張 130m 處的便條（nearby 不可見）
do $$ begin perform public.drop_note('at 130m', 35.66067167, 139.7005); end $$;

-- ─── nearby_notes：半徑、排序、pickable、排除自己 ──────────────────────────
-- B 自己查：自己的便條一律不出現
do $$
declare r jsonb := public.nearby_notes(35.6595, 139.7005);
begin
  -- 零結果：items 必須是空陣列而非 null（client 不必為 null 寫分支）
  if r->'items' <> '[]'::jsonb then
    raise exception 'FAIL: own notes appeared in nearby / items not empty array: %', r;
  end if;
end $$;

reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000a');

do $$
declare r jsonb; h jsonb; i int := 0;
begin
  -- 從基準點查：預期看到 0m（hello）與 70m 兩張，130m 那張不可見
  r := public.nearby_notes(35.6595, 139.7005);
  -- 探索結果是含 items 的物件而非裸陣列（日後加欄位才不是破壞性變更）
  if (select array_agg(k) from jsonb_object_keys(r) k) <> array['items']
     or jsonb_typeof(r->'items') <> 'array' then
    raise exception 'FAIL: nearby envelope shape %', r;
  end if;
  for h in select * from jsonb_array_elements(r->'items') loop
    i := i + 1;
    -- 提示的精確鍵集：不含 content 與任何作者資訊
    if (select array_agg(k order by k) from jsonb_object_keys(h) k)
       <> array['coordinate','createdAt','distanceM','id','pickable'] then
      raise exception 'FAIL: unexpected hint shape %', h;
    end if;
    if (select array_agg(k order by k) from jsonb_object_keys(h->'coordinate') k)
       <> array['latitude','longitude'] then
      raise exception 'FAIL: hint coordinate not nested: %', h->'coordinate';
    end if;
    if (h->>'createdAt') !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$' then
      raise exception 'FAIL: hint createdAt format %', h->>'createdAt';
    end if;
    if i = 1 and not ((h->>'distanceM')::int <= 5 and (h->>'pickable')::boolean) then
      raise exception 'FAIL: nearest row wrong: %', h;
    end if;
    if i = 2 and not ((h->>'distanceM')::int between 60 and 80 and not (h->>'pickable')::boolean) then
      raise exception 'FAIL: 70m row wrong: %', h;
    end if;
  end loop;
  if i <> 2 then raise exception 'FAIL: expected 2 nearby rows, got %', i; end if;
end $$;

select pg_temp.expect_error($$select * from public.nearby_notes(200, 0)$$, 'invalid_coordinates');

-- 上限 20 筆：直接塞 25 張別人的便條在同一點
reset role;
insert into public.notes (author_id, content, lat, lng)
select '00000000-0000-0000-0000-00000000000b', 'bulk ' || g, 35.65953, 139.70053
from generate_series(1, 25) g;
-- 以 postgres 身分先存 hello 便條 id（RLS 下 A/D 看不到未撿的它）
select set_config('test.hello_id',
  (select id::text from public.notes where content = 'hello from Tokyo'), true);
select pg_temp.login('00000000-0000-0000-0000-00000000000a');
do $$
declare c int := jsonb_array_length(public.nearby_notes(35.6595, 139.7005)->'items');
begin
  if c <> 20 then raise exception 'FAIL: nearby limit, expected 20 got %', c; end if;
end $$;

-- ─── pickup_note：距離、獨佔、冪等、診斷碼 ─────────────────────────────────
do $$
declare v_id uuid := current_setting('test.hello_id')::uuid; r jsonb; r2 jsonb;
begin
  -- 70m 外 → too_far
  perform pg_temp.expect_error(
    format($q$select public.pickup_note('%s', 35.66013090, 139.7005)$q$, v_id), 'too_far');

  -- 30m 內 → 成功，content 揭露
  r := public.pickup_note(v_id, 35.65977039, 139.7005);
  if r->>'content' <> 'hello from Tokyo'
     or (r->>'pickedUpAt') !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$' then
    raise exception 'FAIL: pickup result wrong: %', r;
  end if;

  -- 本人重試 → 冪等成功（不是 note_taken）
  r2 := public.pickup_note(v_id, 35.65977039, 139.7005);
  if r2->>'id' <> r->>'id' then raise exception 'FAIL: idempotent retry mismatch'; end if;
end $$;

-- 已被 A 撿走 → 其他人（D）再撿 → note_taken
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000d');
do $$
declare v_id uuid := current_setting('test.hello_id')::uuid;
begin
  perform pg_temp.expect_error(
    format($q$select public.pickup_note('%s', 35.65977039, 139.7005)$q$, v_id), 'note_taken');
end $$;

-- 作者撿自己的 → own_note；亂 uuid → note_not_found
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000b');
do $$
declare v_id uuid;
begin
  select n.id into v_id from public.notes n where n.content = 'at 70m';
  perform pg_temp.expect_error(
    format($q$select public.pickup_note('%s', 35.66013090, 139.7005)$q$, v_id), 'own_note');
  perform pg_temp.expect_error(
    $q$select public.pickup_note('deadbeef-dead-beef-dead-beefdeadbeef', 35.6595, 139.7005)$q$,
    'note_not_found');
end $$;

-- 撿走的便條從 nearby 消失
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000d');
do $$
declare c int;
begin
  select count(*) into c
  from jsonb_array_elements(public.nearby_notes(35.6595, 139.7005)->'items') h
  where h->>'id' = current_setting('test.hello_id');
  if c <> 0 then raise exception 'FAIL: picked note still in nearby'; end if;
end $$;

-- ─── RLS：只能直讀自己寫的或自己撿的 ────────────────────────────────────────
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000a');
do $$
declare c int;
begin
  select count(*) into c from public.notes;
  if c <> 1 then raise exception 'FAIL: A should see exactly 1 row (the picked note), got %', c; end if;
  select count(*) into c from public.notes where picked_up_by = (select auth.uid());
  if c <> 1 then raise exception 'FAIL: A collection count %', c; end if;
end $$;

reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000b');
do $$
declare c int;
begin
  -- B 是 29 張的作者（4 張 RPC drop：hello/500字/70m/130m + 25 張 bulk），全部可見（含被撿走那張）
  select count(*) into c from public.notes;
  if c <> 29 then raise exception 'FAIL: B should see 29 own rows, got %', c; end if;
end $$;

-- INSERT/UPDATE/DELETE 無任何直接路徑
select pg_temp.expect_error(
  $$insert into public.notes (author_id, content, lat, lng)
    values ('00000000-0000-0000-0000-00000000000b', 'direct', 0, 0)$$,
  'permission denied%');
select pg_temp.expect_error($$update public.notes set content = 'x'$$, 'permission denied%');
select pg_temp.expect_error($$delete from public.notes$$, 'permission denied%');

-- anon role 完全不可達
reset role;
set local role anon;
select pg_temp.expect_error($$select * from public.nearby_notes(35.6595, 139.7005)$$, 'permission denied%');
select pg_temp.expect_error($$select public.drop_note('x', 35.6595, 139.7005)$$, 'permission denied%');
select pg_temp.expect_error($$select count(*) from public.notes$$, 'permission denied%');
reset role;

-- authenticated role 但 JWT claims 為空 → 防禦碼 not_authenticated
select set_config('request.jwt.claims', '', true), set_config('role', 'authenticated', true);
select pg_temp.expect_error($$select * from public.nearby_notes(35.6595, 139.7005)$$, 'not_authenticated');
reset role;

-- ─── 反濫用上限 ─────────────────────────────────────────────────────────────
-- C：第 51 張未撿便條 → active_note_limit
select pg_temp.login('00000000-0000-0000-0000-00000000000c');
do $$
begin
  for i in 1..50 loop
    perform public.drop_note('cap ' || i, 35.0 + i * 0.001, 135.0);
  end loop;
  perform pg_temp.expect_error(
    $q$select public.drop_note('cap 51', 35.1, 135.0)$q$, 'active_note_limit');
end $$;

-- D：一小時內第 61 次撿取 → pickup_rate_limited
reset role;
insert into public.notes (author_id, content, lat, lng)
select '00000000-0000-0000-0000-00000000000c', 'ratelimit ' || g, 36.0, 135.0
from generate_series(1, 61) g;
-- 以 postgres 身分存 61 個 id（RLS 下 D 看不到未撿的它們）
select set_config('test.rl_ids',
  (select string_agg(id::text, ',') from public.notes where content like 'ratelimit %'), true);
select pg_temp.login('00000000-0000-0000-0000-00000000000d');
do $$
declare v_ids uuid[] := string_to_array(current_setting('test.rl_ids'), ',')::uuid[];
        v_id uuid; n int := 0;
begin
  if array_length(v_ids, 1) <> 61 then
    raise exception 'FAIL: expected 61 ratelimit ids, got %', array_length(v_ids, 1);
  end if;
  foreach v_id in array v_ids loop
    n := n + 1;
    if n <= 60 then
      perform public.pickup_note(v_id, 36.0, 135.0);
    else
      perform pg_temp.expect_error(
        format($q$select public.pickup_note('%s', 36.0, 135.0)$q$, v_id), 'pickup_rate_limited');
    end if;
  end loop;
  if n <> 61 then raise exception 'FAIL: rate-limit loop ran % times, expected 61', n; end if;
end $$;

-- ─── 列表 RPC：envelope ＋ 不透明游標 ───────────────────────────────────────
-- 注意：本測試單一 transaction，now() 恆定 ⇒ 所有 timestamp 同刻，
-- 等於對複合游標 (ts, id) 的平手邏輯做最嚴苛的壓力測試。
-- 只斷言外部可觀察行為：原樣回傳可翻頁、竄改被拒、nextCursor null ＝ 結束。
-- 游標的內部編碼是實作細節，刻意不斷言。
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000b');
do $$
declare
  seen uuid[] := '{}'; page jsonb; item jsonb; total int := 0; batch int;
  cur text := null;
  prev_ts timestamptz; prev_id uuid;
  cur_ts timestamptz; cur_id uuid;
begin
  -- B 以每頁 10 筆走完自己的 29 張：不重複、不遺漏、(createdAt,id) 嚴格遞減。
  -- 終止訊號只看 nextCursor 為 null——不再靠「多打一次拿到空陣列」。
  loop
    page := public.my_notes(10, cur);
    if (select array_agg(k order by k) from jsonb_object_keys(page) k) <> array['items','nextCursor'] then
      raise exception 'FAIL: my_notes envelope shape %', page;
    end if;
    batch := 0; prev_ts := null; prev_id := null;
    for item in select * from jsonb_array_elements(page->'items') loop
      batch := batch + 1;
      cur_ts := (item->>'createdAt')::timestamptz;
      cur_id := (item->>'id')::uuid;
      if cur_id = any(seen) then raise exception 'FAIL: my_notes duplicate id across pages'; end if;
      seen := seen || cur_id;
      if prev_ts is not null and (cur_ts, cur_id) >= (prev_ts, prev_id) then
        raise exception 'FAIL: my_notes order not strictly descending';
      end if;
      prev_ts := cur_ts; prev_id := cur_id;
    end loop;
    total := total + batch;
    exit when page->'nextCursor' = 'null'::jsonb;
    if batch = 0 then raise exception 'FAIL: nextCursor 非 null 卻回了空頁'; end if;
    cur := page->>'nextCursor';   -- client 唯一的義務：原樣回傳
  end loop;
  if total <> 29 then raise exception 'FAIL: my_notes walked % rows, expected 29', total; end if;

  -- p_limit 下界 clamp：0 → 1 筆
  total := jsonb_array_length(public.my_notes(0)->'items');
  if total <> 1 then raise exception 'FAIL: my_notes p_limit clamp, got %', total; end if;
end $$;

-- 邊界：頁大小恰好等於總筆數 ⇒ nextCursor 必為 null（旅人不必為了確認結束多轉一次載入圈）
do $$
declare p jsonb := public.my_notes(29);
begin
  if jsonb_array_length(p->'items') <> 29 or p->'nextCursor' <> 'null'::jsonb then
    raise exception 'FAIL: exact-fit page should end pagination, nextCursor = %', p->'nextCursor';
  end if;
end $$;

-- 無法解碼、被竄改、排序語意不符的游標一律 invalid_cursor（靜默退化會掉列或無限翻頁）。
-- 一律以外部行為施測——不手工組游標、不斷言其內部編碼，否則改編碼就會誤紅。
select pg_temp.expect_error($$select public.my_notes(10, 'not-a-cursor')$$, 'invalid_cursor');
select pg_temp.expect_error($$select public.my_collection(10, '')$$, 'invalid_cursor');

do $$
declare c text := public.my_notes(1)->>'nextCursor';
begin
  if c is null then raise exception 'FAIL: 前置條件不成立——B 有 29 張，第一頁後應有游標'; end if;
  -- 竄改（截斷）真游標
  perform pg_temp.expect_error(format($q$select public.my_notes(10, '%s')$q$, substr(c, 1, 20)),
                               'invalid_cursor');
  -- 排序語意不符：my_notes 的游標（依 created_at）餵給 my_collection（依 picked_up_at）。
  -- 沒有這道閘門的話會靜默拿 created_at 的值去比 picked_up_at ⇒ 回錯頁且毫無訊號。
  perform pg_temp.expect_error(format($q$select public.my_collection(10, '%s')$q$, c),
                               'invalid_cursor');
end $$;

-- D 的收藏 60 筆全部同刻 picked_up_at：預設 limit 50 → 游標翻頁拿剩下 10、無重疊
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000d');
do $$
declare p1 jsonb; p2 jsonb; n int;
begin
  p1 := public.my_collection();
  n := jsonb_array_length(p1->'items');
  if n <> 50 then raise exception 'FAIL: my_collection default limit, got %', n; end if;
  if p1->'nextCursor' = 'null'::jsonb then
    raise exception 'FAIL: 還有 10 筆卻回 nextCursor null';
  end if;

  p2 := public.my_collection(50, p1->>'nextCursor');
  n := jsonb_array_length(p2->'items');
  if n <> 10 then raise exception 'FAIL: my_collection page 2, got % rows', n; end if;
  if p2->'nextCursor' <> 'null'::jsonb then
    raise exception 'FAIL: 最後一頁 nextCursor 應為 null, got %', p2->'nextCursor';
  end if;
  if exists (select 1 from jsonb_array_elements(p1->'items') a
             join jsonb_array_elements(p2->'items') b on a->>'id' = b->>'id') then
    raise exception 'FAIL: my_collection pages overlap';
  end if;

  -- 反方向的排序語意閘門：my_collection 的游標餵給 my_notes
  perform pg_temp.expect_error(
    format($q$select public.my_notes(10, '%s')$q$, p1->>'nextCursor'), 'invalid_cursor');
end $$;

-- A 的收藏 = 1 張（hello）；A 沒投放過任何便條 ⇒ items 為空陣列而非 null、nextCursor null
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000a');
do $$
declare n int := jsonb_array_length(public.my_collection()->'items');
begin
  if n <> 1 then raise exception 'FAIL: A my_collection expected 1, got %', n; end if;
  if public.my_notes() <> '{"items": [], "nextCursor": null}'::jsonb then
    raise exception 'FAIL: empty my_notes shape %', public.my_notes();
  end if;
end $$;

-- 列表 RPC 的權限面：空 claims → not_authenticated；anon → 拒絕
reset role;
select set_config('request.jwt.claims', '', true), set_config('role', 'authenticated', true);
select pg_temp.expect_error($$select * from public.my_notes()$$, 'not_authenticated');
select pg_temp.expect_error($$select * from public.my_collection()$$, 'not_authenticated');
reset role;
set local role anon;
select pg_temp.expect_error($$select * from public.my_notes()$$, 'permission denied%');
select pg_temp.expect_error($$select * from public.my_collection()$$, 'permission denied%');
reset role;

select 'ALL TESTS PASSED' as result;
rollback;
