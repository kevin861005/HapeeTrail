-- T4：未撿的公開便條 90 天後退出地圖。
--
-- 過期是**讀時推導**的（created_at + TTL），不是儲存的狀態：
--   * 沒有 cron、沒有 expired_at 欄位、沒有會失敗的活動元件
--   * 符合 spec 對狀態欄位的規定——「必須由伺服器推導而非儲存」
--   * 過期便條**仍留在作者的 my_notes 裡**（spec user story 13：「不會有便條莫名消失」），
--     只是不再進探索、也撿不走
--
-- 只有「未撿的公開便條」會過期。已撿走的在別人的收藏裡（刪它是毀別人的東西），
-- 旅遊紀錄是使用者的私人紀錄（刪它是資料遺失）——兩類都不碰。
--
-- ponytail: 過期列永遠留在儲存與探索的熱索引裡。partial index 的述詞必須 IMMUTABLE，
-- 塞不進 now()（實測 `functions in index predicate must be marked IMMUTABLE`），所以
-- TTL 只能當成查詢條件，索引篩不掉它們。天花板已量化在下方 EXPLAIN 註解：探索本來就
-- 限縮在 100m，多一個 created_at 比較不影響查詢速度，膨脹的是索引大小。
-- 升級路徑：等索引真的大到有感，加一支 cron 把「早就過期又沒人撿」的列刪掉即可——
-- 那時它才真的是「pg_cron 一句 delete」，而且不必動任何查詢邏輯。

-- TTL 的單一真相。四個地方要用（探索、撿取、未撿額度、wire 的 expiresAt），
-- 散成四份常數遲早漂移，而漂移的症狀是「地圖上看不到但額度還被佔著」這種查不出來的 bug。
-- 不授權給任何 client 角色：它只被 SECURITY DEFINER 的 RPC 呼叫，client 不需要知道
-- 這個數字——要顯示倒數就讀 wire 上的 expiresAt（同「不得硬編 50m/100m」的原則）。
create function public.note_ttl() returns interval
language sql immutable set search_path = ''
as $$ select interval '90 days' $$;

comment on function public.note_ttl() is
  '未撿公開便條的存活期。探索／撿取／未撿額度／expiresAt 共用的唯一來源。';

revoke execute on function public.note_ttl() from public, anon, authenticated;

-- ─── wire 加上 expiresAt ─────────────────────────────────────────────────────
-- 推導值，不落地。旅遊紀錄不會過期 ⇒ null（client 據此就知道「這張不會消失」）。
-- 已撿走的便條保留它——那是關於這張便條的事實，不隨狀態改變；client 要判斷
-- 「還在地圖上嗎」用 pickedUpAt is null && now < expiresAt，兩個欄位都在 wire 上。
create or replace function public.as_note_wire(n public.notes) returns jsonb
language sql stable set search_path = ''
as $$
  select jsonb_build_object(
    'id',         n.id,
    'content',    n.content,
    'color',      n.color,
    'style',      n.style,
    'audience',   n.audience,
    'coordinate', jsonb_build_object('latitude', n.lat, 'longitude', n.lng),
    'createdAt',  public.as_wire_ts(n.created_at),
    'expiresAt',  case when n.audience = 'anyone'
                       then public.as_wire_ts(n.created_at + public.note_ttl()) end,
    'pickedUpAt', public.as_wire_ts(n.picked_up_at)
  )
$$;

-- ─── 探索：過期便條退出 ──────────────────────────────────────────────────────
-- EXPLAIN（10 萬張便條散在全球、半數已過期）：計畫形狀不變——仍是
-- `Index Scan using notes_active_location_gix`，`created_at` 條件落在 Filter。
-- 這是**可複現的**部分，也是專案守則要求確認的事（地理查詢必須走索引）。
-- ⚠️ 已知的計畫變化：便條密度極端集中時（大量便條擠在同一個小區域），planner 可能改用
-- BitmapAnd 把 notes_author_ix 併進來掃 created_at，掃描列數會跳到全表等級。
-- **刻意不在此記錄毫秒數**：實測過幾組合成資料，絕對值高度依賴「探測點附近有幾張便條」
-- 與快取狀態，不同密度下甚至會得到相反的結論（加 TTL 更快／更慢都量得到），
-- 寫死一組數字只會誤導後人。要判斷時請照當下的真實資料重測。
-- 真的撞到計畫劣化時，解法是讓過期列離開資料表（一支 cron delete），而不是動查詢——
-- 查詢邏輯本身在那時仍然是對的。
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

  -- 內層挑出 ≤20 筆最近的，外層才組 JSON。where 的 audience 條件與
  -- notes_active_location_gix 的索引述詞逐字一致——不一致的話查詢計畫用不到它，
  -- 探索會靜默退化成全表掃描。（TTL 條件無法進索引述詞，理由見檔頭。）
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
        and n.created_at > now() - public.note_ttl()   -- 過期便條退出地圖（T4）
        and extensions.st_dwithin(n.location, v_loc, 100.0)
      order by distance_m                 -- 取最近的 20 筆（外層 jsonb_agg 另有自己的排序）
      limit 20
    ) h
  );
