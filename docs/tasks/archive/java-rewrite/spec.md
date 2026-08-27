# Spec：後端全換 Java／Spring Boot（T19）

Status: ready-for-agent
Ticket: T19
日期：2026-08-25
決策背景：`docs/adr/0011-backend-java-rewrite.md`（十條結論與遷移訊號）；產品名依 `CONTEXT.md` 為 **HapeeTrail**

---

## Problem Statement

HapeeTrail 的後端目前是五支 Postgres 函式（RPC）加 RLS／SECURITY DEFINER 的權限設計。它通過了
獨立安全審查、有 848 行測試守著，**但擁有者讀不動它**：`search_path`、DEFINER、權限收回、
default privileges 這些系統最敏感的一層，沒有人能獨立覆核，每次改動都只能相信產生它的工具。
這不是某次事件，是結構性的——而且接下來的功能（檢舉、成就、打卡）多半不是 SQL 形狀，
架構原則為此預留的 Edge Functions（TypeScript）層從未出生，擁有者對 TS 的掌握也不比 SQL 好。

iOS 夥伴拿到 v3.3 契約已近一個月但**尚未開工**。契約的 transport 是 PostgREST 的殘留
（`/rest/v1/rpc/<fn>`、`p_` 參數、`P0001` 錯誤碼、`details` 是要二次解析的字串）——
現在是唯一可以無痛做破壞性修正的時機；App 一上線，任何 transport 變更都要做版本協調。

## Solution

業務邏輯**全部**搬進一個 Spring Boot 服務，用擁有者能審的語言重寫；資料庫與 auth **不動**：
Supabase 只剩「代管 Postgres＋PostGIS」與「GoTrue 發 JWT」兩個角色，schema 一字不改。
契約升 **v4**：路徑改為乾淨的 REST、錯誤改用標準的 problem+json，但**語意層全部凍結沿用**
——錯誤 token、Note 的 9 個鍵、探索提示的 7 個鍵、游標規則、未知值政策、所有上限與半徑常數，
iOS 讀到的規則跟 v3.3 一模一樣，只是打的網址與解錯誤的方式變了。

Java 版通過驗收前，線上的 RPC 版仍是生產版本；通過後一刀切：iOS 改打 v4、同一天 drop 五支 RPC、
PostgREST 以權限鎖死。v4 契約**先出、先交付**，讓 iOS 與 Java 依同一份文件並行。

## User Stories

### 旅人（app 使用者）——既有體驗一項都不能少

1. 作為旅人，我想依當前位置留下便條，好讓路過的人撿到我的話。
2. 作為旅人，我想留下的便條帶著我選的顏色與樣式，好讓它是我親手做的卡片。
3. 作為旅人，我想留下只給自己的旅遊紀錄，好讓我安心記錄私密的事，且確定沒有人撿得走。
4. 作為旅人，我想看到 100 公尺內有哪些便條、各自多遠、走近沒，好讓我決定要不要走過去。
5. 作為旅人，我想在探索時看不到便條內容，好讓「走進 50 公尺撿起」仍是一種獎勵。
6. 作為旅人，我想走進 50 公尺就能撿起便條並看到內容，好讓我收到那個人留下的話。
7. 作為旅人，我想撿到的便條全世界只有我一個人有，好讓「搶到了」是真的。
8. 作為旅人，我想撿起成功但網路斷掉時重試不會被誤報「被搶走」，好讓我不會白白失去它。
9. 作為旅人，我想走太遠撿不到時看到還差幾公尺，好讓我判斷要不要再走幾步。
10. 作為旅人，我想內容太長時知道上限是多少，好讓我知道要刪掉多少字。
11. 作為旅人，我想按到全形空白鍵留下的空便條被擋下，好讓我不會留下看起來壞掉的東西。
12. 作為旅人，我想未撿便條達到上限時知道那個數字，好讓我理解為什麼現在不能再留。
13. 作為旅人，我想旅遊紀錄不佔用公開便條的額度，好讓我長期記錄旅程而不撞到防濫用的限制。
14. 作為旅人，我想撿得太頻繁被擋下時知道大概要等多久，好讓我不必盲目重試。
15. 作為旅人，我想翻閱自己留過的便條時沒有便條莫名消失（含已過期的），好讓我信任這份紀錄完整。
16. 作為旅人，我想知道我的公開便條何時會退出地圖，好讓 app 能顯示倒數或「已過期」。
17. 作為旅人，我想翻閱我撿到的便條，好讓我重溫收藏。
18. 作為旅人，我想列表翻到最後一頁時立刻知道沒有更多，好讓 app 不多轉一次載入圈。
19. 作為旅人，我想後端改版時 app 不需要重新登入，好讓我的匿名身分與便條都還在。
20. 作為旅人，我想 app 更新前後端新增的顏色或欄位不會讓便條破圖，好讓我不被迫更新。
21. 作為旅人，我想我的位置與便條內容不會被寫進伺服器的一般日誌，好讓隱私不因除錯而外洩。

