-- T11-03：兩支列表改回傳 envelope ＋ 不透明游標。
-- 三個分頁參數（筆數上限、排序鍵、id）收斂為兩個（筆數上限、單一游標字串）：
-- client 唯一的義務變成「把 nextCursor 原樣回傳」，於是
--   「timestamp 必須 byte-for-byte 原樣回傳」與「兩欄位必須成對」兩類 client bug 一併消失。
-- 版本號讓日後換排序策略（距離／熱門／推薦）能辨識並拒絕舊游標，而不必改變 API 形狀。

-- ─── 游標的唯一編解碼處 ─────────────────────────────────────────────────────
-- base64(JSON{版本, 排序鍵名, 排序鍵值, id})。兩道閘門各擋一種漂移：
--   v  擋「編碼格式」變更；
--   k  擋「排序語意」變更——日後某支列表改以距離／熱門度排序時舊游標即失效，
--      同時讓兩支列表的游標無法互換（否則 my_collection 的游標會被 my_notes
--      拿去跟 created_at 比較，靜默回錯頁而毫無訊號）。
-- ponytail: 不簽章不加密，且用標準 base64 而非 base64url。天花板有二：
--   (a) 游標不授予任何權限，查詢永遠限縮在呼叫者自己的資料範圍內（RLS ＋ where），
--       竄改最多只能改變自己看到的起點——哪天游標開始編碼「跨使用者」的查詢條件就得加簽章；
--   (b) payload 長度固定（uuid 36 ＋ 時間戳 27 ＋ 固定鍵名），實測 10000 個游標
--       零個 + 或 /，故放進 URL query 也安全——哪天 payload 改成變動長度就改用 base64url。
create function public.as_cursor(p_sort_key text, p_key timestamptz, p_id uuid) returns text
language sql stable set search_path = ''
as $$
  select translate(   -- encode 每 76 字元插一個換行，wire 上不要它
    encode(convert_to(
      jsonb_build_object('v', 1, 'k', p_sort_key, 't', public.as_wire_ts(p_key), 'i', p_id)::text,
      'utf8'), 'base64'),
    E'\n', '')
$$;

comment on function public.as_cursor(text, timestamptz, uuid) is
  '排序鍵 → 不透明游標字串。內部結構是實作細節，不屬於契約。';

create function public.parse_cursor(
  p_cursor   text,
  p_sort_key text,                          -- 呼叫端宣告自己用哪個排序鍵，不符即拒
  out o_key  timestamptz,
  out o_id   uuid
)
language plpgsql stable set search_path = ''
as $$
declare v jsonb;
begin
  if p_cursor is null then return; end if;   -- 沒帶游標 ＝ 第一頁
  begin
    v := convert_from(decode(p_cursor, 'base64'), 'utf8')::jsonb;
    if (v->>'v')::int is distinct from 1        then raise exception 'cursor version';  end if;
    if  v->>'k'      is distinct from p_sort_key then raise exception 'cursor sort key'; end if;
    o_key := (v->>'t')::timestamptz;
    o_id  := (v->>'i')::uuid;
  exception when others then
    raise exception 'invalid_cursor';       -- 解碼／解析／版本／排序鍵，對外都是同一個 token
  end;
  -- 靜默退化會掉列或無限翻頁 ⇒ 一律大聲失敗
  if o_key is null or o_id is null then raise exception 'invalid_cursor'; end if;
end;
$$;

-- 兩支列表 RPC 是 SECURITY INVOKER，故 authenticated 需要執行權（同 as_wire_ts 的理由）。
-- 兩支都是純字串運算、不碰任何資料。
revoke execute on function public.as_cursor(text, timestamptz, uuid) from public, anon;
revoke execute on function public.parse_cursor(text, text)           from public, anon;
grant  execute on function public.as_cursor(text, timestamptz, uuid) to authenticated;
grant  execute on function public.parse_cursor(text, text)           to authenticated;

-- ─── 舊簽名退場（參數與回傳型別都改變 ⇒ 一律先 drop）────────────────────────
drop function public.my_notes(integer, timestamptz, uuid);
drop function public.my_collection(integer, timestamptz, uuid);

-- ─── my_notes ────────────────────────────────────────────────────────────────
create function public.my_notes(
  p_limit  integer default 50,
  p_cursor text    default null
) returns jsonb
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_key timestamptz;
  v_id  uuid;
  v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100);
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select o_key, o_id into v_key, v_id from public.parse_cursor(p_cursor, 'created_at');

  return (
    with page as (
      select n.id, n.created_at, public.as_note_wire(n) as w
      from public.notes n
      where n.author_id = v_uid
        and (v_key is null or (n.created_at, n.id) < (v_key, v_id))
      order by n.created_at desc, n.id desc
      limit v_lim + 1        -- 多取一筆：只用來判斷還有沒有下一頁，不上 wire
    ), numbered as (
      select p.*, row_number() over (order by p.created_at desc, p.id desc) as rn from page p
    )
    select jsonb_build_object(
      'items', coalesce((select jsonb_agg(x.w order by x.rn) from numbered x where x.rn <= v_lim),
                        '[]'::jsonb),
      -- 只有真的還有下一筆才給游標 ⇒ nextCursor 為 null 是確定的終止訊號
      'nextCursor', (select public.as_cursor('created_at', x.created_at, x.id) from numbered x
                     where x.rn = v_lim and exists (select 1 from numbered y where y.rn > v_lim))
    )
  );
end;
$$;

-- ─── my_collection ───────────────────────────────────────────────────────────
create function public.my_collection(
  p_limit  integer default 50,
  p_cursor text    default null
) returns jsonb
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_key timestamptz;
  v_id  uuid;
  v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100);
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  select o_key, o_id into v_key, v_id from public.parse_cursor(p_cursor, 'picked_up_at');

  return (
    with page as (
      select n.id, n.picked_up_at, public.as_note_wire(n) as w
      from public.notes n
      where n.picked_up_by = v_uid
        and (v_key is null or (n.picked_up_at, n.id) < (v_key, v_id))
      order by n.picked_up_at desc, n.id desc
      limit v_lim + 1
    ), numbered as (
      select p.*, row_number() over (order by p.picked_up_at desc, p.id desc) as rn from page p
    )
    select jsonb_build_object(
      'items', coalesce((select jsonb_agg(x.w order by x.rn) from numbered x where x.rn <= v_lim),
                        '[]'::jsonb),
      'nextCursor', (select public.as_cursor('picked_up_at', x.picked_up_at, x.id) from numbered x
                     where x.rn = v_lim and exists (select 1 from numbered y where y.rn > v_lim))
    )
  );
end;
$$;

-- ─── Grants（drop 後權限一併消失，照既有慣例逐支收回再授予）──────────────────
revoke execute on function public.my_notes(integer, text)      from public, anon;
revoke execute on function public.my_collection(integer, text) from public, anon;
grant  execute on function public.my_notes(integer, text)      to authenticated;
grant  execute on function public.my_collection(integer, text) to authenticated;
