# T28 Spec — 登出立即失效＋註銷帳號 API

日期：2026-09-22。triage：`ready-for-agent`。
共識來源：2026-09-22 grilling（七題全數裁決）；領域詞「登出／註銷」已入 `CONTEXT.md`。

## Problem Statement

旅人目前無法真正離開 HapeeTrail：

1. **登出後憑證仍然活著**——服務無狀態驗 JWT，按下登出只撤了 refresh token，
   已簽發的 access token 在到期前（預設 1 小時）照樣能打所有業務 API。
   裝置遺失或 token 外洩時，使用者沒有任何立即止血的手段。
2. **沒有註銷帳號的路**——想永久刪除帳號與所有便條的旅人做不到這件事；
   而 App Store 審查指南 5.1.1(v) 規定有帳號建立就必須提供 app 內註銷，
   這是 T25（Google/Apple 登入）上架前的硬依賴。

## Solution

1. **登出**：iOS 照舊直打 Supabase `signOut()`（服務不代理 auth 路徑），但服務端
   每個請求在驗完 JWT 簽章後**加驗 session 存活**——GoTrue 的 access token 內含
   `session_id` claim，`signOut()` 會刪除 `auth.sessions` 對應列。session 不在 = 401。
   效果：按下登出的瞬間，該帳號所有已簽發 token 立即失效，**零新端點**。
2. **註銷**：新端點 `DELETE /v1/me`——服務呼叫 GoTrue Admin API 硬刪使用者，
   FK cascade 帶走 sessions、identities、與**該使用者寫下的全部便條**
   （含已被他人撿進收藏的）。立即生效、無反悔期。

## User Stories

1. As a 旅人, I want 按下登出後我的登入狀態立即終止, so that 不會有「登出了但其實還能操作」的模糊地帶
2. As a 裝置遺失的旅人, I want 在別台裝置登入後執行登出（global sign-out）讓舊裝置上的 token 立即作廢, so that 撿到手機的人不能用我的身分留便條
3. As a 旅人, I want 登出後重新登入時我的便條與足跡原封不動, so that 登出是可逆的日常操作而不是危險動作
4. As a 旅人, I want 註銷我的帳號, so that 我可以永久離開這個服務
5. As a 註銷帳號的旅人, I want 我寫過的所有便條（未撿的、已被撿走的、私人的）全部消失, so that 我留在世界上的內容隨帳號一起收回
6. As a 註銷帳號的旅人, I want 註銷立即生效且不可逆, so that 「刪除」就是字面意義的刪除，不是停用
7. As a 註銷帳號的旅人, I want 我撿過的別人便條不因我註銷而消失或回到地圖, so that 原作者的紀錄不被我的離開破壞（既有 schema 立場：`picked_up_by SET NULL`、便條不回地圖）
8. As a 匿名旅人（未綁定 Google/Apple）, I want 同一個註銷功能對我一樣可用, so that 訪客也有隨時抹除自己的權利
9. As a 已升級綁定的旅人, I want 註銷時 Google/Apple 綁定身分（identities）一併刪除, so that 不留任何可回溯的關聯
10. As a 註銷後的旅人, I want 手上殘留的 token 立刻變成 401, so that 不存在「帳號沒了但 token 還能用」的殭屍狀態
11. As a 撿藏者, I want 收藏裡屬於已註銷作者的便條靜默消失（列表少一件，不是壞掉的空殼）, so that 收藏列表永遠是完整可渲染的
12. As an iOS 夥伴, I want 登出後／註銷後的請求收到與現行**逐字相同**的 401 problem+json, so that 我既有的「401 → 走刷新流程」錯誤處理零改動
13. As an iOS 夥伴, I want `DELETE /v1/me` 成功回 204 無 body、重試打到 401, so that 我不用處理「已刪除」的特殊錯誤分支
14. As an iOS 夥伴, I want 契約文件（openapi／notes.md／postman）同步更新並附 curl 範例、明寫登出走 Supabase 而非本服務, so that 我不會來問「登出 API 在哪」
15. As an App Store 審查員, I want app 內提供帳號刪除且真的刪除資料, so that 符合 5.1.1(v)
16. As Kevin（營運者）, I want 註銷經由 GoTrue Admin API 執行而非直改 auth schema, so that Supabase 升級不會弄壞刪除路徑，且 GoTrue audit log 留有刪帳紀錄
17. As Kevin, I want 服務仍以最小權限角色連 DB、service_role key 只以環境變數存在於伺服器, so that 安全姿態不因這個功能退化
18. As Kevin, I want 註銷流程的日誌只有路徑／狀態碼／耗時, so that 隱私守則（不記座標與內容）在刪除路徑上同樣成立
19. As a 下一個 session 的 agent, I want session 存活驗證的取捨記成 ADR-0013, so that 未來的人知道「無狀態服務為什麼每請求查一次 DB」