### iOS 開發者

22. 作為 iOS 開發者，我想在後端動工前就拿到 v4 契約，好讓我與後端並行、不互相等。
23. 作為 iOS 開發者，我想 v4 與 v3.3 的**規則**完全相同，好讓我讀過的語意文件不必重讀一遍。
24. 作為 iOS 開發者，我想路徑是資源導向的 REST，好讓 client 層照慣例寫、不必理解 RPC 命名。
25. 作為 iOS 開發者，我想請求端的座標也是巢狀物件，好讓請求與回應用同一個型別。
26. 作為 iOS 開發者，我想錯誤是標準的 problem+json，`code` 是凍結 token、`details` 是**真物件**，好讓我不必二次解析字串。
27. 作為 iOS 開發者，我想 HTTP 狀態碼有語意（401／404／409／429…），好讓通用層能先分流、業務層再 switch `code`。
28. 作為 iOS 開發者，我想被限流時有標準的 `Retry-After` header，好讓我用平台內建的重試機制。
29. 作為 iOS 開發者，我想只帶一個 `Authorization: Bearer` header，好讓我不必再管 apikey。
30. 作為 iOS 開發者，我想清楚知道哪些路徑打 Supabase（登入、刷新）、哪些打 HapeeTrail 服務，好讓兩個 base URL 不混。
31. 作為 iOS 開發者，我想 401 一律代表「session 問題、走刷新流程」，好讓我不必對 401 的 body 做比對。
32. 作為 iOS 開發者，我想型別或格式錯誤的請求得到**沒有 `code`** 的 400，好讓「有 `code` 才是業務錯誤」這條規則不變。
33. 作為 iOS 開發者，我想時間戳仍是固定六位小數的 UTC，好讓既有的解析不必改。
34. 作為 iOS 開發者，我想有一份匯入即跑的 Postman collection 對著測試環境全綠，好讓我在寫程式前先看實際樣子。
35. 作為 iOS 開發者，我想契約文件仍不含任何 client 語言程式碼，好讓實作決定完全由我做。

### 後端維護者（擁有者）

36. 作為後端維護者，我想每一條業務規則都寫在 Java 裡，好讓我能逐行審、改、加測試。
37. 作為後端維護者，我想資料存取是普通 SQL 語句而不是 ORM 對映，好讓我看得到實際跑的查詢與索引。
38. 作為後端維護者，我想撿取的獨佔性仍由單一條件式 UPDATE 保證，好讓併發正確性不依賴 Java 端的鎖。
39. 作為後端維護者，我想距離計算只在資料庫端做，好讓探索、撿取、錯誤附帶的距離永遠同一算法。
40. 作為後端維護者，我想服務用一個最小權限的資料庫角色連線，好讓服務被攻破時攻擊面只有那張表的 DML。
41. 作為後端維護者，我想 JWT 驗證交給 Spring Security 的標準元件，好讓我不必自己寫簽章驗證。
42. 作為後端維護者，我想 client 角色在切換後對資料庫沒有任何可用路徑，好讓 ADR-0007 的保證延續到新架構。
43. 作為後端維護者，我想 Java 版通過驗收前線上不動，好讓重寫拖延時不會有壓力硬切。
44. 作為後端維護者，我想 `notes.test.sql` 的每個情境都在 Java 測試裡有對應（或寫明為何不搬），好讓驗證重做不是憑印象。
45. 作為後端維護者，我想測試在本機用容器起一個與 hosted 同版本的 Postgres，好讓測試不依賴網路也不污染測試環境。
46. 作為後端維護者，我想上限的併發超越量被量化並記錄，好讓我知道 Java 版比 SQL 版多退化了多少。
47. 作為後端維護者，我想 schema 仍由 Supabase CLI 的 migration 管，好讓角色、權限、drop RPC 這些變更與既有 14 支 migration 同一條歷史。
48. 作為後端維護者，我想 CLAUDE.md 的架構原則與新架構一致，好讓下一個 session 不會照舊原則「修正」我。
49. 作為後端維護者，我想三份契約產出（OpenAPI、語意文件、Postman）同步升 v4，好讓 iOS 不會讀到互相矛盾的說法。

