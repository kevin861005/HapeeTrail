-- T11-04：便條樣式代號（color / style）。
-- 後端只儲存代號、不理解其語意——色票與卡片樣式的對照表在裝置端，
-- 因此新增顏色或樣式完全不需要後端 migration 或發版。代價是後端無法驗證代號是否有意義，
-- 這是刻意的取捨：後端維護一份合法值清單＝養第二份對照表，一定會跟裝置端漂移。
--
-- 兩個欄位刻意各自獨立、不打包成單一數字（如 12 ＝ 色1形2）：打包會讓
-- 「新增第三個樣式軸」與「某軸超過 9 項」雙雙變成破壞性變更。

-- 代號從 1 起算，預設值指向對照表中一個具體項目（1 ＝ 現行黃色），
-- 不是一個「代表預設」的抽象槽。既有列以此預設回填。
alter table public.notes
  add column color smallint not null default 1,
  add column style smallint not null default 1,
  -- 型別與範圍粗檢到此為止：smallint 管上界，這裡管下界。超出裝置端對照表的代號一律照收
  add constraint notes_color_range check (color >= 1),
  add constraint notes_style_range check (style >= 1);

comment on column public.notes.color is
  '裝置端色票對照表的項目代號。永久 ID，不是清單位置——既有代號的意義永久凍結。';
comment on column public.notes.style is
  '裝置端卡片樣式對照表的項目代號。永久 ID，意義永久凍結（同 color）。';

-- ─── 便條與探索提示的形狀：兩處各加兩個鍵 ────────────────────────────────────
create or replace function public.as_note_wire(n public.notes) returns jsonb
language sql stable set search_path = ''
as $$
  select jsonb_build_object(
    'id',         n.id,
    'content',    n.content,
    'color',      n.color,
    'style',      n.style,
    'coordinate', jsonb_build_object('latitude', n.lat, 'longitude', n.lng),
    'createdAt',  public.as_wire_ts(n.created_at),
    'pickedUpAt', public.as_wire_ts(n.picked_up_at)
  )
$$;

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

  -- 內層挑出 ≤20 筆最近的，外層才組 JSON。內層多帶了兩個代號欄位，where 與排序語意不變
  -- （原本的 `order by 4` 位置引用已隨欄位數改變失準，改成引用別名；
  --  EXPLAIN 複驗仍走 notes_active_location_gix，見 ticket 04）
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
             round(extensions.st_distance(n.location, v_loc))::int as distance_m,
             extensions.st_dwithin(n.location, v_loc, 50.0)        as pickable,
             n.created_at
      from public.notes n
      where n.picked_up_at is null
        and n.author_id <> v_uid          -- 自己的 pin 由 my-notes 疊圖
        and extensions.st_dwithin(n.location, v_loc, 100.0)
      order by distance_m                 -- 取最近的 20 筆（外層 jsonb_agg 另有自己的排序）
      limit 20
    ) h
  );
end;
$$;

-- ─── drop_note（新增兩個可省略參數 ⇒ 簽名改變，先 drop）──────────────────────
drop function public.drop_note(text, double precision, double precision);

create function public.drop_note(
  p_content text,
  p_lat     double precision,
  p_lng     double precision,
  p_color   integer default null,           -- 省略 ＝ 由伺服器補預設
  p_style   integer default null
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_uid     uuid := (select auth.uid());
  v_content text := btrim(p_content);
  v_color   integer;                        -- 刻意不是 smallint：超上界要走我們的 token，
  v_style   integer;                        -- 不是 plpgsql 賦值時的 "smallint out of range"
  v_row     public.notes;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  if p_lat is null or p_lng is null
     or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_coordinates';
  end if;
  if v_content is null or v_content = '' then raise exception 'content_empty'; end if;
  if char_length(v_content) > 500 then raise exception 'content_too_long'; end if;
  -- 只做型別與範圍粗檢，不驗證語意：超出裝置端對照表範圍的代號會被接受並原樣儲存
  v_color := coalesce(p_color, 1);
  v_style := coalesce(p_style, 1);
  if v_color not between 1 and 32767 or v_style not between 1 and 32767 then
    raise exception 'invalid_style_code';
  end if;
  -- ponytail: advisory 上限（併發下可小幅超越），防匿名帳號灑滿地圖；觀察到濫用再調
  if (select count(*) from public.notes n
      where n.author_id = v_uid and n.picked_up_at is null) >= 50 then
    raise exception 'active_note_limit';
  end if;

  insert into public.notes (author_id, content, lat, lng, color, style)
  values (v_uid, v_content, p_lat, p_lng, v_color, v_style)
  returning * into v_row;

  return public.as_note_wire(v_row);
end;
$$;

-- ─── Grants（drop 後權限一併消失，照既有慣例逐支收回再授予）──────────────────
revoke execute on function
  public.drop_note(text, double precision, double precision, integer, integer) from public, anon;
grant execute on function
  public.drop_note(text, double precision, double precision, integer, integer) to authenticated;
