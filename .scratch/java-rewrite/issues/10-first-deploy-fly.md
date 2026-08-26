# 10 — 首次部署 Fly.io `nrt`

**What to build:** 服務以容器跑在東京、常駐不縮零、TLS 由平台；用**真正的 GoTrue** 匿名 token 打 Fly 上的 `GET /v1/me/notes` 得到 200 空列表——實證 JWKS 驗證、session pooler 的 IPv4 可達性、secrets 注入三件只有上了真機才會壞的事。早部署早發現，不等五支端點全做完。

**Blocked by:** 04

**Status:** 部分完成、**其餘刻意延後到票 11 前**——容器／設定／runbook 全部做完並驗過；
上真機那半段等「夥伴要開工」才做（決策與理由見下方〈2026-08-25 決定延後部署〉）

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

---

## 2026-08-25 決定延後部署（使用者裁決）

**決定**：不現在部署，以本機為開發環境；票 10 上真機的部分（`fly apps create`／`secrets`／`deploy`
＋四條驗證 curl ＋延遲實測）**併到票 11 驗收前**做。

**理由**（不是省事，是這一票的價值已經先被兌現掉大半）：

1. 票 10 存在的理由是「早發現三件只有上真機才會壞的事」，**今天從本機證掉兩件**（見下方查證表）。
   剩下只有 secrets 注入沒證，而那件事票 11 驗收前做一次就夠。
2. **iOS 夥伴尚未開工**（HANDOFF 記載 2026-08-25 確認），現在沒有人在等這個環境。
3. Fly 沒有免費方案，常駐 `shared-cpu-1x`／1GB 約 **US$5.70／月**；沒有人在用的期間開著是純燒錢。
   免費替代方案都有致命傷：Koyeb 只有 Frankfurt／Washington（無 Tokyo，跨洲打 Supabase）、
   Render 512MB／15 分鐘睡著／喚醒約一分鐘（JVM 冷啟）、Oracle Always Free 2026-06 從
   4 OCPU/24GB 砍半成 2/12 且 ARM 常 Out of Capacity、又是 VM 不是容器平台（TLS 要自己架）。
   **ADR-0011 白紙黑字決定 Fly.io `nrt`，換平台是 ADR 等級的決定，不在本票範圍。**
4. 本機開發完全不需要雲端：票 05–09 五支端點都由 Testcontainers 覆蓋。
5. 「本機當夥伴的測試環境」不可行——要嘛同區網（無 TLS、真機測試常不同網），
   要嘛開 tunnel，兩種都要**你的 Mac 一直開著**，他會在你睡覺時斷線。

**重新開工的觸發條件**：與 iOS 夥伴約定的開工日前幾天。那時走 `api/README.md` 的
「一次性設定」，其中步驟 1–5 今天已經做完（見下表），只剩 `fly` 那三步。

## 今天實際查證到的（下次不用重查）