### 部署／營運

50. 作為營運者，我想服務打包成容器部署在東京，好讓每個請求不多一趟跨區往返。
51. 作為營運者，我想服務常駐不縮零，好讓旅人不會撞到 JVM 冷啟動的幾秒空白。
52. 作為營運者，我想有一個不需認證的健康檢查端點，好讓平台能判斷服務活著。
53. 作為營運者，我想所有密鑰（資料庫密碼、JWKS 位址）只在部署平台的 secrets 裡，好讓 repo 公開也不外洩。
54. 作為營運者，我想部署後有一支煙霧測試驗「換一台機器才會壞」的事，好讓每次部署都能一分鐘內確認。
55. 作為營運者，我想部署後 `/rest/v1/*` 對 client 角色全部不可達，好讓沒有第二條路繞過服務。

### 安全審查者

56. 作為安全審查者，我想 JWT 缺失、過期、簽章不符、缺 `sub`、`aud` 不符五種變形全部 fail-closed，好讓沒有任何一種變形拿到資料。
57. 作為安全審查者，我想每支列表只回呼叫者自己的資料且有跨使用者的正面斷言，好讓 WHERE 掉了會立刻紅。
58. 作為安全審查者，我想私人便條與過期便條對外人的回應與不存在完全相同，好讓外人無法探測某座標是否有他看不到的便條。
59. 作為安全審查者，我想限流閘門下 `too_far` 不會洩漏距離，好讓距離探測 oracle 不能用限流繞過（T15 的立場）。
60. 作為安全審查者，我想 Note 與探索提示永遠不含任何 uuid 身分欄位，好讓帳號綁定後無法回溯連結真人。

## Implementation Decisions

### 服務形狀

- **一個 Spring Boot 4.1／Java 21 應用**，住在 repo 內的 `api` 目錄（由今天建立的骨架改名而來，
  Copilot 的 modernize 殘骸刪除）。Maven；artifact 與 package 沿用 `hapeetrail`。
- 分層取最少：HTTP 層（契約形狀、狀態碼、problem+json）、一個便條服務（全部業務規則）、
  `JdbcClient` 的普通 SQL。**不建 repository 介面、不建 DTO mapper、不用 JPA**；
  wire 型別用 Java record。單一實作不抽介面。
- 新增依賴只有四類：JDBC starter ＋ PostgreSQL driver、OAuth2 resource server、Actuator（只開 health）、
  Testcontainers（test scope）。其餘一律不加。

### Auth：Spring 只驗 GoTrue 的 JWT

- 以 Spring Security resource server 驗證 Bearer JWT，JWKS 指向 Supabase 專案的 `/auth/v1/.well-known/jwks.json`。
  **施工第一步要查證專案的 JWT 簽章方式**：若仍是 legacy 的 HS256 共享密鑰，先在 dashboard 切換到
  非對稱簽章金鑰——共享密鑰一旦進 Spring，等於 Spring 也能鑄造 token，違反最小權限。
- 使用者身分＝JWT 的 `sub`；`aud` 必須為 `authenticated`。缺 `sub`、簽章不符、過期、`aud` 不符、
  沒有 header，一律 **HTTP 401 ＋ `code: not_authenticated`**（token 沿用，狀態碼由 400 改 401）。
- 匿名與正式帳號在服務端**無差別**：`is_anonymous` claim 不影響任何規則（第二階段帳號綁定不需要動服務）。
- 登入、刷新、日後的帳號綁定**全部仍打 Supabase**；服務不代理任何 auth 路徑。

### 資料庫：角色、權限、RLS

