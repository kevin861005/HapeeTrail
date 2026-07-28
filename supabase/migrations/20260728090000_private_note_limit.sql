-- T13：私人便條的絕對上限。
--
-- 旅遊紀錄不佔未撿額度、也永遠不會被撿走，於是在此之前單一帳號可以無限累積
-- （實測連建 200 張全數成功，之後仍可留滿 50 張公開便條）。spec 明文接受過這個天花板，
-- 但它的論證是「別讓認真記錄的旅人撞到一個為了防濫用而設的限制」——那支持的是
-- 「更高的上限」，不是「沒有上限」。
--
-- ponytail: 5000 張 ≈ 每天寫一張寫 13 年，真實使用者永遠碰不到；擋的是單一帳號的
-- 無限累積，不是定向攻擊——匿名註冊無限量，換帳號就繞過（同 ADR-0003 的 advisory 立場）。
-- 與 active_note_limit 一樣是 advisory：併發下可小幅超越，可以接受。
-- 升級路徑：真出現跨帳號的儲存濫用訊號，要做的是 App Attest／裝置級識別，不是把這個數字調小。

-- 計數走專用的 partial index。實測（1000 個作者各 30 張公開 ＋ 受測者 4999 張私人，
-- VACUUM ANALYZE 後）：
--   有索引 → Index Only Scan，7 buffers，Heap Fetches: 0，0.36ms
--   沒有   → Bitmap Heap Scan on notes_author_ix，97 buffers，0.71ms
-- 約 14 倍 buffer 差；絕對成本都在 1ms 內，而且只有逼近上限的人才付。
-- ⚠️ 不要寫成「沒有它就會 Seq Scan」——那只在單一作者佔全表絕大多數的退化分佈下成立
-- （真實分佈下 planner 仍會用 notes_author_ix），而在那種分佈下 Seq Scan 本來就是正解。
-- partial index 只索引 audience = 'self' 的列 ⇒ 公開便條的寫入完全不必維護它，
-- 而且「同一作者累積大量已撿走的公開便條」這種分佈對私人計數完全沒有影響
-- （實測 5 萬張已撿公開便條，計數不受牽連）——`where audience='self'` 把它們排除在索引外。
create index notes_author_private_ix on public.notes (author_id)
  where audience = 'self';

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
  v_content  text    := btrim(p_content);
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
