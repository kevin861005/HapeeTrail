-- T16：內容的 trim 改認完整的 Unicode 空白。
--
-- 單參數的 btrim() 只剝 U+0020 一個字元——連 tab 與換行都不剝，更別說全形空白。
-- 於是 `\t\n`、`　　`（U+3000）、NBSP 這類內容都能建立便條，在地圖上是一張看起來空白的
-- 便條，而兩側都不會有任何錯誤訊號。CJK 使用者按到全形空白鍵並不罕見。
--
-- 順帶對齊 client：各平台慣用的「whitespaces and newlines」（Zs ＋ U+0009–000D ＋ U+0085）
-- 與 UTF-8 locale 下 Postgres 的 \s 幾乎同一套。兩邊不一致的症狀是「app 預檢說可以送，
-- 伺服器說 content_empty」或字數算不一樣——與契約已警告過的 grapheme vs code point 同類。
-- 「幾乎」是實測後的用字：PG 的 \s 另外涵蓋 U+001C–U+001F（C0 資訊分隔符，不是 Unicode
-- White_Space）。實務上打不出來，但契約文件必須照實寫，不能宣稱兩邊完全等同。
--
-- ⚠️ **有 locale 依賴**：\s 認不認得 U+3000／NBSP 取決於資料庫的 ctype。
-- 本機 en_US.UTF-8 認得；C locale 不認得，那樣這個修正會靜默地什麼都沒做
-- （tab／換行仍會被擋，因為它們在任何 locale 下都是空白）——「本機綠、線上無效」。
-- 靠人記得去驗守不住，所以下面直接擋在套用時：locale 不支援就 db push 失敗，
-- 而不是安靜地上線。真的撞到的話，退路是把 \s 換成顯式字元集的 btrim
-- （確定、與 locale 無關，代價是要自己維護那份 Unicode 空白清單）。
--
-- ponytail: 這只擋常見的意外，擋不住刻意的空白便條——零寬空白 U+200B–200D、word joiner
-- U+2060、BOM U+FEFF、U+180E 在 Unicode 裡是格式字元而非空白，任何 trim 寫法都攔不到。要連它們一起剝就得處理整個
-- Cf 類別，接著是變體選擇符、組合字元⋯⋯「看起來是空的」沒有可判定的邊界。真出現濫用，
-- 要做的是內容審查（T3 的檢舉機制），不是把 trim 的字元集愈修愈長。
--
-- ^ 與 $ 只匹配字串頭尾（未給 n 旗標），所以多行內容中間那幾行的縮排不受影響——
-- 那是內容的一部分，咬掉會改變使用者寫的東西。
-- 套用時就驗，不留給部署後的人工檢查
do $$
begin
  if not (chr(12288) ~ '^\s$' and chr(160) ~ '^\s$') then
    raise exception using
      message = '資料庫的 ctype 不認得 Unicode 空白（U+3000／NBSP），T16 的 trim 會靜默失效',
      detail  = format('目前 ctype=%s。此 migration 依賴 \s 涵蓋完整 Unicode 空白。',
                       (select datctype from pg_database where datname = current_database())),
      hint    = '改用顯式字元集的 btrim，或以支援 UTF-8 的 locale 建立資料庫';
  end if;
end $$;

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
  -- 字數上限量的是 trim 之後的內容，順序不能反（見檔頭）
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

  -- 兩個上限刻意分開，各管各的：公開便條的額度是「未撿的才算」（被撿走就釋放），
  -- 旅遊紀錄永遠不會被撿走，所以它的額度只能是絕對總量。混成一個計數會讓
  -- 「公開便條滿了就不能再記錄旅程」，那正是把私人便條排除在 active 之外要避免的事。
  -- ponytail: advisory 上限（併發下可小幅超越），防匿名帳號灑滿地圖；觀察到濫用再調。
  if v_audience = 'anyone' then
    if (select count(*) from public.notes n
        where n.author_id = v_uid and n.picked_up_at is null
          and n.audience = 'anyone') >= c_max_active then
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