- 新增 Supabase migration 建立登入角色 **`hapeetrail_api`**：`public` 與 `extensions` schema 的 USAGE、
  `public.notes` 的 SELECT／INSERT／UPDATE（**無 DELETE**——契約沒有刪除路徑）。
  不授予 `auth.users` 任何權限：FK 檢查以表擁有者身分執行，不需要。密碼**不寫在 migration**，
  以一次性的手動 SQL 設定，值只存在部署平台的 secrets。
- `notes` 表的 RLS 維持啟用。既有的 `notes_select_own` 保留休眠（ADR-0007）；**新增一條只給
  `hapeetrail_api` 的全列 permissive policy**（讀寫皆放行）。理由：RLS 不關，日後 default privileges
  若讓某個 client 角色靜默重新拿到表權限，休眠的 policy 仍把它限在自己的列——多一層不花錢的防線。
- 連線走 Supabase 的 **session pooler**（IPv4 可達；transaction pooler 是給 serverless 的）。
  HikariCP 池大小取小（個位數）：Supabase Pro 的連線數是共用資源。
- schema 一字不動：`author_id`／`picked_up_by` 仍 FK 到 `auth.users`，索引不動。

### 業務規則（全部搬進 Java；常數值與 v3.3 逐一相同）

- 探索半徑 100m、撿取半徑 50m、探索最多 20 筆最近優先；**所有距離與半徑判定仍在 SQL 語句內用 PostGIS
  的 geography 運算**，Java 永遠不算距離——探索的 `distanceM`、撿取的 `too_far` 附帶距離、
  `pickable` 三者同一算法（T11-08「距離算法單一真相」延續）。
- 內容：先 trim 再以 **Unicode code point** 計數，1–500。trim 的字元集必須與 v3.3 契約**逐字相同**：
  Unicode `White_Space` ＋ U+001C–U+001F；格式字元（U+200B–200D、U+2060、U+FEFF、U+180E）不算空白。
  **不得用 Java 標準庫的 `isWhitespace`**——它不含 NBSP、U+2007、U+202F，會與契約分歧；用明列字元類別的正規表示式。
  回傳的 `content` 是 trim 後版本。
- `color`／`style`：省略或 null → 1；範圍 1–32767 之外 → `invalid_style_code`；範圍內任何值原樣存、原樣回，不驗語意。
- `audience`：省略或 null → `anyone`；`anyone`／`self` 以外 → `invalid_audience`，**不走預設**。
- 上限：未撿、未過期的公開便條每人 50（`active_note_limit`，附 `maxActiveNotes`）；旅遊紀錄每人絕對 5000
  （`private_note_limit`，附 `maxPrivateNotes`）；兩者互不影響。計數與 INSERT 在同一交易內。
- 撿取頻率：滾動一小時內 60 次（`pickup_rate_limited`，附**算出來的** `retryAfterS`＝第 60 近那次撿取離開窗口的秒數）。
- TTL：公開便條 `createdAt` ＋ 90 天退出探索與撿取並釋放額度；**讀時推導、無欄位、無 cron**（ADR-0010）。
  `expiresAt` 公開便條為推導值、旅遊紀錄為 null；已撿走的仍保有 `expiresAt`。
- 探索排除：呼叫者自己的、已撿走的、已過期的、任何人的旅遊紀錄。
- **撿取的原子性與診斷順序**（與現行 RPC 一致，順序是契約的一部分）：
  1. 驗身分、驗座標；
  2. 頻率閘門：若已達 60 次——這張便條**已經是呼叫者的** → 照常回成功（冪等重試不計入額度，T15）；否則 `pickup_rate_limited`；
  3. **單一條件式 UPDATE**：`id 相符 且 未撿 且 audience=anyone 且 未過期 且 50m 內`，`RETURNING` 即成功；
  4. UPDATE 影響 0 列才診斷：不存在 → `note_not_found`；已被撿走（不是自己）→ `note_taken`；已是自己的 → 成功（冪等）；
     作者是自己 → `own_note`；旅遊紀錄 → `note_not_found`；已過期 → `note_not_found`；其餘 → `too_far` 附實際距離。
  診斷只在 UPDATE 失敗後跑，happy path 一句 SQL。
- 冪等重試回傳**原本的** `pickedUpAt`，不得改寫。
- 列表：my_notes 依 `createdAt` 新→舊、my_collection 依 `pickedUpAt` 新→舊，皆以（排序鍵, id）平手；
  keyset 分頁多取一筆判斷有無下一頁；`limit` 省略 50、靜默夾到 1–100。
