# 03 — Session 存活驗證（登出立即失效）

**What to build:** 旅人按下登出（iOS 直打 Supabase `signOut()`，session 列消失）的瞬間，
該帳號所有已簽發 access token 打任何業務 API 一律 401——與現行 `not_authenticated`
**逐字相同**的凍結形狀。機制：JWT 簽章與 claims 驗過後，以 `session_id` claim 對
`auth.sessions` 做 PK lookup，查無此列即 401。適用所有需認證端點。

**Blocked by:** 01（分包）、02（research——claim 格式與 signOut 行為以 findings 為準）.

**Status:** done ✅ 2026-09-22

- [x] migration：`hapeetrail_api` 取得 `auth.sessions` 的最小必要讀取權（僅此，不多）
      ✅ **Kevin 2026-09-22 裁決方案 A（view）**，取代 spec 的單一 GRANT（票 02 研究 Q4：靜默失敗）。
      `supabase/migrations/20260922000000_session_liveness.sql`：自建 schema `hapeetrail_private`、
      view `auth_sessions`（只露 `id`）、服務只拿 schema USAGE＋view SELECT。
      `SmokeTest.serviceConnectsAsTheLeastPrivilegedRole` 釘 SELECT=true、insert/update/delete/truncate=false；
      `clientRolesOwnNothingInPublic` 釘 anon／authenticated 無 USAGE。**突變驗證**：migration 追加
      `grant delete …`＋`grant usage … to anon` → SmokeTest 恰 2 紅，還原後綠。
      EXPLAIN（以 `hapeetrail_api` 身分，本機探針）：`Index Only Scan using sessions_pkey on sessions`
- [x] 正面組：user＋session 齊備的 token → 200
      ✅ `AuthTest.validTokenGetsAnEmptyPage` 改用 `signIn`；其餘 7 個測試類的旅人全走 `signIn`（全綠即正面組）
- [x] `badTokensAre401` 表新增列：①簽章有效但無對應 session 列 ②session 列被刪（模擬登出）——401 逐字斷言
      ✅ 列「查無此 session」「已登出」（`delete from auth.sessions where user_id = ?`＝GoTrue global logout 那句）
- [x] 缺 `session_id` claim 或格式不符 → 401（fail-closed，進表）
      ✅ 列「無 session_id」「session_id 非 UUID」「session_id 是 nil UUID」（GoTrue 對 nil 放行，本服務不抄）
- [x] 既有測試遷移：「建 user＋session」fixture helper 一處收掉；「不存在的使用者拿有效 token 得 200」的舊案例翻成 401 表列；`./mvnw test` 全綠且綠的理由正確
      ✅ `SupabaseDbTest.openSession`／`signIn`；舊 200 案例翻成列「使用者不存在（帳號已刪除）」。
      **綠的理由**：表的每一列都帶同一個真在線的 session，只有一個毛病——否則 session 檢查會替
      sub／iss／exp 那些列把關，拿掉任何驗證都不會轉紅。
      `tokenForADeletedUserIs401` 併入上述表列；它原本覆蓋的 `ApiErrors` 23503→401（過了 session 檢查、
      寫入前被註銷的窗口）搬到 `ErrorEnvelopeTest.foreignKeyViolationIsTheSame401`，以服務連線**真的**
      違反 FK（Spec 複核點名後補回真 DB）；**突變驗證**：23503 常數改掉 → 恰 1 紅。
      測試底座：Testcontainers 映像沒有 `auth.sessions`（GoTrue 建的），`SupabaseDbTest` 以
      `supabase_auth_admin` 身分補等價表＋GoTrue 那條 `grant select … with grant option`
      （ACL 探針：`postgres=ar*wdDxtm/supabase_auth_admin`，與研究 Q4.1 的 hosted 形狀相同）。
      `./mvnw test` → **Tests run: 200, Failures: 0, Errors: 0／BUILD SUCCESS**（票 01 後為 193）
- [x] 隱私：新增查詢與 401 路徑不落任何 token 內容或 claim 值進日誌
      ✅ validator 本身不記 log；失敗描述是固定字串 `session is not alive`；SQL 只以 `?` 綁參數
      （JdbcTemplate 參數值只在 TRACE）；預設 log level INFO
- [x] **ADR-0013 落檔**：「驗 JWT」擴充為「驗 JWT＋session 存活」——立即失效 vs 無狀態的取捨、每請求一次 PK lookup 的代價、刻意不做快取的理由
      ✅ `docs/adr/0013-session-liveness.md`（另含：為什麼 view、不是保證的邊界、下列兩項裁決）
- [x] TDD red→green：每條新斷言先紅後綠
      ✅ 6 條 session 列實作前恰 6 紅（`expected: 401 but was: 200`，rows 12–17），實作後 19/19 綠；
      DB 故障 500 那條實作前紅（`expected: 500 but was: 401`）；SmokeTest 兩條權限斷言以突變證紅

## 施工結果（兩軸 `/code-review` 後，2026-09-22）

程式：`api/…/auth/LiveSessionValidator.java`（`OAuth2TokenValidator<Jwt>` bean，Boot 自動併進 decoder；
失敗與簽章不符同一條路 ⇒ 401 逐字同形）。

**複核後 Kevin 裁決的三項**：
1. **驗證路徑上的 DB 故障 → 500（本票內修）**。探針實測：session 查詢拋例外會逃出 filter chain，
   error dispatch 無認證 ⇒ 原本回 **401**，iOS 會把伺服器故障當 session 問題刷新（匿名旅人登出＝永久失去帳號）。
   修法：`SecurityConfig` 的 entry point 看到 error dispatch 帶著例外（`RequestDispatcher.ERROR_EXCEPTION`）
   就回 500 problem+json，形狀同 `ApiErrors` catch-all。`ErrorEnvelopeTest.aFailingSessionCheckIs500NotA401`
   以撤掉 view 授權模擬（正是研究 Q4 那種故障）。
2. **FK 覆蓋補回真 DB**（見上）。
3. **validator 鏈不短路 ⇒ 已簽但過期／aud／iss 不符的 token 也多查一次**：接受，註解與 ADR 寫明。

**複核的其他發現與處置**：
- 「測試底座的 `postgres` 只有 `r*`、與 hosted 不同」——**駁回**：同映像同語句的探針 ACL 為
  `postgres=ar*wdDxtm/supabase_auth_admin`（auth schema 的 default privileges 給了 arwdDxtm，GoTrue 那條補上 grant option）。
- `docs/api/notes.md` 401 成因未列「session 已終止」、舊「不存在使用者 200」已變 401——spec 排在票 04
  （契約 v4.1.0）。⚠️ **票 03 不可先於票 04 部署**，否則契約落後於實際行為。
- ⚠️ **部署順序**：migration 必須先 `db push` 到 hosted，再部署新服務——view 不在時每個請求都是 500。
- 未處理（判斷題、不在本票）：五個測試類各自的 `Traveler`／`traveler()` 重複（改動前就有）；
  `TestJwt.token` 三個相鄰 String 參數。
