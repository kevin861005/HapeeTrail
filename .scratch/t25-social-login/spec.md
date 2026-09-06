# T25 Spec — Google + Apple 登入（匿名升級綁定）

日期：2026-09-06。事實依據：`docs/research/2026-09-06-supabase-anon-upgrade-native-oauth.md`（下稱「研究檔」）。

## 產品規則（2026-09-06 裁決）

1. 訪客＝匿名登入起步，現況不變；全功能可用。
2. 升級＝使用者主動綁 Google 或 Apple：**同一 UUID 延續**，便條與足跡不斷。
3. 登入方式僅 Google／Apple 兩種；**不提供 email/password 自助註冊**。
4. 有 Google 登入就必須同時有 Sign in with Apple（App Store 審查規定）。
5. 棄置匿名帳號 MVP 不清理 → **T27**。

## 關鍵技術事實（細節與原始碼佐證見研究檔）

- 升級唯一正確 API：`linkIdentityWithIdToken`（supabase-swift ≥ 2.32.0）。
  `signInWithIdToken` 完全無視當前匿名 session，只用於「登入既有帳號」情境（重灌後找回帳號）。
- 升級前後 `sub`／`iss`／ES256 全不變，只有 `is_anonymous` claim 翻 false ⇒ **Java 服務零改動**。
- 關閉自助註冊的正確做法：**只停用 Email provider**。
  「Allow new users to sign up」（`DisableSignup`）**不能關**——原始碼確認它連匿名登入與
  Google/Apple 首次直接登入一起擋。
- 衝突錯誤兩種：422 `identity_already_exists`（身分已綁別人，**無自動合併**）、
  400 `email_exists`（email 被佔用）。iOS 端要有對應 UX 分支。
- 文件與原始碼矛盾（本 spec 的待實測項）：「Enable Manual Linking」beta 開關從原始碼看
  只掛在 web 版連結與 unlink 路由，`POST /token`（原生升級）沒掛 ⇒ 理論上不用開，**必須實測釘死**。

## 範圍

**In**：Supabase dashboard 設定、Apple Developer／Google Cloud Console 設定、
待實測項的落地驗證、iOS 夥伴交付包、`docs/api/` 一句澄清、ADR-0012。

**Out**：Java 服務程式碼（零改動，驗證即可）、匿名帳號清理（T27）、web 版登入、
帳號資料合併機制（官方沒有，我們也不自建）、CAPTCHA/Turnstile（現有 30 次/時/IP rate limit 先擋著，
要加另立票）。

## 驗收標準

1. 實測：匿名 session → `linkIdentityWithIdToken`（Google 與 Apple 各一次）→ 成功，
   升級前後 JWT 的 `sub` 逐字相同；升級後 token 打 Java 服務業務 API 200。
2. 實測：「Enable Manual Linking」開／關兩態下原生升級的實際行為，結論落檔。
3. 實測：同一身分綁第二個帳號 → 重現 422 `identity_already_exists`，記錄實際 wire 回應。
4. `curl` 直打 `/auth/v1/signup`（email/password）確認被擋；匿名登入確認仍可用。
5. 夥伴收到交付包；`docs/api/` 澄清句上線；ADR-0012 寫入。

## 施工票

| 票 | 內容 | 依賴 |
|---|---|---|
| [01](issues/01-supabase-config.md) | Supabase／Apple／Google 三邊設定 | Kevin 的 Apple Developer 與 Google 帳號、夥伴的 Bundle ID |
| [02](issues/02-live-verification.md) | 待實測項與驗收 1–4 | 票 01；真 ID token（需夥伴端或測試 App 配合） |
| [03](issues/03-partner-handoff-and-docs.md) | 夥伴交付包、docs/api 澄清、ADR-0012 | 票 02 結論 |