## Implementation Decisions

- **登出零端點**（grilling Q1–Q2 裁決）：登出是 iOS ↔ Supabase 的事。本功能的服務端改動是
  「驗證路徑加一道 session 存活檢查」，不是登出 API。
- **Session 存活檢查**：JWT 簽章與 claims 驗過之後，以 token 的 `session_id` claim 對
  `auth.sessions` 做一次 PK lookup；查無此列 → 401，形狀與現行 `not_authenticated`
  **逐字相同**（401 的唯一形狀是凍結的契約）。適用**所有**需認證的端點，包括 `DELETE /v1/me` 自己。
  - 連帶收緊：簽章有效但使用者不存在／session 不存在的 token（現況部分端點回 200）一律變 401。
  - `hapeetrail_api` 需要 `SELECT ON auth.sessions` 的授權（一支 migration；auth schema
    不在 T24 的 public default privileges 範圍，無靜默破口疑慮）。
    → **2026-09-22 修正**：單一 GRANT 不可行（票 02 研究 Q4），Kevin 裁決改走私有 schema 的 view
    `hapeetrail_private.auth_sessions`，見 ADR-0013 與票 03。
  - 效能：每請求多一次 PK lookup（sub-ms）；這是「立即失效」的必要代價，記入 ADR-0013。
    不做快取——快取窗＝失效延遲窗，等於推翻需求本身。
- **`DELETE /v1/me`**：無 request body；成功 `204 No Content`；服務以 Spring 內建
  `RestClient`（零新依賴）呼叫 GoTrue Admin API 刪除使用者，`should_soft_delete` 明確傳 false。
  - GoTrue 回 404（使用者已不存在，併發重試的窗口）→ 視為目標狀態已達成，回 204（冪等）。
  - GoTrue 回其他錯誤 → 500 problem+json（走既有 catch-all，不發明新錯誤 token）。
  - 正常時序下的重試根本進不到 controller：session 已隨帳號消失，驗證層直接 401。
- **資料命運＝全刪**（grilling Q3 裁決）：維持既有 schema 的 `author_id ON DELETE CASCADE`
  與 `picked_up_by SET NULL`，**零 schema 改動**。刪 `auth.users` 一列即帶走
  sessions／identities／refresh tokens／該使用者寫的全部便條。
- **執行路徑＝GoTrue Admin API**（grilling Q4 裁決，明確否決直下 SQL）：auth schema 是
  Supabase 的私有實作，用廠商的公開契約管廠商的資料；service_role key（或新制 secret key，
  research 定奪）走環境變數＋fly secrets，比照 `HAPEETRAIL_JWT_ISSUER` 的處理，不進 repo。
- **Admin base URL 是設定值**——本功能唯一的新 seam。正式環境指 Supabase 專案網址；
  測試指 fake GoTrue（見 Testing Decisions）。
- **立即硬刪、無反悔期**（grilling Q5 裁決）：不做軟刪標記、不做排程真刪、不做寬限期殭屍狀態。
- **分包整理先行**（grilling Q8 裁決）：package by feature——root 只留啟動類，
  `config/`（SecurityConfig、JsonConfig、ApiErrors）、`notes/`（Note、NoteService、
  NotesController、Cursor）、`auth/`（session 檢查，新）、`account/`（註銷，新）。
  純機械搬移、獨立一張票排最前，測試檔暫不分包。
- **契約 v4.1.0**（新端點＝minor bump）：openapi／notes.md／postman 三份同步——
  新增 `DELETE /v1/me` 一節（含 curl 範例）、401 語意補「session 已終止」一句、
  「登出走 Supabase `/auth/v1/logout`，本服務無登出端點」澄清句。文件保持語言中立。
- **Research 先行（動手前釘死，比照 T25 研究檔模式）**：
  ① `signOut()` 確實刪 `auth.sessions` 列＋預設 scope（global/local）＋`session_id` claim 格式；
  ② GoTrue Admin delete 端點形狀、認證 header、`should_soft_delete` 參數行為；
  ③ service_role JWT vs 新制 `sb_secret_*` key 該用哪種。
  查證結果若推翻上述任何決策（例如 signOut 不刪列），先回報再動。
