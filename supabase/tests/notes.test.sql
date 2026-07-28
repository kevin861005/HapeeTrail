-- notes RPC/RLS 行為驗證。用法（擇一）：
--   psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" -f supabase/tests/notes.test.sql
--   docker exec -i supabase_db_trailstamp psql -U postgres -d postgres -f - < supabase/tests/notes.test.sql
-- 先 supabase db reset。全部在單一 transaction 內執行、結尾 ROLLBACK，不留任何測試資料。
-- 注意：跨使用者的 id 查詢一律在 reset role（postgres）下先存入 GUC，
-- 再登入目標使用者斷言——RLS 下直接查會拿到 NULL/空集合，測試會變成空測。
-- 唯一例外是 nearby 結果的過濾，走 pg_temp.ours()（SECURITY DEFINER，理由見該處註解）。
\set ON_ERROR_STOP on
begin;

-- ─── 測試工具 ───────────────────────────────────────────────────────────────
create function pg_temp.login(u uuid) returns void language sql as $fn$
  select set_config('request.jwt.claims',
                    json_build_object('sub', u, 'role', 'authenticated')::text, true),
         set_config('role', 'authenticated', true);
$fn$;

-- 期望 sql 丟出指定錯誤（want 可用 like pattern）。
-- want_detail 省略 ＝ 斷言該錯誤**不附帶任何資料**——於是每個既有呼叫點都順便守住
-- 「只有那四種錯誤帶 details」這條契約，不必為每個 token 各寫一條斷言。
create function pg_temp.expect_error(sql text, want text, want_detail jsonb default null)
returns void language plpgsql as $fn$
declare got text;
begin
  begin
    execute sql;
  exception when others then
    get stacked diagnostics got = pg_exception_detail;
    if not (sqlerrm = want or sqlerrm like want) then
      raise exception 'FAIL: expected error [%], got [%]', want, sqlerrm;
    end if;
    -- details 在 wire 上是「內容為 JSON 的字串」；此處只比對它的內容。
    -- 轉型只在「預期有 JSON」時才做：否則某個非預期的 DETAIL（如 check constraint 的
    -- Failing row contains …）會在此拋轉型錯，蓋掉真正該看到的 FAIL 訊息
    if want_detail is null then
      if got <> '' then
        raise exception 'FAIL: [%] 不該附帶 details, got [%]', want, got;
      end if;
    elsif got::jsonb is distinct from want_detail then
      raise exception 'FAIL: [%] details expected [%], got [%]', want, want_detail, got;
    end if;
    return;
  end;
  raise exception 'FAIL: expected error [%], but call succeeded', want;
end $fn$;

-- 假使用者清單的單一真相：下方的 insert 與 pg_temp.ours() 都吃這一份。
-- 新增測試使用者只改這裡——分成兩份的話，漏改會讓 ours() 靜默丟掉新使用者的便條，
-- 斷言照樣綠但覆蓋沒了。
create function pg_temp.fixture_users() returns uuid[] language sql immutable as $fn$
  select array[
    '00000000-0000-0000-0000-00000000000a',  -- A：撿起者
    '00000000-0000-0000-0000-00000000000b',  -- B：作者
    '00000000-0000-0000-0000-00000000000c',  -- C：50 張上限測試
    '00000000-0000-0000-0000-00000000000d',  -- D：60 次/時上限測試
    '00000000-0000-0000-0000-00000000000e',  -- E：style 代號測試（便條刻意遠離東京，不干擾 nearby）
    '00000000-0000-0000-0000-00000000000f'   -- F：私人便條測試（同上，另一個遠離的座標）
  ]::uuid[]
$fn$;

