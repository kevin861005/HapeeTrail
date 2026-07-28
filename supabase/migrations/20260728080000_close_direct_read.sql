-- T12：收掉資料表的直讀路徑，撿起者的 uuid 從此沒有 client 讀得到。
--
-- 問題：RLS 是「自己寫的或自己撿的」，加上 authenticated 有整表 SELECT，
-- 於是便條作者直讀自己那一列就拿得到 picked_up_by ＝ 撿走它的人的 auth.users.id。
-- 契約層明明已經把 uuid 從 wire 上拿掉了（T7），理由是「帳號綁定後 uuid 會變成可連結
-- 真人身分的穩定識別字，且已發出的資料收不回來」——那個理由對這條路徑一樣成立。
--
-- 曾否決的修法：欄位級 grant。兩支列表當時是 SECURITY INVOKER，其 WHERE 引用
-- author_id／picked_up_by 同樣受欄位權限管制，會 permission denied（20260712010000 有註記）。
--
-- 本次改法：兩支列表改 SECURITY DEFINER，然後整個收回表權限。
-- 代價要講清楚：這兩支從此不受 RLS 保護，WHERE 子句成為唯一防線。可以接受的理由是
-- 它們本來就各自明寫 `author_id = v_uid`／`picked_up_by = v_uid`（RLS 只是第二層），
-- 且測試對此有跨使用者的正面斷言——A 沒投放任何便條時 my_notes 必須是空的，
-- 而同一時刻 B 有 29 張；WHERE 若掉了，那條會立刻紅。
-- RLS policy 保留不動（目前沒有角色有 SELECT，等於休眠）：哪天真的要再開表權限，
-- 它就是現成的第二層，而不是要重新想一次。
create or replace function public.my_notes(
  p_limit  integer default 50,
  p_cursor text    default null
) returns jsonb
language plpgsql stable security definer set search_path = ''
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
      where n.author_id = v_uid          -- DEFINER ⇒ 這一行是唯一的隔離保證
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

create or replace function public.my_collection(
  p_limit  integer default 50,
  p_cursor text    default null
) returns jsonb
language plpgsql stable security definer set search_path = ''
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
      where n.picked_up_by = v_uid       -- DEFINER ⇒ 這一行是唯一的隔離保證
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

-- ─── 收回表權限 ─────────────────────────────────────────────────────────────
-- 這一行就是 T12 的本體：沒有它，上面兩支改 DEFINER 只是少繞一層 RLS 而已。
revoke all on table public.notes from anon, authenticated;

-- ⚠️ 以下 revoke 都是「時點修正」，不是結構保證：Supabase 在 public schema 設了
-- default privileges，新建的表預設 grant ALL、新建的函式預設 grant EXECUTE 給
-- anon／authenticated。所以日後若對某支 helper 做 drop ＋ create（而不是 create or
-- replace），它會**靜默重新被授權**。測試對此有守——本檔收回的每一支都有一條
-- `permission denied` 的正面斷言（見 notes.test.sql 的「內部 helper 一律不授權」段）；
-- 但新增的**表**沒有同等覆蓋。要根治得改 schema 的 default privileges，那會影響
-- 往後每一支新 RPC 都必須顯式授權，是專案級的慣例變更，值得單獨決定。

-- ─── 收回四支 helper 的執行權 ───────────────────────────────────────────────
-- 它們被授權給 authenticated 的唯一理由是「兩支 SECURITY INVOKER 列表需要以呼叫者身分
-- 執行它們」。兩支列表改 DEFINER 之後，全部呼叫端都以擁有者身分執行 ⇒ 理由消失。
-- 留著不收等於在契約外白留四支 POST /rest/v1/rpc/{fn}，沒有任何人需要它們。
revoke execute on function public.as_note_wire(public.notes)               from public, anon, authenticated;
revoke execute on function public.as_wire_ts(timestamptz)                  from public, anon, authenticated;
revoke execute on function public.as_cursor(text, timestamptz, uuid)       from public, anon, authenticated;
revoke execute on function public.parse_cursor(text, text)                 from public, anon, authenticated;

comment on table public.notes is
  '便條。client 角色無任何直接權限——一律經五支 SECURITY DEFINER 的 RPC 存取。新增欄位前不必再擔心直讀外洩，但仍須確認它有沒有被 as_note_wire 放上 wire。決策理由見 docs/adr/0007。';
