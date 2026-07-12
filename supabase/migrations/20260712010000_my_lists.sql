-- T6：我的便條／我的收藏改為 RPC ＋ keyset（cursor-based）分頁
-- SECURITY INVOKER：權限由 RLS（notes_select_own）與 table grant 把關，
-- 函式本身不越權——與三支寫入型 DEFINER RPC 刻意區隔。
-- 複合游標 (timestamptz, id) 用 row-value 比較，同刻 timestamp 不掉列不重複；
-- 游標兩欄位只帶一半 → raise 'invalid_cursor'（靜默退化會掉列或無限翻頁）。
-- 回傳不含任何 uuid 身分欄位（author_id / picked_up_by）——見 ADR/T7：
-- 帳號綁定後 uuid 會變成可連結真人身分的穩定識別字，client 端一律不给。

-- ─── RPC: my_notes（我投放的便條，新→舊）──────────────────────────────────
create or replace function public.my_notes(
  p_limit             integer     default 50,
  p_before_created_at timestamptz default null,
  p_before_id         uuid        default null
) returns table (
  id           uuid,
  content      text,
  lat          double precision,
  lng          double precision,
  created_at   timestamptz,
  picked_up_at timestamptz
)
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if (p_before_created_at is null) <> (p_before_id is null) then
    raise exception 'invalid_cursor';
  end if;

  return query
    select n.id, n.content, n.lat, n.lng, n.created_at, n.picked_up_at
    from public.notes n
    where n.author_id = v_uid
      and (p_before_created_at is null
           or (n.created_at, n.id) < (p_before_created_at, p_before_id))
    order by n.created_at desc, n.id desc
    limit least(greatest(coalesce(p_limit, 50), 1), 100);
end;
$$;

-- ─── RPC: my_collection（我撿到的便條，撿起時間新→舊）─────────────────────
create or replace function public.my_collection(
  p_limit            integer     default 50,
  p_before_picked_at timestamptz default null,
  p_before_id        uuid        default null
) returns table (
  id           uuid,
  content      text,
  lat          double precision,
  lng          double precision,
  created_at   timestamptz,
  picked_up_at timestamptz
)
language plpgsql stable security invoker set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if (p_before_picked_at is null) <> (p_before_id is null) then
    raise exception 'invalid_cursor';
  end if;

  return query
    select n.id, n.content, n.lat, n.lng, n.created_at, n.picked_up_at
    from public.notes n
    where n.picked_up_by = v_uid
      and (p_before_picked_at is null
           or (n.picked_up_at, n.id) < (p_before_picked_at, p_before_id))
    order by n.picked_up_at desc, n.id desc
    limit least(greatest(coalesce(p_limit, 50), 1), 100);
end;
$$;

-- ─── Grants（Supabase 預設 auto-grant 給 anon，照慣例逐支收回）─────────────
revoke execute on function public.my_notes(integer, timestamptz, uuid) from public, anon;
revoke execute on function public.my_collection(integer, timestamptz, uuid) from public, anon;
grant execute on function public.my_notes(integer, timestamptz, uuid) to authenticated;
grant execute on function public.my_collection(integer, timestamptz, uuid) to authenticated;

-- 註：曾實測欄位級 grant（隱藏 uuid 欄位）——INVOKER 函式的 WHERE 引用
-- author_id/picked_up_by 同樣受欄位權限管制，會 permission denied，故維持全表
-- SELECT grant；直讀路徑的揭露寫在 docs/api/notes.md「契約外路徑」段。
