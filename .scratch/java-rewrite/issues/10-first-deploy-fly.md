# 10 — 首次部署 Fly.io `nrt`

**What to build:** 服務以容器跑在東京、常駐不縮零、TLS 由平台；用**真正的 GoTrue** 匿名 token 打 Fly 上的 `GET /v1/me/notes` 得到 200 空列表——實證 JWKS 驗證、session pooler 的 IPv4 可達性、secrets 注入三件只有上了真機才會壞的事。早部署早發現，不等五支端點全做完。

**Blocked by:** 04

**Status:** in-progress — 容器與設定完成並本機驗過；上真機的部分等 Fly 帳號與 hosted 專案 restore

- [x] 容器：`spring-boot:build-image` 或最小 Dockerfile；映像能在本機以環境變數起動
- [ ] Fly app 建在 `nrt`；1 shared CPU／1GB；`min_machines_running=1`、不 auto-stop；health check 打 `/actuator/health`
- [ ] secrets：資料庫 URL（session pooler 5432）、`hapeetrail_api` 密碼、JWKS 位址——全部 `fly secrets`，repo 無任何機密；`hapeetrail_api` 密碼以一次性手動 SQL 設定在 hosted 專案（準備 migration 已 `db push`）
- [ ] 查證並記錄：Fly → Supabase session pooler 連得上（IPv4）；連線池個位數
- [ ] 以 hosted 專案的匿名登入取得真 token → 打 Fly 的 `/v1/me/notes` 200 `{items:[],nextCursor:null}`；壞 token 401；無 token 401；`/actuator/health` 200
- [ ] 首位元組延遲實測記進本票（Fly nrt → AWS 東京；只記數量級，不當 SLA）
- [x] 部署步驟寫成一段可重跑的說明（放 `api` 的 README 或 HANDOFF），下個 session 能照做
- [ ] OpenAPI `servers` 的 placeholder 換成實際網址（02 留的）

---

## 進度（2026-08-25，cli）

### 完成並驗過

**容器**（`api/Dockerfile` ＋ `api/.dockerignore`）：兩段式，build 段 JDK 打包（`-DskipTests`
——測試要 Docker socket 與 `../supabase/migrations`，兩者都不在建置 context 內）、
runtime 段只留 JRE 與 jar，非 root（uid 1001）跑。堆上限用 `-XX:MaxRAMPercentage=75`
不寫死 `-Xmx`，換機型不用改檔。映像 531MB。

**本機以環境變數起動**（票的第一項，實測過）：起 Supabase 官方 Postgres 容器套上 14＋1 支
migration、`alter role hapeetrail_api password`，再以四個環境變數跑映像：

| 斷言 | 結果 |
|---|---|
| 啟動 | `Started HapeetrailApplication in 2.1 seconds` |
| `GET /actuator/health` | 200 `{"status":"UP"}`（Hikari 在這個請求上才初始化 ⇒ db indicator 真的有跑） |
| `GET /v1/me/notes` 無 token | 401 ＋ `{"type":"about:blank","title":"not_authenticated","status":401,"code":"not_authenticated"}` |
| 壞 token | 401 |
| DB 停掉後的 health | curl 20 秒逾時（Hikari 預設 connectionTimeout 30s）⇒ Fly 的 5s check timeout 會判 unhealthy，行為正確 |

**`fly.toml`**：`nrt`、`shared-cpu-1x`／1GB、`auto_stop_machines="off"`＋`auto_start_machines=true`
（JVM 冷啟不縮零；auto_start 是保險絲——機器若因 OOM／host maintenance 停掉，下個請求會叫回來）、
health check 打 `/actuator/health`（`grace_period=30s`、`timeout=5s`）、`force_https`。
**票面寫的 `min_machines_running=1` 沒有照抄**：Fly 文件明說它只在 `auto_stop_machines` 為
`"stop"`／`"suspend"` 時生效，這裡設了是死設定。常駐一台改由首次 `fly deploy --ha=false` 達成
（`--ha` 預設 true，會多開一台待命機器）。

**票 04 留下來的 ES256 地雷，在這裡拆掉了**：`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS="RS256,ES256"`
放在 `fly.toml` 的 `[env]`。**為什麼不能放 `application.properties`**——查了 Boot 4.1.1 的
`JwtDecoderConfiguration` bytecode：`jwk-set-uri` 那條路徑是
`jwsAlgorithms(Consumer<Set>)`，逐一 add，多個沒問題；`public-key-location` 那條
（**測試在走的**）呼叫 `exactlyOneAlgorithm()`，列兩個直接 `IllegalStateException` 啟動失敗。
放 `[env]` 兩邊都對，而且不必先知道 hosted 切的是 RS256 還是 ES256。

**部署 runbook**：`api/README.md`——一次性設定（flyctl、`db push`、角色密碼、`apps create`、
`fly secrets`）＋每次 `fly deploy` ＋部署後四條驗證 curl（含真 GoTrue 匿名 token 那條）。

**pooler 查證（文件層級，非實機）**：Supabase 官方文件確認 shared pooler（Supavisor）
**IPv4-only**，session pooler 走 5432（6543 是 transaction pooler，給 serverless 的）。
自訂角色的使用者名稱要帶 tenant：**`hapeetrail_api.<project-ref>`**，不是裸的角色名
——這是最容易在真機才炸的一顆，已寫進 README 的設定表。
連線池 `spring.datasource.hikari.maximum-pool-size=5`（03 就設好，個位數）。

### 未完成（擋住的原因）

1. **hosted 專案當時 DNS NXDOMAIN**（Free 方案閒置暫停，票 04 就撞到了）。決策：**restore、維持 Free**
   ——7 天閒置會再暫停一次，票 11 驗收前要先確認它活著。