- 游標：不透明 base64 JSON，內含版本、**所屬列表**、排序鍵、id；不簽章不加密（不授予任何權限）。
  無法解碼、版本不符、列表不符 → `invalid_cursor`。編碼由 Java 實作，**與 SQL 版的游標不相容**——
  v4 是乾淨切換，夥伴尚未開工，沒有舊游標要相容。

### 契約 v4（只換 transport；語意層凍結）

路徑與方法（座標**不進 URL**——沿用 v3 §10 對存取日誌與 proxy 快取的立場，因此探索是 POST）：

| 動作 | v4 | 請求 |
|---|---|---|
| 留便條 | `POST /v1/notes` | body：`content`、`coordinate{latitude,longitude}`、`color?`、`style?`、`audience?` |
| 探索 | `POST /v1/notes/nearby` | body：`coordinate` |
| 撿起 | `POST /v1/notes/{id}/pickup` | body：`coordinate` |
| 我的便條 | `GET /v1/me/notes` | query：`limit?`、`cursor?` |
| 我的收藏 | `GET /v1/me/collection` | query：`limit?`、`cursor?` |
| 健康檢查 | `GET /actuator/health` | 無認證 |

- 請求端座標改為**巢狀物件**，與回應對稱（v3 扁平的理由——函式參數的型別檢查——已消失）。
- 回應形狀**逐鍵不變**：Note 9 鍵、NearbyHint 7 鍵、`{items, nextCursor}` 與 `{items}` envelope、
  camelCase、時間戳固定 `YYYY-MM-DDTHH:MM:SS.ffffffZ`（Java 端必須明確格式化為六位小數，
  預設序列化的位數是可變的）。
- 錯誤改為 **RFC 9457 problem+json**，`code` 仍是唯一的判斷依據，`details` 改為真物件、沒有附帶資料時省略：

```json
{ "type": "about:blank", "title": "too_far", "status": 403,
  "code": "too_far", "details": { "distanceM": 87 } }
```

- 狀態碼對照（token 字串全部沿用、一個不加）：

| 狀態 | token |
|---|---|
| 400 | `invalid_coordinates` `invalid_cursor` `content_empty` `content_too_long` `invalid_style_code` `invalid_audience` |
| 401 | `not_authenticated` |
| 403 | `own_note` `too_far` |
| 404 | `note_not_found`（含私人與過期，與不存在無法區分） |
| 409 | `note_taken` |
| 422 | `active_note_limit` `private_note_limit` |
| 429 | `pickup_rate_limited`（另附標準 `Retry-After` header） |

- **型別／格式錯誤的請求**（非法 JSON、欄位型別不對、缺必填）→ 400 problem+json **沒有 `code`**：
  這是 v3「第二層閘門」的對應物，「有 `code` 才是業務錯誤」的規則不變，不新增 token。
- 未知值政策（§9）逐字沿用；`audience` 仍是請求端唯一「不認得就拒絕」的欄位。
- OpenAPI：`info.version` 4.0.0；`servers` 指向 HapeeTrail 服務；`/auth/v1/signup` 保留在文件裡
  但以該 path 自己的 `servers` 指向 Supabase，讓「兩個 base URL」在文件裡一目了然；apikey security scheme 移除。
- 語意文件重寫 transport 相關段落（curl 範例、錯誤閘門、§10 契約外路徑改寫為「Supabase 的 `/rest/v1/*` 對
  client 全部不可達」），規則段落逐字保留；Changelog 記 v4。`style-codes.md` 不動。
- Postman collection 與兩個 environment 改打新路徑與新錯誤信封；匿名登入請求仍打 Supabase。

### 切換

- 兩支 migration 分開：**準備**（建角色、授權、RLS policy）在 Java 部署前套用，與 RPC 版並存無害；
  **切換**（drop 五支 RPC ＋ 五支 helper、收回殘餘 EXECUTE）在 iOS 改打 v4 的同一天套用。
- 切換後 hosted 煙霧測試改打 HapeeTrail 服務，並**正面斷言** `/rest/v1/notes` 各變體與五支舊 RPC 路徑
  對 `authenticated` 全部 401／403／404。
