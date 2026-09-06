# 票 03 — 夥伴交付包、docs/api 澄清、ADR-0012

依賴：票 02 的實測結論（交付包裡的錯誤對照與 Manual Linking 結論要用實測值，不用推論值）。

## 夥伴交付包（訊息或文件，非 docs/api/）

以研究檔「給 iOS 夥伴的具體 checklist」為底，重點：

- [ ] supabase-swift 版本需求 **≥ 2.32.0**
- [ ] App 啟動 flow：永遠先確保有 session（沒有就 `signInAnonymously`）
- [ ] **升級只能用 `linkIdentityWithIdToken`，絕不能用 `signInWithIdToken`**（後者造成資料孤兒）；
      「我已有帳號」（重灌找回）情境才用 `signInWithIdToken`
- [ ] Apple：自產 raw nonce → SHA256 給 `ASAuthorizationAppleIDRequest`、raw nonce 給 Supabase；
      **不要照抄 supabase-swift 官方範例（它沒做 nonce）**；full name 只有第一次拿得到，當場存
- [ ] Google：GoogleSignIn-iOS 拿 idToken/accessToken 後同一呼叫方式
- [ ] 錯誤 UX：422 `identity_already_exists`（提示改走登入）、400 `email_exists`（票 02 實測 wire 為準）
- [ ] 升級成功後存回新 Session，之後的業務 API 都用新 token

## docs/api/ 澄清（語言中立，一句話等級）

- [ ] 在 auth 相關段落補：「JWT 的 `sub`（使用者識別）在匿名升級綁定前後保持不變」——
      只講與 Java 服務介面相關的保證，不放 SDK 用法（CLAUDE.md 分工原則）

## ADR-0012 — 登入策略

- [ ] `docs/adr/0012-social-login-strategy.md`：匿名訪客＋Google/Apple 升級綁定、
      不開放 email 註冊（落點＝停用 Email provider；`DisableSignup` 必須保持關閉狀態的理由）、
      無帳號合併機制的取捨、T27 延後清理的裁決

## 收工

- [ ] TASKS.md：T25 打勾附證據；本資料夾搬 `docs/tasks/archive/t25-social-login/`