| 項目 | 結果 | 怎麼證的 |
|---|---|---|
| hosted 專案 | ✅ 已 restore，`ACTIVE_HEALTHY`，GoTrue v2.195.0（restore 後約 105 秒才起來） | `supabase projects list -o json`、`/auth/v1/health` |
| **JWT 簽章金鑰** | ✅ **已是非對稱，演算法 `ES256`**（EC P-256，kid `5b42f887…`） | `GET /auth/v1/.well-known/jwks.json` |
| 匿名登入 | ✅ 已開啟（沒開會回 422 `anonymous_provider_disabled`） | `POST /auth/v1/signup` body `{}` 拿到 200 |
| 真 token 形狀 | ✅ `alg=ES256`、`aud=authenticated`、`role=authenticated`、`is_anonymous=true`、`sub` 存在 | 解 JWT header／payload |
| 準備 migration | ✅ `20260825000000_hapeetrail_api_role.sql` 已套上 hosted | `supabase db push` |
| `hapeetrail_api` 權限 | ✅ 七項全對：`rolcanlogin=true`、notes 只有 `INSERT,SELECT,UPDATE`（**無 DELETE**）、`public`／`extensions` USAGE 皆 true、policy `notes_api_all`＝`ALL roles=hapeetrail_api`、`notes` RLS 開著、**對 `auth.users` 無 SELECT** | SQL Editor 查 `pg_roles`／`information_schema.role_table_grants`／`pg_policies` |
| 角色密碼 | ✅ 已由使用者以一次性 SQL 設定（值只在他手上，不在 repo、不在我的 context） | — |
| **pooler host** | ✅ **`aws-0-ap-northeast-1.pooler.supabase.com`**（`aws-1-` 不是這個 tenant） | 故意用錯密碼探針：`aws-0` 回「password authentication failed **for user hapeetrail_api**」＝tenant 與角色都找到；`aws-1` 回「tenant/user **not found**」 |
| **pooler IPv4 可達** | ✅ 解析到 IPv4 `52.68.3.1`／`54.64.190.72`，從本機（Supabase 之外）連得到 TCP 並走到認證階段 | 同上探針 |
| **`<角色>.<專案ref>` 格式** | ✅ 對自訂角色有效——`hapeetrail_api.iwkuywlrggxolyoiyrui` 真的被 Supavisor 認出來 | 同上探針（錯誤訊息是「密碼錯」不是「找不到」） |
| `sslmode=require` | ✅ pooler 接受，一樣走到認證階段（Fly→Supabase 走公網，不讓它有機會靜默降級明文） | 同上探針加 `sslmode=require` |
| flyctl | ✅ 已安裝並登入（`fly auth whoami` = kevin861005@gmail.com） | — |
| Fly app／secrets／deploy | ⏸️ 未做——延後 | — |

**`ES256` 這一顆是今天最重要的發現**：Boot 的預設只認 RS256，若沒有 `fly.toml` 那行
`JWS_ALGORITHMS=RS256,ES256`，**每一顆真 token 都會 401**。先前標為「範圍外待追認」的那一項，
現在有硬證據證明它是必要設定，不是保險。

## 剩餘 checkbox 的現況

- [x] 容器（做完、本機驗過）
- [ ] Fly app 建在 `nrt` ⏸️ 延後
- [ ] secrets ⏸️ 延後（值已全部備齊，指令見 README，只差密碼由使用者貼上）
- [x] ~~查證並記錄：Fly → Supabase session pooler 連得上（IPv4）；連線池個位數~~
      **改以本機探針證實**（見上表三列）；「從 Fly 連得到」這半段隨部署一起延後。
      連線池 `maximum-pool-size=5` 已設。
- [ ] 真 token 打 Fly 拿 200 空列表 ⏸️ 延後（真 token 本身已拿到並驗過形狀）
- [ ] 首位元組延遲實測 ⏸️ 延後（沒機器可測）
- [x] 部署步驟寫成可重跑的說明（`api/README.md`，已填入今天查證到的實際值）
- [ ] OpenAPI `servers` placeholder ⏸️ 延後——網址 `https://hapeetrail.fly.dev` 與定案的 app 名稱
      一致，但**沒真的部署前那段「⚠️ 目前是 placeholder」的警語不能拿掉**，拿掉就是對夥伴說謊。

## 2026-08-26 裁決：Fly 延到上線前，測試期改本機＋Tailscale 供夥伴串接（使用者裁決）

推翻上一段「開工日前幾天上 Fly」。測試期環境＝Kevin 的 Mac 跑本票做好的容器（`api/README.md`「本機跑容器」），
以 `tailscale serve --bg 8080` 對 tailnet 提供 HTTPS（ts.net 憑證，iOS ATS 不需例外）；夥伴手機裝 Tailscale。
本票剩餘 6 個 ⏸️ 項全部移到「上線前」再做（README 順序表最後一列）。接受的限制：電腦要開著、只有 tailnet 內連得到、
驗收（票 11）打的是本機而非雲端；上線前上 Fly 時要再跑一次票 11 的 newman。
