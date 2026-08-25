# 10 — 首次部署 Fly.io `nrt`

**What to build:** 服務以容器跑在東京、常駐不縮零、TLS 由平台；用**真正的 GoTrue** 匿名 token 打 Fly 上的 `GET /v1/me/notes` 得到 200 空列表——實證 JWKS 驗證、session pooler 的 IPv4 可達性、secrets 注入三件只有上了真機才會壞的事。早部署早發現，不等五支端點全做完。

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] 容器：`spring-boot:build-image` 或最小 Dockerfile；映像能在本機以環境變數起動
- [ ] Fly app 建在 `nrt`；1 shared CPU／1GB；`min_machines_running=1`、不 auto-stop；health check 打 `/actuator/health`
- [ ] secrets：資料庫 URL（session pooler 5432）、`hapeetrail_api` 密碼、JWKS 位址——全部 `fly secrets`，repo 無任何機密；`hapeetrail_api` 密碼以一次性手動 SQL 設定在 hosted 專案（準備 migration 已 `db push`）
- [ ] 查證並記錄：Fly → Supabase session pooler 連得上（IPv4）；連線池個位數
- [ ] 以 hosted 專案的匿名登入取得真 token → 打 Fly 的 `/v1/me/notes` 200 `{items:[],nextCursor:null}`；壞 token 401；無 token 401；`/actuator/health` 200
- [ ] 首位元組延遲實測記進本票（Fly nrt → AWS 東京；只記數量級，不當 SLA）
- [ ] 部署步驟寫成一段可重跑的說明（放 `api` 的 README 或 HANDOFF），下個 session 能照做
- [ ] OpenAPI `servers` 的 placeholder 換成實際網址（02 留的）
