# 04 — 第一顆子彈：JWT 驗證 ＋ `hapeetrail_api` 準備 migration ＋ `GET /v1/me/notes` 回空列表

**What to build:** 一個剛匿名登入的旅人拿 GoTrue 的 token 打 `GET /v1/me/notes`，得到 `{"items":[],"nextCursor":null}`——請求穿過 Spring Security 的 JWT 驗證、以 `hapeetrail_api` 角色下 SQL、過 RLS policy、回 v4 envelope。同時五種壞 token 全部 401。這是第一條穿透所有層的路徑，之後每支端點都是在它上面加規則。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] **先查證**專案 JWT 簽章方式：若是 legacy HS256 共享密鑰，在 dashboard 切換到非對稱簽章金鑰並記錄；服務只用 JWKS，共享密鑰永不進 Spring
- [ ] 新增 Supabase migration「準備」：建登入角色 `hapeetrail_api`（密碼不寫在 migration，以一次性手動 SQL 設定，值只在部署平台 secrets）；`public`、`extensions` schema USAGE；`public.notes` 的 SELECT／INSERT／UPDATE（無 DELETE）；不動 `auth.users` 權限
- [ ] 同一支 migration 新增只給 `hapeetrail_api` 的全列 permissive policy（讀寫皆放行）；RLS 維持啟用、`notes_select_own` 保留休眠
- [ ] migration 在 Testcontainers 套用成功，且與 RPC 版並存無害（既有 `notes.test.sql` 在本機 `supabase db reset` 後仍 `ALL TESTS PASSED`）
- [ ] 服務以 `hapeetrail_api` 連線（Testcontainers 內同樣建這個角色）；HikariCP 池大小設個位數
- [ ] JWT：驗簽章、過期、`aud` 必須為 `authenticated`、必須有 `sub`；`sub` 即使用者身分；`is_anonymous` 不影響任何行為
- [ ] 測試 profile 以本機 RSA 公鑰取代 JWKS；測試工具能以私鑰鑄 token 指定 `sub`／`aud`／過期
- [ ] 五種變形各一條斷言 **401 ＋ problem+json `code: not_authenticated`**：無 header、簽章不符、過期、缺 `sub`、`aud` 不符
- [ ] 合法 token → `GET /v1/me/notes` 200 `{"items":[],"nextCursor":null}`（items 是空陣列不是 null）
- [ ] `/actuator/health` 不需 token；其餘路徑無 token 一律 401
- [ ] 全程紅→綠：先寫 401 與空列表的測試看它們紅
