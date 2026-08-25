# 02 — 契約 v4 三份產出並交付夥伴

**What to build:** iOS 夥伴在後端動工前拿到 v4 契約：路徑改為 REST、錯誤改為 problem+json、只帶 Bearer、座標巢狀——但**規則逐字與 v3.3 相同**（token、Note 9 鍵、NearbyHint 7 鍵、游標規則、未知值政策、所有常數）。三份產出（OpenAPI、語意文件、Postman）同步升 4.0.0；正式文件頁面 push 即更新。此後語意凍結，iOS 與 Java 依同一份文件並行。

**Blocked by:** None — can start immediately

**Status:** done（2026-08-25）

- [x] OpenAPI `info.version` 4.0.0；五支端點依 spec 的路徑表（`POST /v1/notes`、`POST /v1/notes/nearby`、`POST /v1/notes/{id}/pickup`、`GET /v1/me/notes`、`GET /v1/me/collection`）＋ `GET /actuator/health`
- [x] `servers` 指向 HapeeTrail 服務（測試環境網址可先留 placeholder 並註明）；`/auth/v1/signup` 保留，以該 path 自己的 `servers` 指向 Supabase；apikey security scheme 移除，只剩 Bearer
- [x] 請求端座標改巢狀 `coordinate{latitude,longitude}`；列表參數 `limit`／`cursor` 為 query
- [x] 錯誤 schema 改 RFC 9457 problem+json：`code` 為凍結 token enum（14 個，一個不加）、`details` 為物件（nullable／可省略）；狀態碼對照表逐 token 寫進各端點的 responses（400／401／403／404／409／422／429，429 帶 `Retry-After`）
- [x] 型別／格式錯誤 → 400 且**沒有 `code`** 的規則寫進錯誤章節（v3「第二層閘門」的對應物）
- [x] 回應 schema（Note、NearbyHint、NotePage、NearbyResult、Timestamp、Cursor、StyleCode、Audience）逐鍵不變，只改描述中的 transport 說法
- [x] `redocly lint` 通過；GitHub Pages 的 Swagger UI 頁面能載入 v4
- [x] 語意文件：§1 session、§2 錯誤閘門、§4–§7 的 curl 範例、§10 契約外路徑重寫為新 transport；§3／§8／§9 規則段逐字保留；§11 Changelog 記 v4（破壞性：transport；非破壞：規則）
- [x] Postman collection 與兩個 environment 改新路徑與新斷言（錯誤斷言改看 `code` 與 status）；匿名登入請求仍打 Supabase；此票只要求 import 得起來、結構完整——全綠是 11 的事
- [x] 契約文件仍不含任何 client 語言程式碼
- [x] 交付：把三份產出與「請勿對 v3.3 開工、直接依 v4」的說明交給夥伴（HANDOFF 記錄交付日期）

---

## 完成紀錄（2026-08-25）

三份產出全部升 4.0.0：`docs/api/openapi.yaml`、`docs/api/notes.md`、`docs/api/postman/`（collection ＋ 兩個 environment）。
`docs/index.html` 的 Swagger UI banner 同步改（Bearer、兩個 base URL）。

**證據**
- `npx @redocly/cli lint docs/api/openapi.yaml` → exit 0（1 個 advisory warning：`/actuator/health` 沒有 4XX 回應，屬實，未捏造回應去消音）。
- Swagger UI 實載（本機 serve `docs/` + Playwright）：標題顯示 `4.0.0`，六支路徑全部出現
  （`POST /v1/notes`、`POST /v1/notes/nearby`、`POST /v1/notes/{id}/pickup`、`GET /v1/me/notes`、
  `GET /v1/me/collection`、`GET /actuator/health`），`/auth/v1/signup` 顯示自己的 Supabase `servers`，
  Authorize 只剩 Bearer。唯一 console error 是 `favicon.ico` 404（既有、與契約無關）。
- **語意凍結的機械驗證**（不是憑印象）：
  - 14 個 token：`ApiError.message.enum`(v3.3) 與 `Problem.code.enum`(v4) `diff` 結果**完全相同**。
  - `Note` 9 鍵、`NearbyHint` 7 鍵、`NotePage`／`NearbyResult` envelope：required 與 properties 逐一相同。
  - token→狀態碼對照：腳本掃過所有端點 responses，7 個狀態碼各自的 token 集合與 spec 的表**完全吻合，無缺無多**。
  - v3 transport 殘留（`p_` 參數、`/rest/v1/rpc/`、`P0001`）在 openapi／postman／index.html 為 **0 處**；
    notes.md 僅 4 處，全部是 §11 Changelog 與 §10 過渡期聲明中刻意的歷史引用。
  - `securitySchemes` 只剩 `Bearer`；`/auth/v1/signup` 與 `/actuator/health` 為 `security: []`。
  - §3／§8／§9 對 v3.3 逐段 `diff`：全部差異都是 transport 名詞改寫（RPC 名 → 路徑、`message` → `code`、
    `p_audience` → `audience`、`details` 字串 → 物件）＋ §8 表新增 HTTP 欄位。**沒有一條規則或常數被改動**。
- Postman：三個 JSON 皆為合法 JSON、collection 共 16 支請求；錯誤斷言改看 `code` 與有語意的狀態碼
  （`invalid_cursor` 400、`invalid_audience` 400、`note_not_found` **404**、`too_far` **403** 且 `details` 斷言為**物件**）。
  全綠是票 11 的事，此票只要求 import 得起來、結構完整。

**判斷與偏離**
- **成功狀態碼一律 200**（留便條不用 201）：spec 的狀態碼表只規範錯誤；契約沒有單張便條的 GET 路徑，
  沒有 `Location` 可指，且 v3.3 就是 200。已在 openapi 與 notes.md §4 寫明理由。
- **`apikey` 以 header parameter 形式保留在 `/auth/v1/signup`**（不是 security scheme）。
  security scheme 依票移除；但 Supabase 端確實要求這個 header，不寫夥伴的 signup 會失敗。
- **加了兩處票面沒明列的東西**：①Postman 多一支 `GET /actuator/health`（該路徑是本票 openapi 的要求項，
  收進可執行範例才對得起來）；②§8 補一句「token 與狀態碼的對照同樣凍結」（v4 才有狀態碼語意，
  不寫的話這層對照的穩定性沒有定義）。兩者都可撤。
- **`details` 的 null 與省略**：票寫「nullable／可省略」，因此文件要求 client **不得區分**
  「沒有這個鍵」與「值為 null」，兩者都代表沒有附帶資料。
- **`servers` 是 placeholder**：`https://hapeetrail.fly.dev`（openapi ＋ hosted environment 各一處），
  票 10 部署定案後更新這兩行，契約本身不動。

**過程中發現、未處理（超出本票範圍，待討論）**
- `docs/api/build-doc.py` 對 v4 已失效：硬編 `/rest/v1/rpc/` 路徑、`ApiError`、`P0001`、apikey，
  跑起來端點區塊會是空的。它自稱「產生器，不是第四份契約產出」，不在本票的三份產出內，故未動。
- 游標進了 query 之後需要 URL-safe 編碼（票 6 實作時決定）：標準 base64 的 `+` 在 query 會被解成空白，
  且 Postman 不會替 `{{next_cursor}}` 做編碼。建議 base64url。契約端已在 notes.md §7 要求 client URL-encode。