- **ADR-0013**：session 存活驗證——「驗 JWT」擴充為「驗 JWT＋session 存活」。
  難回頭（動所有端點的驗證路徑＋每請求一次 DB 依賴）、未來人會問、真取捨
  （立即失效 vs 無狀態），三條件齊。

## Testing Decisions

- **只測外部行為**：真 HTTP 進、真 DB 出，斷言 wire 形狀與資料庫可觀察後果，
  不斷言內部實作。先例：`AuthTest`（`@SpringBootTest(RANDOM_PORT)` ＋ `TestJwt`
  鑄 GoTrue 形狀 token ＋ Testcontainers 從零套全部 migration）。
- **Session 存活**進 `badTokensAre401` 參數表體系：簽章有效但 ①無對應 session 列
  ②session 列已刪（模擬登出）→ 401 逐字斷言。正面組：有 session 列 → 200。
- **Fake GoTrue**（唯一新測試設施）：JDK 內建 `com.sun.net.httpserver`，收到 admin delete
  就對測試 DB 真下 `delete from auth.users where id = ?`——FK cascade 真的跑，
  整條 tracer bullet 在最高 seam 驗到底：`DELETE /v1/me` → 204 → 舊 token 401 →
  作者便條從 nearby／撿藏者收藏消失 → 撿過的別人便條原封不動。
  同時斷言送出的 admin 請求形狀（路徑含正確 user id、認證 header、soft_delete=false）。
- **冪等窗口**：fake GoTrue 回 404 → 服務回 204。
- **既有測試遷移**：鑄 token 處配一個「建 user＋session」的 fixture helper（一處收掉）；
  `AuthTest.validTokenGetsAnEmptyPage` 的「隨機 UUID 也 200」翻成 401 表的一列。
  遷移完成的定義：`./mvnw test` 全綠且綠的理由正確（不是靠放寬斷言）。
- **hosted-smoke.sh 延伸**：對真 hosted 走一次完整註銷（匿名註冊 → 留便條 → 註銷 →
  舊 token 401 ＋ 便條消失）——這同時是「signOut／admin delete 在真 GoTrue 上行為如 research
  所述」的持續驗證。注意匿名註冊 30 次/時/IP 額度預算。
- **驗收不自驗**：實作完成後派獨立 subagent 複核（只給本 spec 與 ADR），
  安全視角必查：session 檢查可否繞過（例如偽 `session_id` claim 形狀）、
  `DELETE /v1/me` 可否刪到別人、admin key 是否可能進日誌。

## Out of Scope

- **Apple token 撤銷**（App Store 對 Sign in with Apple 註銷的額外要求）→ **T29**，
  blocked by T25；日後在註銷請求加選填欄位即可（非破壞性），本契約不預留。
- 登出端點、denylist、token 黑名單——session 存活檢查已涵蓋需求。
- 反悔期／軟刪除／帳號停用。
- 便條匿名化保留（裁決為全刪）。
- 棄置匿名帳號的**批次**清理（T27；本功能是使用者主動單刪，路徑不同）。
- 資料匯出（GDPR portability 類需求，未有此需求）。
- newman collection 的每輪帳號清理（見 Further Notes，另行決定）。

## Further Notes

- **送審 checklist**：T25（登入）＋ T28（本功能）＋ T29（Apple 撤銷）三者全關才能送 App Store。
- **T27 連動**：本功能上線後，newman／煙霧測試每輪製造的匿名帳號可在收尾步驟自刪，
  順手止血測試帳號累積——是否改 collection 另行決定，不在本票範圍。
- **T25 平行**：本功能與 T25 無程式碼衝突（T25 後端零改動）；唯 hosted 實測共用
  匿名註冊額度，同一小時內排程注意。
- 分包搬移那張票完成後，`docs/tasks/archive/java-rewrite/` 內文件提到的類別路徑不回改
  （施工紀錄是歷史文件）。

## 施工票

| 票 | 內容 | Blocked by |
|---|---|---|
| [01](issues/01-package-reorg.md) | 分包整理（prefactor） | 無 |
| [02](issues/02-research-pin-facts.md) | Research 釘死三事實 | 無（與 01 平行） |
| [03](issues/03-session-liveness.md) | Session 存活驗證＋ADR-0013 | 01、02 |
| [04](issues/04-delete-me-and-contract.md) | `DELETE /v1/me` ＋契約 v4.1.0 | 03 |
| [05](issues/05-hosted-verify-and-closeout.md) | Hosted 實證＋獨立複核收尾 | 04 |