end;
$$;

-- ─── 撿取：過期便條撿不走，且與不存在同一個答案 ────────────────────────────────
-- 對外人回 note_not_found 而不新增 token：過期便條已經退出地圖，沒有理由讓外人分辨
-- 「這裡曾經有一張」與「這裡什麼都沒有」——與私人便條同一個立場。
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
    -- 已經是你的 ⇒ 這是重送，不是新的撿取（冪等重試不新增任何撿取，不該被閘門擋下）。
    -- 與下方診斷段的冪等分支同一個判準
    select * into v_row from public.notes n
     where n.id = p_note_id and n.picked_up_by = v_uid;
    if found then return public.as_note_wire(v_row); end if;
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
     and n.created_at > now() - public.note_ttl()   -- 過期便條撿不走（T4）
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
  elsif v_row.created_at <= now() - public.note_ttl() then
    raise exception 'note_not_found';    -- 過期便條同理：已退出地圖，不必讓外人分辨
  else
    raise exception 'too_far'
      using detail = jsonb_build_object('distanceM', public.distance_m(v_row.location, v_loc))::text;
  end if;
end;
$$;

-- ─── 未撿額度：過期便條不再計入 ──────────────────────────────────────────────
-- 不改的話，貼滿 50 張的人就算便條全部過期退出地圖，額度仍被永久佔著——
-- 一個「防濫用」的限制變成「永遠留不了新便條」。
create or replace function public.drop_note(
  p_content  text,
  p_lat      double precision,
  p_lng      double precision,
  p_color    integer default null,          -- 省略 ＝ 由伺服器補預設
  p_style    integer default null,
  p_audience text    default null
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  -- 上限與它附帶的數字必須是同一個常數：分成兩處遲早漂移，
  -- 而漂移的症狀是「app 說刪到 500 字，伺服器仍然拒收」這種沒人看得懂的 bug。
  -- ⚠️ 字數上限還有第三份：table 的 notes_content_len CHECK。調高 c_max_chars 而忘了它，
  -- 症狀是 insert 撞原生 23514 而非我們的 content_too_long（約束無法引用函式常數）
  c_max_chars   constant integer := 500;
  c_max_active  constant integer := 50;
  c_max_private constant integer := 5000;
  v_uid      uuid    := (select auth.uid());
  -- 字數上限量的是 trim 之後的內容，順序不能反
  v_content  text    := regexp_replace(p_content, '^\s+|\s+$', '', 'g');
  v_color    integer;                       -- 刻意不是 smallint：超上界要走我們的 token，
  v_style    integer;                       -- 不是 plpgsql 賦值時的 "smallint out of range"
  v_audience text    := coalesce(p_audience, 'anyone');
  v_row      public.notes;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  if v_content is null or v_content = '' then raise exception 'content_empty'; end if;
  if char_length(v_content) > c_max_chars then
    raise exception 'content_too_long'
      using detail = jsonb_build_object('maxChars', c_max_chars)::text;
  end if;
  -- 只做型別與範圍粗檢，不驗證語意：超出裝置端對照表範圍的代號會被接受並原樣儲存
  v_color := coalesce(p_color, 1);
  v_style := coalesce(p_style, 1);
  if v_color not between 1 and 32767 or v_style not between 1 and 32767 then
    raise exception 'invalid_style_code';
  end if;
  -- 這裡相反：值必須被後端理解，不認得就拒絕（不比對大小寫、不 trim——
  -- 猜使用者的意圖在這個欄位上的失敗成本是「私密內容變公開」）
  if v_audience not in ('anyone', 'self') then raise exception 'invalid_audience'; end if;

  -- 兩個上限刻意分開，各管各的：公開便條的額度是「未撿的才算」（被撿走或過期就釋放），
  -- 旅遊紀錄永遠不會被撿走也不會過期，所以它的額度只能是絕對總量。混成一個計數會讓
  -- 「公開便條滿了就不能再記錄旅程」，那正是把私人便條排除在 active 之外要避免的事。
  -- ponytail: advisory 上限（併發下可小幅超越），防匿名帳號灑滿地圖；觀察到濫用再調。
  if v_audience = 'anyone' then
    if (select count(*) from public.notes n
        where n.author_id = v_uid and n.picked_up_at is null
          and n.audience = 'anyone'
          and n.created_at > now() - public.note_ttl()) >= c_max_active then
      raise exception 'active_note_limit'
        using detail = jsonb_build_object('maxActiveNotes', c_max_active)::text;
    end if;
  else
    if (select count(*) from public.notes n
        where n.author_id = v_uid and n.audience = 'self') >= c_max_private then
      raise exception 'private_note_limit'
        using detail = jsonb_build_object('maxPrivateNotes', c_max_private)::text;
    end if;
  end if;

  insert into public.notes (author_id, content, lat, lng, color, style, audience)
  values (v_uid, v_content, p_lat, p_lng, v_color, v_style, v_audience)
  returning * into v_row;

  return public.as_note_wire(v_row);
end;
$$;
