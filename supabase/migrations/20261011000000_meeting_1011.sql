-- T33：2026-10-11 與 WeiWei 會議的兩個資料層決策（更新檢查只回門檻那一項不動 DB，
-- app_versions 表與 CHECK 照舊）。

-- ── 決策 2：功能開關預設一筆 paywall ─────────────────────────────────────────────
-- 付費牆一上線就是開的；要關是 SQL editor 一句 UPDATE。
insert into hapeetrail_private.feature_flags (key, enabled) values ('paywall', true);

-- ── 決策 3：基本資料先不要頭像 ────────────────────────────────────────────────
-- 回應拿掉 avatarUrl，view 也一併拿掉四個頭像欄（ADR-0013「只露用到的欄位」：用不到的
-- 使用者可控字串不經過服務角色）。`create or replace view` 不能刪欄，只能 drop 重建；
-- drop 會把 grant 與 comment 一起丟掉，所以下面重新授權、重新註解。
-- 其餘定義與 20261005000000_profile_and_app_config.sql 相同（登入方式＝最早綁定的 identity）。
drop view hapeetrail_private.auth_users;

create view hapeetrail_private.auth_users as
  select u.id, u.last_sign_in_at,
         u.raw_user_meta_data->>'full_name'  as meta_full_name,
         u.raw_user_meta_data->>'name'       as meta_name,
         i.provider,
         i.identity_data->>'full_name'  as identity_full_name,
         i.identity_data->>'name'       as identity_name
    from auth.users u
    left join lateral (
      select provider, identity_data from auth.identities
       where user_id = u.id order by created_at asc limit 1
    ) i on true;

grant select on hapeetrail_private.auth_users to hapeetrail_api;

comment on view hapeetrail_private.auth_users is
  'T30 基本資料（GET /v1/me）：使用者列＋最早綁定的 identity，只投影暱稱用到的鍵（頭像 2026-10-11 拿掉）。provider 為 null ＝ 匿名。';
