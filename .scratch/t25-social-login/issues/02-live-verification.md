# 票 02 — 待實測項與驗收（spec 驗收 1–4）

依賴：票 01 完成。**需要真的 Google／Apple ID token**——Apple 的只能從真裝置／模擬器的
AuthenticationServices 拿，所以本票的升級實測要等夥伴串好 sign-in UI 後配合執行
（或 Kevin 用 Xcode 起最小測試 App）。能先做的（第 4 項）不用等。

## 實測項

- [ ] **1. 升級保 UUID**：匿名登入 → 記下 JWT `sub` → `linkIdentityWithIdToken`（Google、Apple 各一輪）
      → 成功後比對新 JWT `sub` 逐字相同、`is_anonymous` 翻 false → 用新 token 打 Java 服務任一業務 API 200
- [ ] **2. Manual Linking 開關兩態**：開關各一態打一次原生升級，釘死「原生路徑到底要不要開」
      （研究檔的文件×原始碼矛盾點）。結論寫回本票＋研究檔補註
- [ ] **3. 身分衝突**：用已綁過的 Google 身分對第二個匿名帳號升級 → 應得 422 `identity_already_exists`；
      抄錄實際 wire 回應（狀態碼＋body）給票 03 的交付包
- [ ] **4. 自助註冊確實被擋**（不需 ID token，票 01 完成即可做）：
      `curl POST /auth/v1/signup`（email+password）→ 應被拒；匿名登入對照組仍 200

## 注意

- hosted 匿名登入 30 次/時/IP 的配額（HANDOFF 雷區）——本票會多吃幾次匿名登入，
  同一小時別再跑煙霧＋newman
- 測試產生的匿名帳號與綁定帳號留在 hosted `auth.users`，無害；數量記回本票

## 驗收

四項全過、證據（指令＋輸出）附在本票；第 2 項的結論同步補進研究檔
