# 02 — 契約 v4 三份產出並交付夥伴

**What to build:** iOS 夥伴在後端動工前拿到 v4 契約：路徑改為 REST、錯誤改為 problem+json、只帶 Bearer、座標巢狀——但**規則逐字與 v3.3 相同**（token、Note 9 鍵、NearbyHint 7 鍵、游標規則、未知值政策、所有常數）。三份產出（OpenAPI、語意文件、Postman）同步升 4.0.0；正式文件頁面 push 即更新。此後語意凍結，iOS 與 Java 依同一份文件並行。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] OpenAPI `info.version` 4.0.0；五支端點依 spec 的路徑表（`POST /v1/notes`、`POST /v1/notes/nearby`、`POST /v1/notes/{id}/pickup`、`GET /v1/me/notes`、`GET /v1/me/collection`）＋ `GET /actuator/health`
- [ ] `servers` 指向 HapeeTrail 服務（測試環境網址可先留 placeholder 並註明）；`/auth/v1/signup` 保留，以該 path 自己的 `servers` 指向 Supabase；apikey security scheme 移除，只剩 Bearer
- [ ] 請求端座標改巢狀 `coordinate{latitude,longitude}`；列表參數 `limit`／`cursor` 為 query
- [ ] 錯誤 schema 改 RFC 9457 problem+json：`code` 為凍結 token enum（14 個，一個不加）、`details` 為物件（nullable／可省略）；狀態碼對照表逐 token 寫進各端點的 responses（400／401／403／404／409／422／429，429 帶 `Retry-After`）
- [ ] 型別／格式錯誤 → 400 且**沒有 `code`** 的規則寫進錯誤章節（v3「第二層閘門」的對應物）
- [ ] 回應 schema（Note、NearbyHint、NotePage、NearbyResult、Timestamp、Cursor、StyleCode、Audience）逐鍵不變，只改描述中的 transport 說法
- [ ] `redocly lint` 通過；GitHub Pages 的 Swagger UI 頁面能載入 v4
- [ ] 語意文件：§1 session、§2 錯誤閘門、§4–§7 的 curl 範例、§10 契約外路徑重寫為新 transport；§3／§8／§9 規則段逐字保留；§11 Changelog 記 v4（破壞性：transport；非破壞：規則）
- [ ] Postman collection 與兩個 environment 改新路徑與新斷言（錯誤斷言改看 `code` 與 status）；匿名登入請求仍打 Supabase；此票只要求 import 得起來、結構完整——全綠是 11 的事
- [ ] 契約文件仍不含任何 client 語言程式碼
- [ ] 交付：把三份產出與「請勿對 v3.3 開工、直接依 v4」的說明交給夥伴（HANDOFF 記錄交付日期）
