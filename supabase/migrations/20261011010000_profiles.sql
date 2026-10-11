-- T34：本人自訂暱稱（PATCH /v1/me）。一人至多一列；沒有列 ＝ 沒自訂，GET /v1/me 回到
-- 登入提供者給的名字（訪客為 null）。
--
-- 放 hapeetrail_private（ADR-0013 的 schema）：沒有 Supabase 的 default privileges、PostgREST
-- 不暴露、client 角色與 service_role 連 USAGE 都沒有——SmokeTest 逐項證明。
--
-- FK 指向 auth.users、on delete cascade：註銷（T28）硬刪使用者列時暱稱跟著走。服務角色沒有
-- auth schema 權限，但 FK 檢查以被參照表擁有者的身分跑，與 public.notes 相同；寫入時使用者
-- 已被註銷 ⇒ 23503 ⇒ ApiErrors.identityGone 轉 401。
--
-- CHECK 只是最後防線（SQL editor 手滑、或哪天服務的驗證被改壞）：業務規則——trim 字元集、
-- 20 個 code point 上限——在 Java（ADR-0011）。Postgres 的 length() 數的是字元（UTF-8 下即
-- code point），與服務的計數單位相同；btrim 只剝 ASCII 空白，服務 trim 的字元集是它的超集，
-- 所以服務寫得進的值這條 CHECK 必過。
create table hapeetrail_private.profiles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  nickname text not null check (nickname = btrim(nickname) and length(nickname) between 1 and 20),
  updated_at timestamptz not null default now()
);

-- delete 是為了 null 清除；truncate（整表清空）不給。
grant select, insert, update, delete on hapeetrail_private.profiles to hapeetrail_api;

comment on table hapeetrail_private.profiles is
  'T34 本人自訂暱稱（PATCH /v1/me）：優先於登入提供者給的名字；null 清除＝刪列。業務規則在服務端。';