-- 把探索結果濾成「本測試建立的便條」。
-- nearby_notes 是唯一會跨使用者看見便條的查詢，於是也是唯一會被外來資料污染的地方
-- （其餘查詢都被 RLS 限縮在自己的列內）。共用的開發資料庫上這很容易發生——
-- docs/api/notes.md §4 的 curl 範例用的就是本檔的東京基準點，照著文件敲一次就會踩到，
-- 而失敗訊息會寫成「private note leaked」「own notes appeared」，把人導向
-- 「過濾邏輯壞了」這個完全錯誤的方向。
-- SECURITY DEFINER：查作者需要繞過 RLS（呼叫時已登入為某個測試使用者，看不到別人的列）。
-- ponytail: 只擋得住「外來便條混進結果」，擋不住「外來便條把我們的擠出前 20 名」——
-- nearby_notes 先取最近的 20 筆，才輪到這裡過濾。真撞上時斷言會**紅**（不是靜默綠），
-- 訊息是「expected 2 nearby rows, got 0」之類，追得回來。升級路徑：測試座標比照
-- postman collection 改成每次隨機，代價是註解裡那些手算的公尺數會失去固定參照。
create function pg_temp.ours(items jsonb) returns jsonb
language sql stable security definer set search_path = '' as $fn$
  select coalesce(pg_catalog.jsonb_agg(t.h order by t.ord), '[]'::jsonb)
  from pg_catalog.jsonb_array_elements(items) with ordinality t(h, ord)
  join public.notes n on n.id = (t.h->>'id')::uuid
  where n.author_id = any(pg_temp.fixture_users())
$fn$;

-- ─── 假使用者與座標 ──────────────────────────────────────────────────────────
-- 東京基準點；緯度每度約 110,953m（35.66°N），偏移只動 lat 便於計算
--   30m ≈ 0.00027039、70m ≈ 0.00063090、130m ≈ 0.00117167
insert into auth.users (id) select unnest(pg_temp.fixture_users());  -- 名單見檔頭 helper

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
     <> array['audience','color','content','coordinate','createdAt','id','pickedUpAt','style'] then
    raise exception 'FAIL: unexpected JSON shape %', r;
  end if;
  -- 兩個代號皆可省略；預設值指向對照表中的具體項目（1 ＝ 現行黃色），不是抽象的「預設槽」
  if (r->>'color')::int <> 1 or (r->>'style')::int <> 1 then
    raise exception 'FAIL: default style codes %', r;
  end if;
  -- audience 亦可省略，預設為給任何人撿
  if r->>'audience' <> 'anyone' then
    raise exception 'FAIL: default audience %', r;
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
-- 附上限數字，旅人才知道要刪掉多少字（client 不必為了取得 500 去解析錯誤字串）
select pg_temp.expect_error($$select public.drop_note(repeat('あ', 501), 35.6595, 139.7005)$$,
                            'content_too_long', '{"maxChars": 500}');
select pg_temp.expect_error($$select public.drop_note('x', 91, 139.7005)$$, 'invalid_coordinates');
select pg_temp.expect_error($$select public.drop_note('x', 35.6595, null)$$, 'invalid_coordinates');

-- 500 字元恰好合法（char_length 算 code point）
do $$ begin perform public.drop_note(repeat('あ', 500), 35.66200, 139.7005); end $$;

-- B 再放一張 70m 處的便條（nearby 可見但不可撿）；刻意給非預設代號，驗證它一路走到 wire
do $$ begin perform public.drop_note('at 70m', 35.66013090, 139.7005, 7, 3); end $$;
-- B 放一張 130m 處的便條（nearby 不可見）
do $$ begin perform public.drop_note('at 130m', 35.66067167, 139.7005); end $$;

-- ─── style 代號：預設、可省略、互不干擾、超出對照表範圍照收 ─────────────────
-- 後端只存代號、不理解語意（對照表在裝置端）⇒ 超範圍的值必須原樣存、原樣回，且無錯誤訊號
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000e');
do $$
declare r jsonb;
begin
  -- 兩欄位各自獨立：給不同值不得互換、不得被打包成單一數字
  r := public.drop_note('codes', 10.0, 10.0, 7, 3);
  if (r->>'color')::int <> 7 or (r->>'style')::int <> 3 then
    raise exception 'FAIL: explicit codes not round-tripped: %', r;
  end if;
  -- 只給一個，另一個補伺服器預設
  r := public.drop_note('color only', 10.0, 10.0, 9);
  if (r->>'color')::int <> 9 or (r->>'style')::int <> 1 then
    raise exception 'FAIL: partial codes: %', r;
  end if;
  -- 超出裝置端對照表的代號：照收（後端不維護第二份合法值清單）
  r := public.drop_note('unknown codes', 10.0, 10.0, 999, 32767);
  if (r->>'color')::int <> 999 or (r->>'style')::int <> 32767 then
    raise exception 'FAIL: out-of-table codes not accepted verbatim: %', r;
  end if;
end $$;

