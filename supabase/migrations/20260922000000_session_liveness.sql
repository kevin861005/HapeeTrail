-- T28 票 03（ADR-0013）：服務每個請求驗 session 存活——登出（GoTrue 刪 auth.sessions 列）
-- 的瞬間，該 session 的 access token 立即 401。
--
-- 為什麼不是一句 `grant select on auth.sessions to hapeetrail_api`：auth schema 的擁有者是
-- supabase_admin，postgres 對它的 USAGE 沒有 grant option。那句 grant 只印
-- `WARNING: no privileges were granted`，ON_ERROR_STOP 攔不到，migration 照綠，
-- 上線後每個請求才 `permission denied for schema auth`（票 02 研究 Q4，本機探針實測）。
--
-- 所以走 view：擁有者是 postgres（持有 auth.sessions 的 SELECT，來自 GoTrue 自己的 migration），
-- view 以擁有者權限讀底層表，服務只拿得到這個 view。
--   * 只露 id——session 檢查只問「這一列在不在」，其餘欄位（user_agent、ip…）服務用不到。
--   * 自建 schema，不放 public：public 有 Supabase 的 default privileges（新物件自動 grant 給
--     anon／authenticated，T24），而且是 PostgREST 對外暴露的 schema。新 schema 兩者皆無。
--   * 不在 auth 建任何東西：Supabase 2025-04 起禁止在 auth 建物件（Discussion #34270）。
--
-- 代價（ADR-0013 記載）：view 在 pg_depend 上依賴 auth.sessions.id，GoTrue 哪天改這欄的型別
-- 或重建整張表，它的 migration 會被擋。notes.author_id → auth.users 的 FK 本來就是同一類耦合。
--
-- 簡單 view 會被 inline：`where id = ?` 走 sessions_pkey 的 Index Only Scan（EXPLAIN 實測）。
create schema hapeetrail_private;

create view hapeetrail_private.auth_sessions as
  select id from auth.sessions;

grant usage on schema hapeetrail_private to hapeetrail_api;
grant select on hapeetrail_private.auth_sessions to hapeetrail_api;

comment on schema hapeetrail_private is
  'HapeeTrail 服務專用、不對 client 暴露的 schema。只授權給 hapeetrail_api。';
comment on view hapeetrail_private.auth_sessions is
  'session 存活檢查（ADR-0013）：GoTrue 登出即刪 auth.sessions 列，服務以 token 的 session_id 查這裡。';