- CLAUDE.md 的技術棧與架構原則改寫為新架構（第一張 ticket）；HANDOFF 的 as-built 段同步。

### 部署

- `spring-boot:build-image` 或最小 Dockerfile 出容器；Fly.io `nrt`、常駐一台（不縮零）、
  1 shared CPU／1GB（JVM 需要）；TLS 由平台；health check 打 `/actuator/health`。
- 設定全部走環境變數／平台 secrets：資料庫 URL 與密碼、JWKS 位址。repo 內只有非機密預設值。
- **日誌不記座標與內容**：INFO 層只記路徑、狀態碼、耗時、錯誤 `code`；請求 body 永不落日誌。

## Testing Decisions

好的測試只驗**外部可觀察的行為**：打某個端點得到什麼狀態碼、什麼 `code`、什麼形狀、什麼資料
——不驗 service 方法、不驗 SQL 字串、不驗游標的內部編碼（只驗「原樣回傳可翻頁、竄改被拒、
拿錯列表被拒」）。既有 `notes.test.sql` 的立場「只斷言外部行為，游標內部編碼刻意不斷言」原樣繼承。

### Seam A：HapeeTrail 服務的 HTTP 邊界（主力，唯一新 seam）

- `@SpringBootTest` 起**真實 servlet 容器**（隨機 port），用 HTTP client 打；不用 MockMvc——併發情境
  要真的平行請求才驗得到 row lock。
- 資料庫用 Testcontainers 起 **Supabase 官方的 Postgres 映像（與 hosted 同大版本 17）**，套用
  `supabase/migrations` 同一份 SQL（含準備與切換兩支新 migration）。映像缺什麼補**最小 shim**
  （預期只有 `auth.users` 表；施工時查證），shim 只存在測試資源、不進 migration。
- JWT 由測試自簽：測試 profile 用本機 RSA 公鑰取代 JWKS，測試程式以私鑰鑄 token 指定 `sub`／`aud`。
  五種變形（無 header、簽章不符、過期、缺 `sub`、`aud` 不符）各一條斷言 401。
- 座標**每次隨機**、各使用者的便條群以整數度隔開（T18 的不變式），測試才能在共用資料庫上重跑不互擾。
- 情境清單＝`notes.test.sql` 的段落，逐條搬，每條在 ticket 裡對照打勾；**不搬的要寫理由**：
  - drop：驗證與 trim（含 Unicode 空白 35 個碼位的代表樣本、格式字元不算空白、500 恰好合法、501 拒絕附 `maxChars`）
  - style 代號：預設、可省略、互不干擾、超範圍照收、越界拒絕
  - 私人便條：不進他人探索（用「同點一公開一私人、只看到公開」的寫法，不用「斷言為空」）、他人撿回 `note_not_found`、不佔公開額度、非法值拒絕
  - 探索：半徑（30m 可撿、70m 可見不可撿、130m 不可見）、排序最近優先、上限 20、排除自己、撿走的消失
  - 撿取：距離、獨佔（**10 條平行請求恰 1 成功 9 `note_taken`**）、冪等（回原 `pickedUpAt`）、`own_note`、亂 id、閘門下冪等仍成功且真新撿取照擋、`retryAfterS` 是算出來的
  - 上限：第 51 張拒、過期釋放額度、5000／5001 邊界、兩閘門互不影響；**併發超越量量化並記進 ticket**
  - TTL：89／90／91 天在探索、撿取、額度三處**完全一致**；過期便條仍在 my_notes 且 `expiresAt` 正確；已撿走的不受影響
  - 列表：29 張每頁 10 走完不重不漏、頁大小恰等於總數時 `nextCursor` 為 null、全部同刻的 60 筆翻頁無重疊、空列表回空陣列、竄改／跨列表／亂字串游標一律 `invalid_cursor`、`limit` 越界夾住
  - 授權隔離：A 無便條 B 有 29 張，A 的 my_notes 為空、my_collection 恰 1（跨使用者正面斷言）
  - wire：Note 恰 9 鍵、NearbyHint 恰 7 鍵、無任何 uuid 身分欄位、時間戳六位小數、problem+json 形狀、型別錯誤的 400 無 `code`、429 帶 `Retry-After`
  - **刻意不搬**：RLS／表權限／helper 授權那一段（那是 PostgREST 路徑，改由 Seam B 驗）