-- 粗檢只擋型別與範圍（非正整數、超出 smallint），不擋語意
select pg_temp.expect_error($$select public.drop_note('x', 10.0, 10.0, 0, 1)$$, 'invalid_style_code');
select pg_temp.expect_error($$select public.drop_note('x', 10.0, 10.0, 1, -1)$$, 'invalid_style_code');
select pg_temp.expect_error($$select public.drop_note('x', 10.0, 10.0, 32768, 1)$$, 'invalid_style_code');

-- ─── 私人便條（audience）：不進他人探索、他人撿不到、不佔上限 ─────────────────
-- F 在同一點放兩張：一張公開、一張旅遊紀錄。過濾若失效，E 的探索會看到兩張——
-- 刻意不用「只放私人便條、斷言探索為空」，那種測試在整支查詢壞掉時也會綠。
reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000f');
do $$
declare r jsonb;
begin
  perform public.drop_note('public at 20,20', 20.0, 20.0);
  r := public.drop_note('journal at 20,20', 20.0, 20.0, p_audience => 'self');
  if r->>'audience' <> 'self' then
    raise exception 'FAIL: audience not round-tripped: %', r;
  end if;
end $$;

-- 不合法的值一律大聲失敗：靜默走預設會把旅人以為私密的便條變成公開的
select pg_temp.expect_error(
  $$select public.drop_note('x', 20.0, 20.0, p_audience => 'public')$$, 'invalid_audience');
select pg_temp.expect_error(
  $$select public.drop_note('x', 20.0, 20.0, p_audience => 'SELF')$$, 'invalid_audience');
select pg_temp.expect_error(
  $$select public.drop_note('x', 20.0, 20.0, p_audience => '')$$, 'invalid_audience');

-- 私人便條的 id：RLS 下 E 看不到，先以 postgres 身分存起來（否則測試會變成空測）
reset role;
select set_config('test.journal_id',
  -- 作者條件不可省：外來使用者留下同內容的便條會讓這個純量子查詢炸成
  -- 「more than one row returned by a subquery」，訊息同樣不指向真因
  (select id::text from public.notes
    where content = 'journal at 20,20' and author_id = any(pg_temp.fixture_users())), true);

select pg_temp.login('00000000-0000-0000-0000-00000000000e');
do $$
declare r jsonb := public.nearby_notes(20.0, 20.0);
        ours jsonb;
begin
  -- F 在 (20,20) 放了一公開一私人；E 從同一點查只該看到公開的那張
  ours := pg_temp.ours(r->'items');
  if jsonb_array_length(ours) <> 1
     or ours->0->>'id' = current_setting('test.journal_id') then
    raise exception 'FAIL: private note leaked into nearby: %', ours;
  end if;
  -- 他人撿私人便條 → note_not_found。不新增「這是私人便條」的 token：
  -- 那等於向外人確認該座標存在一張他看不到的便條
  perform pg_temp.expect_error(
    format($q$select public.pickup_note('%s', 20.0, 20.0)$q$, current_setting('test.journal_id')),
    'note_not_found');
end $$;

reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000f');
do $$
declare p jsonb := public.my_notes();
begin
  -- 作者自己撿 → own_note；對自己的便條沒有隱藏的必要（也不會有 UI 走到這）
  perform pg_temp.expect_error(
    format($q$select public.pickup_note('%s', 20.0, 20.0)$q$, current_setting('test.journal_id')),
    'own_note');
  -- 私人便條正常出現在自己的列表，且列表分得出哪些是旅遊紀錄
  if (select count(*) from jsonb_array_elements(p->'items') n
      where n->>'content' = 'journal at 20,20' and n->>'audience' = 'self') <> 1
     or (select count(*) from jsonb_array_elements(p->'items') n
         where n->>'content' = 'public at 20,20' and n->>'audience' = 'anyone') <> 1 then
    raise exception 'FAIL: my_notes audience %', p;
  end if;
end $$;

reset role;
select pg_temp.login('00000000-0000-0000-0000-00000000000b');

-- ─── nearby_notes：半徑、排序、pickable、排除自己 ──────────────────────────
-- B 自己查：自己的便條一律不出現
do $$
declare r jsonb := public.nearby_notes(35.6595, 139.7005);
        ours jsonb;