2. **本機沒有 flyctl、沒有 Fly 帳號**。app 名稱定案 **`hapeetrail`** ⇒ `fly.toml` 已寫死，
   openapi 的 placeholder `https://hapeetrail.fly.dev` 正好一致，**部署成功後那一行不用改**
   （最後一個 checkbox 因此變成「確認」而不是「修改」）。
3. 因此以下三項全部延到真機那一次：真 token 打 Fly 拿 200 空列表、Fly → Supabase pooler 的
   實機 IPv4 可達性、首位元組延遲實測。

### 下一次接手要做的事

使用者自己跑 `api/README.md` 的「一次性設定」（brew install flyctl → fly auth login →
`supabase db push` → SQL Editor 設角色密碼 → `fly apps create hapeetrail` → `fly secrets set`
→ `fly deploy`），跑完把網址交回來，這個 session 做四條驗證 curl ＋ 延遲實測 ＋ 把數字補進本票。

### 順手發現、沒動（要你裁決）

- DB 掛掉時 `/actuator/health` 會**卡 30 秒**才回，不是快速 503。Fly 的 5s check timeout 已經
  把它判成 unhealthy，行為正確，所以沒加 `spring.datasource.hikari.connection-timeout`
  ——加了會連正常請求的失敗等待一起改短，那是超出本票的決定。

## `/code-review` 兩軸（2026-08-25）

兩個獨立 subagent。**兩軸各自獨立抓到同一顆真的錯**，已修：

1. **`fly.toml` 的常駐設定原本是錯的**（兩軸都抓到）：`auto_stop_machines = false` ＋
   `min_machines_running = 1` ＋ `auto_start_machines = false`。查了 Fly 文件確認：
   `min_machines_running` 只在 auto_stop 為 `"stop"`／`"suspend"` 時生效 ⇒ 那行是死設定，
   而註解卻把「常駐一台」的功勞算在它頭上；更糟的是 auto_start 關掉之後，機器一旦因 OOM
   或 host maintenance 停掉，proxy **不會**再起它，只能人工救。
   已改成 `auto_stop_machines = "off"` ＋ `auto_start_machines = true`，刪掉 no-op 的
   `min_machines_running`（＝**沒有照抄票面那一行**，理由記在上面）。
   `auto_stop_machines` 順手改成現行的字串形式（布林是已棄用的寫法）。
2. **runbook 漏了「dashboard 開啟 Allow anonymous sign-ins」**（Spec 軸）：整個 App 靠匿名登入，
   沒開的話驗證那條 curl 會拿到 422 `anonymous_provider_disabled`，而不是 token。已補成步驟 3。
3. **`README` 的「本機跑容器」與設定表漏了 `…JWT_JWS_ALGORITHMS`**（Standards 軸）：它**有預設值
   RS256**，漏了不會啟動失敗、只會每個請求靜靜 401——正是這一票要拆的那顆雷。
   已補進 `docker run` 範例，設定表也拆成「缺了就啟動失敗的四個」與「有預設值但一定要帶的這一個」。
4. **首次部署要 `--ha=false`**（我自己查的，兩軸都沒問到）：`fly deploy` 的 `--ha` 預設 true，
   會多開一台待命機器，與票面「常駐一台」不符。已寫進 README。
5. `.dockerignore` 的 `.git` 是死行（建置 context 是 `api/`，底下沒有 `.git`）——刪掉。
6. **jar 的 glob 從 COPY 移到 build 段的 `mv`**（第三個查證 agent 抓到的，實測出來的）：
   `hapeetrail-*.jar` 現在只命中一個檔沒錯，但 **BuildKit 不執行「多來源 COPY 的目的地
   必須是目錄」那條規則**——legacy builder 會硬擋，BuildKit 靜靜挑字典序最後一個。
   意思是 `target/` 哪天多出第二個 `hapeetrail-*.jar`（多掛一個 classifier artifact，
   或 target 沒清乾淨），映像會**無聲**地包錯 jar，等執行時才以 missing main class 炸。
   改成 `mvn package && mv target/hapeetrail-*.jar /app.jar`，`COPY` 走固定路徑：
   多於一個檔時 `mv` 直接 exit 1，響亮地紅在建置階段。實測驗過（一個檔→過、
   兩個檔→exit 1、`.jar.original` 本來就不在 glob 內）。

**複核查證後確認正確、沒動的**：pooler 使用者名稱 `hapeetrail_api.<project-ref>`（Supavisor
的 `[USER].[PROJECT_REF]` 對自訂角色同樣適用）、session 5432／transaction 6543、
`POST /auth/v1/signup` `{}` 與 `access_token` 的位置、Boot 的
`exactlyOneAlgorithm()` 推論、`hapeetrail-*.jar` 的 glob 不會誤中 `.jar.original`
（Go 的 `filepath.Match`，且實測映像內 `/app.jar` 是 29.9MB 的 fat jar）。

### 複核提出、我沒動，要你裁決

- **`JWS_ALGORITHMS` 嚴格說是範圍外**（Spec 軸）：票 04 把它列在「回報但未修（要你決定）」，
  建議併進本票，但**你還沒點頭**。我做了（理由：本票就是第一次拿真 token，撞也是撞在這裡），
  請追認或叫我拿掉。
- **`docs/api/openapi.yaml:29` 的網址已與定案的 `hapeetrail` 相符，但 30–33 行給夥伴的
  「⚠️ 網址目前是 placeholder」警語還在**。真的部署成功、網址活著之前拿掉它是說謊，
  所以留著，最後一個 checkbox 也維持未勾——下次部署完就是刪那三行。
- 基礎映像用的是浮動 tag（`21-jdk`／`21-jre`），重建不是 bit-for-bit 可重現。MVP 不值得釘 digest，
  但知道一下。
