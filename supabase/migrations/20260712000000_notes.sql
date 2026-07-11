-- Phase 1: notes（依位置留便條 / 100m 提示 / 50m 獨佔撿起）
create extension if not exists postgis with schema extensions;

create table public.notes (
  id            uuid primary key default gen_random_uuid(),
  author_id     uuid not null references auth.users (id) on delete cascade,
  content       text not null,
  lat           double precision not null,
  lng           double precision not null,
  location      extensions.geography(point, 4326) not null
                  generated always as
                  (extensions.st_setsrid(extensions.st_makepoint(lng, lat), 4326)::extensions.geography)
                  stored,
  created_at    timestamptz not null default now(),
  picked_up_by  uuid references auth.users (id) on delete set null,
  picked_up_at  timestamptz,

  constraint notes_content_len    check (char_length(content) between 1 and 500),
  constraint notes_lat_range      check (lat between -90 and 90),
  constraint notes_lng_range      check (lng between -180 and 180),
  -- picked_up_by 蘊含 picked_up_at；at 可在 by 被 SET NULL 後留存（撿起者刪號，便條不回地圖）
  constraint notes_pickup_pair    check (picked_up_by is null or picked_up_at is not null),
  constraint notes_no_self_pickup check (picked_up_by is distinct from author_id)
);

-- 熱查詢：附近未撿便條。partial index 讓活躍集永遠小而熱
create index notes_active_location_gix on public.notes using gist (location)
  where picked_up_at is null;
create index notes_author_ix on public.notes (author_id, created_at desc);
create index notes_picker_ix on public.notes (picked_up_by, picked_up_at desc)
  where picked_up_by is not null;

-- ─── RLS + grants ─────────────────────────────────────────────────────────
alter table public.notes enable row level security;

-- 唯一直接讀取面：自己寫的或自己撿的。世界地圖無法從 table 掃出，探索只走 RPC
create policy notes_select_own on public.notes
  for select to authenticated
  using (author_id = (select auth.uid()) or picked_up_by = (select auth.uid()));

revoke all on table public.notes from anon, authenticated;
grant select on table public.notes to authenticated;

-- ─── RPC: drop_note ───────────────────────────────────────────────────────
create or replace function public.drop_note(
  p_content text,
  p_lat     double precision,
  p_lng     double precision
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_uid     uuid := (select auth.uid());
  v_content text := btrim(p_content);
  v_row     public.notes;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  if v_content is null or v_content = '' then raise exception 'content_empty'; end if;
  if char_length(v_content) > 500 then raise exception 'content_too_long'; end if;
  -- ponytail: advisory 上限（併發下可小幅超越），防匿名帳號灑滿地圖；觀察到濫用再調
  if (select count(*) from public.notes n
      where n.author_id = v_uid and n.picked_up_at is null) >= 50 then
    raise exception 'active_note_limit';
  end if;

  insert into public.notes (author_id, content, lat, lng)
  values (v_uid, v_content, p_lat, p_lng)
  returning * into v_row;

  return to_jsonb(v_row) - 'location';
end;
$$;

-- ─── RPC: nearby_notes ────────────────────────────────────────────────────
create or replace function public.nearby_notes(
  p_lat double precision,
  p_lng double precision
) returns table (
  id         uuid,
  lat        double precision,
  lng        double precision,
  distance_m integer,
  pickable   boolean,
  created_at timestamptz
)
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

  return query
    select n.id, n.lat, n.lng,
           round(extensions.st_distance(n.location, v_loc))::int,
           extensions.st_dwithin(n.location, v_loc, 50.0),
           n.created_at
    from public.notes n
    where n.picked_up_at is null
      and n.author_id <> v_uid          -- 自己的 pin 由 my-notes 疊圖
      and extensions.st_dwithin(n.location, v_loc, 100.0)
    order by 4                          -- 位置引用避開 OUT 變數名捕捉
    limit 20;
end;
$$;

-- ─── RPC: pickup_note（原子獨佔）──────────────────────────────────────────
create or replace function public.pickup_note(
  p_note_id uuid,
  p_lat     double precision,
  p_lng     double precision
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_loc extensions.geography;
  v_row public.notes;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  -- ponytail: advisory 撿取頻率上限，防座標掃描清空城市（獨佔＝破壞性，見 ADR-0003）
  if (select count(*) from public.notes n
      where n.picked_up_by = v_uid
        and n.picked_up_at > now() - interval '1 hour') >= 60 then
    raise exception 'pickup_rate_limited';
  end if;
  v_loc := extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography;

  -- 獨佔性的全部保證就是這一個語句：check 與 write 同語句同 row version，無競態窗口
  update public.notes n
     set picked_up_by = v_uid, picked_up_at = now()
   where n.id = p_note_id
     and n.picked_up_at is null
     and n.author_id <> v_uid
     and extensions.st_dwithin(n.location, v_loc, 50.0)
  returning * into v_row;

  if found then return to_jsonb(v_row) - 'location'; end if;

  -- 失敗診斷。此讀取相對 UPDATE 有 race，但只影響回報哪個錯誤碼，不影響獨佔正確性
  select * into v_row from public.notes n where n.id = p_note_id;
  if not found then
    raise exception 'note_not_found';
  elsif v_row.picked_up_by = v_uid then
    return to_jsonb(v_row) - 'location';   -- 冪等重試：已是你的 = 成功
  elsif v_row.picked_up_at is not null then
    raise exception 'note_taken';
  elsif v_row.author_id = v_uid then
    raise exception 'own_note';
  else
    raise exception 'too_far';
  end if;
end;
$$;

-- ─── Function grants（Supabase 預設會 auto-grant 給 anon，逐支收回）────────
revoke execute on function public.drop_note(text, double precision, double precision) from public, anon;
revoke execute on function public.nearby_notes(double precision, double precision) from public, anon;
revoke execute on function public.pickup_note(uuid, double precision, double precision) from public, anon;
grant execute on function public.drop_note(text, double precision, double precision) to authenticated;
grant execute on function public.nearby_notes(double precision, double precision) to authenticated;
grant execute on function public.pickup_note(uuid, double precision, double precision) to authenticated;