begin
  -- items 必須是陣列而非 null（零結果時為 []，client 不必為 null 寫分支）。
  -- is null 那半不可省：鍵不存在時 jsonb_typeof 回 SQL NULL，`NULL <> 'array'` 也是 NULL，
  -- plpgsql 的 if 不會觸發 ⇒ 少了它這條檢查是死的，整段會靜默全綠
  if r->'items' is null or jsonb_typeof(r->'items') <> 'array' then
    raise exception 'FAIL: items 應為陣列（零結果時是 [] 而非 null）: %', r;
  end if;
  ours := pg_temp.ours(r->'items');
  if ours <> '[]'::jsonb then
    raise exception 'FAIL: own notes appeared in nearby: %', ours;
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
  for h in select * from jsonb_array_elements(pg_temp.ours(r->'items')) loop
    i := i + 1;
    -- 提示的精確鍵集：不含 content 與任何作者資訊
    if (select array_agg(k order by k) from jsonb_object_keys(h) k)
       <> array['color','coordinate','createdAt','distanceM','id','pickable','style'] then
      raise exception 'FAIL: unexpected hint shape %', h;
    end if;
    if (select array_agg(k order by k) from jsonb_object_keys(h->'coordinate') k)
       <> array['latitude','longitude'] then
      raise exception 'FAIL: hint coordinate not nested: %', h->'coordinate';
    end if;
    if (h->>'createdAt') !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$' then
      raise exception 'FAIL: hint createdAt format %', h->>'createdAt';
    end if;
    -- 地圖 pin 要拿作者當初選的代號才渲染得出對應樣式（預設 1/1 與非預設 7/3 各一張）
    if i = 1 and not ((h->>'distanceM')::int <= 5 and (h->>'pickable')::boolean
                      and (h->>'color')::int = 1 and (h->>'style')::int = 1) then
      raise exception 'FAIL: nearest row wrong: %', h;
    end if;
    if i = 2 and not ((h->>'distanceM')::int between 60 and 80 and not (h->>'pickable')::boolean
                      and (h->>'color')::int = 7 and (h->>'style')::int = 3) then
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
  (select id::text from public.notes
    where content = 'hello from Tokyo' and author_id = any(pg_temp.fixture_users())), true);
select pg_temp.login('00000000-0000-0000-0000-00000000000a');
do $$
declare c int := jsonb_array_length(public.nearby_notes(35.6595, 139.7005)->'items');
begin
  if c <> 20 then raise exception 'FAIL: nearby limit, expected 20 got %', c; end if;
end $$;

-- ─── pickup_note：距離、獨佔、冪等、診斷碼 ─────────────────────────────────
do $$
declare v_id uuid := current_setting('test.hello_id')::uuid; r jsonb; r2 jsonb; d text;
begin
  -- 70m 外 → too_far，且附上伺服器**當下**算出的真實距離（不是 app 沿用上次探索的估計值）。
  -- 距離是量測值，故以範圍斷言——寫死數字等於把 PostGIS 的算法抄進測試
  begin
    perform public.pickup_note(v_id, 35.66013090, 139.7005);
    raise exception 'FAIL: expected too_far, but call succeeded';
  exception when others then
    if sqlerrm <> 'too_far' then raise; end if;
    get stacked diagnostics d = pg_exception_detail;
    if (d::jsonb->>'distanceM')::int not between 60 and 80 then
      raise exception 'FAIL: too_far details %', d;
    end if;
  end;

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

-- distance_m 不授權給任何 client 角色：兩支呼叫端都是 SECURITY DEFINER，它不需要上檯面。
-- 授權了就等於在契約外多開一支 RPC（as_wire_ts／as_cursor 是兩支 SECURITY INVOKER
-- 列表逼出來的例外，這支沒有那個理由）
select pg_temp.expect_error($$select public.distance_m(null, null)$$, 'permission denied%');

