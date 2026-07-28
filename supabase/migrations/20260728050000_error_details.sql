-- T11-06：四種業務錯誤附帶伺服器當下算出的真實數字。
--
-- 在此之前，「再走近一點」的文案只能沿用上一次探索結果的估計值，而字數上限、便條數上限、
-- 建議重試秒數在 client 端只能硬編一份——後端調整就悄悄不一致。四個數字改由錯誤自己帶。
--
-- transport 不變：業務錯誤仍是 HTTP 400 ＋ `P0001`，`message` 仍是唯一的判斷依據，
-- token 字串一個都沒動。PostgREST 把例外的 DETAIL 原樣塞進 `details`，因此
-- **wire 上的 `details` 是一個內容為 JSON 的字串、不是巢狀物件**（2026-07-28 本機實測），
-- client 需二次解析、解析失敗一律視為「沒有附帶資料」。
-- 其餘錯誤不附 details（測試以 expect_error 的預設值守住這條）。

-- ─── drop_note：兩個上限數字改由錯誤帶出 ─────────────────────────────────────
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
  c_max_chars  constant integer := 500;
  c_max_active constant integer := 50;
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
  -- ponytail: advisory 上限（併發下可小幅超越），防匿名帳號灑滿地圖；觀察到濫用再調。
  -- 只管公開便條：旅遊紀錄不會出現在任何人的地圖上，計入或擋下都只是懲罰認真記錄的旅人。
  -- 天花板：私人便條因此沒有數量上限——真出現儲存濫用再另立一個上限。
  if v_audience = 'anyone'
     and (select count(*) from public.notes n
          where n.author_id = v_uid and n.picked_up_at is null
            and n.audience = 'anyone') >= c_max_active then
    raise exception 'active_note_limit'
      using detail = jsonb_build_object('maxActiveNotes', c_max_active)::text;
  end if;

  insert into public.notes (author_id, content, lat, lng, color, style, audience)
  values (v_uid, v_content, p_lat, p_lng, v_color, v_style, v_audience)
  returning * into v_row;

  return public.as_note_wire(v_row);
end;
$$;

-- ─── pickup_note：真實距離與建議重試秒數 ─────────────────────────────────────
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
    -- 與 nearby 提示的 distanceM 同一個算法：兩處若不同，app 會看到「探索說 60m、
    -- 撿取說 70m」這種自相矛盾的畫面
    raise exception 'too_far'
      using detail = jsonb_build_object(
        'distanceM', round(extensions.st_distance(v_row.location, v_loc))::int)::text;
  end if;
end;
$$;
