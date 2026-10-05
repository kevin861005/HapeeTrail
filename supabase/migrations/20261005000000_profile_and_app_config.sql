-- T30／T31／T32：基本資料的來源 view ＋ App 設定兩張表。
-- 全部放 hapeetrail_private（ADR-0013 開的 schema：沒有 Supabase 的 default privileges、
-- PostgREST 不暴露、client 角色連 USAGE 都沒有），服務只拿 SELECT——寫入走 SQL editor
-- （postgres 身分），改一個開關或版本門檻不必重新部署服務。
--
-- T24 提醒：這三個物件刻意不放 public，就是為了不吃 default privileges；SmokeTest 的
-- clientRolesOwnNothingInPublic 與 serviceConnectsAsTheLeastPrivilegedRole 會逐項證明。

-- ── T31 更新檢查 ───────────────────────────────────────────────────────────────
-- 一個平台一列。版本字串只允許「數字.數字…」（CHECK 擋住在 SQL editor 手滑打進去的
-- `v1.2`／`1.2-beta`），比較在 Java 逐段按數值比（1.10 > 1.9）。
create table hapeetrail_private.app_versions (
  platform text primary key,
  minimum_version text not null check (minimum_version ~ '^\d{1,9}(\.\d{1,9}){0,3}$'),
  latest_version text not null check (latest_version ~ '^\d{1,9}(\.\d{1,9}){0,3}$')
);
-- 一上線就要有 iOS 那一列：服務查不到列是 500（設定壞了），不是「不用更新」。
insert into hapeetrail_private.app_versions values ('ios', '1.0.0', '1.0.0');

-- ── T32 功能開關 ───────────────────────────────────────────────────────────────
-- 表裡沒有的 key 一律視為 false（服務端處理，不在這裡補列）。key 的字元集與服務的輸入檢查同一條
-- regex，兩邊不會各自漂移成「存得進去卻查不到」。
create table hapeetrail_private.feature_flags (
  key text primary key check (key ~ '^[A-Za-z0-9_.-]{1,64}$'),
  enabled boolean not null
);

grant select on hapeetrail_private.app_versions, hapeetrail_private.feature_flags to hapeetrail_api;

comment on table hapeetrail_private.app_versions is
  'T31 更新檢查：minimum_version 以下強制更新、latest_version 以下提示有新版。SQL editor 直接改，不必重部署。';
comment on table hapeetrail_private.feature_flags is
  'T32 功能總開關：POST /v1/app/flags 逐 key 回 enabled；不在表裡的 key 回 false。';

-- ── T30 基本資料 ───────────────────────────────────────────────────────────────
-- 同 ADR-0013 的手法：服務碰不到 auth schema，由 postgres 擁有的 view 代讀。
-- 暱稱／頭像不在 auth.users：GoTrue 綁定身分（linkIdentityToUser）只把 provider 給的資料寫進
-- auth.identities.identity_data（Google：full_name／name、avatar_url／picture；Apple 的 id_token
-- 不帶姓名、也沒有頭像），**不回寫 raw_user_meta_data**（2026-10-05 查 internal/api/identity.go）。
-- 登入方式＝最早建立的那個 identity 的 provider——GoTrue 的 app_metadata.provider 就是這樣算的
-- （models.FindProvidersByUser：order by created_at asc 取第一個）；沒有 identity ＝ 匿名（訪客）。
-- 不讀 raw_app_meta_data->>'provider'：匿名使用者根本沒有這個鍵（ToUserModel 對 anonymous 跳過）。
-- 只投影服務真的用到的純量（ADR-0013「只露用到的欄位」）：raw_user_meta_data／identity_data 整欄
-- 帶著 email、provider sub 等 PII，整欄露給服務角色等於第一次讓它讀得到全站 email（複核 A1）。
-- `->>` 只是投影不是業務函式；「哪個鍵優先」的 coalesce 仍在 Java 那句 SQL（ADR-0011）。
-- 代價同 ADR-0013：view 在 pg_depend 上多掛了 auth.users 三欄與 auth.identities 三欄。
create view hapeetrail_private.auth_users as
  select u.id, u.last_sign_in_at,
         u.raw_user_meta_data->>'full_name'  as meta_full_name,
         u.raw_user_meta_data->>'name'       as meta_name,
         u.raw_user_meta_data->>'avatar_url' as meta_avatar_url,
         u.raw_user_meta_data->>'picture'    as meta_picture,
         i.provider,
         i.identity_data->>'full_name'  as identity_full_name,
         i.identity_data->>'name'       as identity_name,
         i.identity_data->>'avatar_url' as identity_avatar_url,
         i.identity_data->>'picture'    as identity_picture
    from auth.users u
    left join lateral (
      select provider, identity_data from auth.identities
       where user_id = u.id order by created_at asc limit 1
    ) i on true;

grant select on hapeetrail_private.auth_users to hapeetrail_api;

comment on view hapeetrail_private.auth_users is
  'T30 基本資料（GET /v1/me）：使用者列＋最早綁定的 identity，只投影暱稱／頭像用到的鍵。provider 為 null ＝ 匿名。';