- 依 `tdd` skill 走 red-green：每條情境先看它紅；併發與邊界情境沿用專案慣例做**突變測試**證明斷言實心。
- 地理查詢的 EXPLAIN 是一次性 checklist（Java 端的語句與 RPC 內同一句、索引不動），不是測試。

### Seam B：對部署後的 Fly.io 打 newman ＋ hosted 煙霧測試（既有，驗收）

- Postman collection 改新路徑與新錯誤信封後對測試環境全綠，斷言精簡不重複 A；它同時是交付給 iOS 的範例，
  資料夾順序即完整流程。
- 煙霧測試只驗「換一台機器才會壞」的事：真 GoTrue 的 JWT 驗得過、PostGIS 可用、資料庫 locale 支援 Unicode 空白、
  五支端點可達、**Supabase 的 `/rest/v1/*` 對 client 全部不可達、五支舊 RPC 不在**、anon 完全不可達。

### 驗收

兩個 seam 全綠後，依專案守則派**兩個獨立複核**，只拿本 spec 不繼承實作假設：
安全（JWT fail-closed、Java 端授權隔離、`hapeetrail_api` 權限最小、契約外路徑不可達、限流下無距離 oracle）
與正確性（併發、邊界、三處 TTL 一致、游標平手）。

## Out of Scope

- **T3 檢舉機制**、**T20 文件改名**：各自獨立 ticket；T3 在切換後於 Java 實作。
- **離開 Supabase、自建 auth、自架 Postgres**：ADR-0011 的遷移訊號未觸發。
- **JPA／Hibernate、repository 抽象、DTO mapper**：一張表三句查詢，純成本。
- **HTTP 層的通用限流／WAF、指標、追蹤、集中日誌**：MVP 規模用不到；health 以外的 Actuator 端點不開。
- **CI pipeline**：repo 目前沒有 CI；要加另開 ticket。
- **API 版本化機制**：有未知值政策；真要破壞時開新路徑。
- **照片、作者資訊、撿起者資訊、狀態欄位**：v3 spec 的 out of scope 原樣沿用。
- **中國市場 GCJ-02**：依 CLAUDE.md 留待啟動時。
- **iOS 端任何程式碼或實作決定**。
- **舊游標相容**：夥伴未開工，沒有舊游標。

## Further Notes

**建議的 ticket 順序**：①CLAUDE.md 架構原則改寫（否則下一個 session 會照舊原則「修正」）→
②契約 v4 三份產出（**先交付夥伴**，此後語意凍結）→ ③骨架改名、清殘骸、依賴、設定、Testcontainers 起得來
→ ④準備 migration（角色、權限、policy）與 JWT 驗證（五種變形 401）→ ⑤五支端點逐支 TDD（順序：
留便條 → 我的便條 → 探索 → 撿起 → 收藏，每支帶上它的情境清單）→ ⑥容器化與 Fly.io 部署、煙霧測試
→ ⑦兩個獨立複核 → ⑧切換 migration ＋ 通知夥伴改打 v4 ＋ HANDOFF。

**施工前要查證的三件事**（不查會在中途返工）：專案 JWT 是 HS256 還是非對稱金鑰；Supabase 的
Postgres 映像是否自帶 `auth.users`；session pooler 對 Fly.io 的 IPv4 可達性。

**與既有 ADR 的關係**：ADR-0001（獨佔＝單句條件式 UPDATE）、0002（client 輪詢）、0003（信任 client 座標＋兩個上限）、
0004（不透明游標）、0005（代號在裝置端）、0006（私人便條）、0008（5000 上限）、0009（Unicode 空白）、
0010（TTL 讀時推導）**全部沿用，實作位置從 SQL 改為 Java**；ADR-0007 的原則延續，路徑從 RPC 改為服務。
不需要修改任何既有 ADR。

**已知會退化的一點**（ADR-0011 已接受）：上限與頻率閘門在 Java 端是「查數量→動作」兩句，併發超越量可能
大於 SQL 版（T13 量到 20 連線 5000→5019）。量化後若不可接受，補 DB constraint／trigger，不重開 RPC。

**上線前的非程式動作**：Supabase 升 Pro（Free 閒置 7 天暫停、無法下載備份）；Fly.io 帳號與 secrets。
