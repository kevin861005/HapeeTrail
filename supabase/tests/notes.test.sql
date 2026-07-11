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
  if not (r ?& array['id','author_id','content','lat','lng','created_at','picked_up_by','picked_up_at'])
     or r ? 'location' then
    raise exception 'FAIL: unexpected JSON shape %', r;
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
declare c int;
begin
  select count(*) into c from public.nearby_notes(35.6595, 139.7005);
  if c <> 0 then raise exception 'FAIL: own notes appeared in nearby (%)', c; end if;
end $$;

reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000a');

do $$
declare rows record; i int := 0;
begin
  -- 從基準點查：預期看到 0m（hello）與 70m 兩張，130m 那張不可見
  for rows in select * from public.nearby_notes(35.6595, 139.7005) loop
    i := i + 1;
    if i = 1 and not (rows.distance_m <= 5 and rows.pickable) then
      raise exception 'FAIL: nearest row wrong: distance_m=%, pickable=%', rows.distance_m, rows.pickable;
    end if;
    if i = 2 and not (rows.distance_m between 60 and 80 and not rows.pickable) then
      raise exception 'FAIL: 70m row wrong: distance_m=%, pickable=%', rows.distance_m, rows.pickable;
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
declare c int;
begin
  select count(*) into c from public.nearby_notes(35.6595, 139.7005);
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
  if r->>'content' <> 'hello from Tokyo' or (r->>'picked_up_at') is null then
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
  select count(*) into c from public.nearby_notes(35.6595, 139.7005) nn
  where nn.id = current_setting('test.hello_id')::uuid;
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

reset role;
select 'ALL TESTS PASSED' as result;
rollback;
