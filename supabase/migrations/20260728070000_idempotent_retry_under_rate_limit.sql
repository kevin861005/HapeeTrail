-- T15：冪等重試不該被撿取頻率閘門擋下。
--
-- 閘門排在冪等診斷之前，於是窗內撿滿 60 次之後，對「自己已經撿到的那張」重試會拿到
-- pickup_rate_limited——而 docs/api/notes.md §6 對 iOS 承諾的是「timeout 後可安心重試同一筆」。
-- 這條承諾恰好在最需要它的時候失效：撿得最勤的旅人正是最可能撞上行動網路掉回應的人，
-- 而他此時看到的錯誤還會讓 client 以為「這次撿取沒成功」——事實上它早就成功了。
--
-- 修法：閘門跳起來時，先問一句「這張是不是已經是你的」。是就照常回成功——
-- 冪等重試不新增任何撿取，本來就不該計入防濫用的額度。
-- 這個查詢只在閘門真的跳起來時才跑，happy path 一次都不多付。
-- 閘門對真正新的撿取維持原樣，防座標掃描的效果不受影響（ADR-0003）。
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
    -- 已經是你的 ⇒ 這是重送，不是新的撿取（見檔頭）。與下方診斷段的冪等分支同一個判準
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