-- anon role 完全不可達
reset role;
set local role anon;
select pg_temp.expect_error($$select * from public.nearby_notes(35.6595, 139.7005)$$, 'permission denied%');
select pg_temp.expect_error($$select public.drop_note('x', 35.6595, 139.7005)$$, 'permission denied%');
select pg_temp.expect_error($$select count(*) from public.notes$$, 'permission denied%');
select pg_temp.expect_error($$select public.distance_m(null, null)$$, 'permission denied%');
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
  -- 先放 3 張旅遊紀錄：上限若誤計私人便條，下面第 48 張公開便條就會被擋
  for i in 1..3 loop
    perform public.drop_note('journal ' || i, 35.0, 135.0, p_audience => 'self');
  end loop;
  for i in 1..50 loop
    perform public.drop_note('cap ' || i, 35.0 + i * 0.001, 135.0);
  end loop;
  -- 上限數字隨錯誤附上：旅人才理解為什麼不能再留（且數字改了不必發 app 版）
  perform pg_temp.expect_error(
    $q$select public.drop_note('cap 51', 35.1, 135.0)$q$, 'active_note_limit',
    '{"maxActiveNotes": 50}');
  -- 公開便條已滿，旅遊紀錄仍可繼續——這正是把私人便條排除在上限外的理由
  perform public.drop_note('journal after cap', 35.1, 135.0, p_audience => 'self');
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
      -- 建議重試秒數：本測試在單一交易內，now() 恆定 ⇒ 60 次撿取全部同刻、
      -- 時間窗一秒都還沒滑動，故恰為整個窗長 3600
      perform pg_temp.expect_error(
        format($q$select public.pickup_note('%s', 36.0, 135.0)$q$, v_id), 'pickup_rate_limited',
        '{"retryAfterS": 3600}');
    end if;
  end loop;
  if n <> 61 then raise exception 'FAIL: rate-limit loop ran % times, expected 61', n; end if;
end $$;

-- D 此刻正被閘門擋著。對「自己已經撿到的那張」重試仍須成功——冪等重試不新增任何撿取，
-- 擋下它等於在旅人撿得最勤的時候，收回 notes.md §6「timeout 後可安心重試同一筆」的承諾。
-- 同時確認閘門沒有因此被整個關掉：真正新的撿取照樣擋。
--
-- 先把第一次撿取回撥 17 分鐘：單一 transaction 內 now() 恆定，不回撥就分不出
-- 「重試回的是原本的 pickedUpAt」還是「被重新寫成當下時間」——兩者長得一模一樣。
-- 回撥後窗內仍有 60 次 ⇒ 閘門照樣跳，且建議秒數變成算出來的 3600−1020＝2580
-- （上一段在 now() 恆定下只驗得到整個窗長 3600，驗不到它是算的還是寫死的）。
-- 斷言完會還原，否則下方「D 的收藏 60 筆全部同刻」那段的前提會被這裡悄悄弄壞。
reset role;
update public.notes set picked_up_at = picked_up_at - interval '17 minutes'
 where id = (string_to_array(current_setting('test.rl_ids'), ',')::uuid[])[1];
select set_config('test.rl_first_at',
  (select picked_up_at::text from public.notes
    where id = (string_to_array(current_setting('test.rl_ids'), ',')::uuid[])[1]), true);
select pg_temp.login('00000000-0000-0000-0000-00000000000d');
do $$
declare v_ids uuid[] := string_to_array(current_setting('test.rl_ids'), ',')::uuid[];
        v_was timestamptz := current_setting('test.rl_first_at')::timestamptz;
        r jsonb;
begin
  r := public.pickup_note(v_ids[1], 36.0, 135.0);
  if r->>'id' <> v_ids[1]::text then
    raise exception 'FAIL: 頻率閘門下的冪等重試沒回原便條, got %', r;
  end if;
  if r->>'pickedUpAt' <> public.as_wire_ts(v_was) then
    raise exception 'FAIL: 冪等重試改寫了 pickedUpAt: was %, got %',
      public.as_wire_ts(v_was), r->>'pickedUpAt';
  end if;
  perform pg_temp.expect_error(
    format($q$select public.pickup_note('%s', 36.0, 135.0)$q$, v_ids[61]), 'pickup_rate_limited',
    '{"retryAfterS": 2580}');
end $$;
reset role;
update public.notes set picked_up_at = picked_up_at + interval '17 minutes'
 where id = (string_to_array(current_setting('test.rl_ids'), ',')::uuid[])[1];

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
  -- 代號走完整條路徑：列表拿到的是作者當初選的值，不是預設
  if (select count(*) from jsonb_array_elements(p->'items') n
      where n->>'content' = 'at 70m' and (n->>'color')::int = 7 and (n->>'style')::int = 3) <> 1 then
    raise exception 'FAIL: style codes lost in my_notes';
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
