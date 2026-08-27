# 11 — 驗收：newman 對 Fly 全綠 ＋ 煙霧測試改打服務

**What to build:** 交付給 iOS 的 Postman collection 對著測試環境完整跑通整條流程（旅人 A 留便條與旅遊紀錄、翻頁、竄改游標；旅人 B 探索、撿別人的旅遊紀錄失敗、距離不足附距離、撿起、冪等重試、收藏）全綠；煙霧測試一分鐘內驗完「換一台機器才會壞」的事。這是 ADR-0011 定義的驗收：同一份契約語意在真機全綠。

**Blocked by:** 02, 09, 10

**Status:** 本機半段完成（2026-08-26）；**tailnet 半段完成（2026-08-27）**；Fly 留到上線前

- [x] ~~newman 對 Fly 上的服務~~ **對本機起的 Java 服務**：16 支斷言全綠、連跑 **30 輪 480 支斷言 0 失敗**（每輪隨機地點，T14 手法）
- [x] collection 斷言看 `status` 與 `code`、`details` 為物件；`details` 斷言不凍結非破壞性變更
- [x] hosted 煙霧測試改寫：六節 **33 項**，對本機服務＋本機 Supabase 乾跑全綠
- [x] 煙霧測試暫時保留 RPC 版的斷言（切換前 RPC 仍在）；切換後的斷言在 13 改
- [x] 語意文件 §10「契約外路徑」與實測一致（實測抓到兩處不符，已改文件）
- [x] 三份契約產出交叉比對：token 清單、狀態碼、鍵名三方逐字一致
- [x] **newman 對本機容器跑 ~~30~~ 15 輪**（容器連 hosted Supabase；打 tailnet MagicDNS 網址，
  最後一輪 12:31 **直接吃交付用的 environment 檔**、不覆寫 base_url，240/240）——
  輪數與網址協定的兩處變更見下方「2026-08-27 的兩個裁決」。
  ⚠️ **不是**「與夥伴同一條路」：跑的機器就是跑容器的那台，流量沒有真的過 WireGuard，見裁決 ③
- [x] **煙霧測試跑一次**：服務＝本機容器的 tailnet 網址、auth＝hosted Supabase（Free，已確認 ACTIVE 沒被暫停）→ **33 項全綠**
- [x] openapi `servers` 第一項改為 tailnet 網址（Fly 移到「上線前」註記）；三份契約產出同步（見下方交付物）
- [x] 通知夥伴用的資料（tailnet 加入方式＋網址＋apikey 取得方式）寫進 `.claude/HANDOFF.local.md`
      ——**訊息本身還沒發出去**，等與夥伴約好開工日

---

## 為什麼分兩半

開工時 `flyctl apps list` → **No apps found**：票 10 刻意延後的「上真機」那半段還沒補，
Fly 帳號也還沒綁付款方式。使用者裁決：**先做本機那半**，Fly 那一哩留給部署當天。
Supabase **維持 Free**（不升 Pro）——閒置 7 天會暫停，通知夥伴時要一併說明。

本機半段用的環境：`supabase start`（54321／54322，15 支 migration 全套上）＋
`./mvnw spring-boot:run` 以 `hapeetrail_api` 角色連本機 DB、JWKS 指本機 GoTrue
（**本機 GoTrue 也是 `ES256`**，與 hosted 一致，所以 `JWS_ALGORITHMS=RS256,ES256` 這條路徑真的被走到）。

## 2026-08-27 的兩個裁決（tailnet 半段）

票上寫的是「經 `tailscale serve` 的 **https** ts.net 網址、跑 **30** 輪」。兩處都撞到外部限制，
使用者當場裁決，**票的字面沒做到，做到的是它要證的東西**：

### ① https ts.net → `http://100.94.228.79:8080`（tailnet 直連）

`tailscale serve --bg 8080` **會卡住不回、serve config 保持空的**。原因用 `tailscale cert` 問出來：

```
$ tailscale cert --cert-file /dev/null --key-file /dev/null kevinchenmacbook-air.tailac7ba7.ts.net
500 Internal Server Error: your Tailscale account does not support getting TLS certs
```

tailnet（`painpoint-ai.com`）的 **HTTPS Certificates 開關是關的**，serve 拿不到憑證就一直等。
選項給了「去 admin console 開」與「不開憑證、改用 tailnet 內部位址」，**使用者選後者**——
不動帳號設定，代價是夥伴那端要自行處理 cleartext HTTP 的平台限制（openapi 已寫明，
但**不替 iOS 做實作決定**，CLAUDE.md 的分工）。傳輸仍由 tailnet 的 WireGuard 加密。

⚠️ **這裡我原本少走一步，複核抓出來後已改**：第一版直接改用裸 IP `100.94.228.79`，
理由寫「拿不到憑證所以不能用網址」——**錯的**。憑證開關只擋 **https**，MagicDNS 名在
http 下完全可用，實測 `http://kevinchenmacbook-air.tailac7ba7.ts.net:8080/actuator/health`
→ **200**、解析到 `100.94.228.79`。改用 MagicDNS 名之後：節點換 IP 不必改契約、
夥伴端要做 domain-based 的 cleartext 例外也才有域名可寫、tailnet suffix 自己寫在網址裡
（**`tailac7ba7.ts.net`**——夥伴照這個確認自己加對 tailnet）。三份契約產出用的都是這個網址。

（另：`tailscale status` 裡的 `macbook-pro.tail0cb7bc.ts.net` 是**別人共享進來的節點**，
suffix 不是本 tailnet 的，抄錯很容易——本 tailnet 一律 `tailac7ba7.ts.net`。）

### ② 30 輪 → 15 輪（hosted 匿名登入速率上限）

票上那條「hosted 很可能擋」的警語**實測成立**，而且比預期硬：

```
第一次（-n 30）：iterations 30／0 failed，assertions 480／143 failed
                 失敗全部源自 signup 429（每輪 2 次 signup ＝ 60 次 > 上限 30／小時／IP），
                 後半段每輪從「匿名登入（B）」開始整串垮掉
第二次（-n 15，配額已被上一次抽乾）：assertions 240／225 failed
                 30 次 signup 全部 429，第 1 輪就開始 401 連鎖
```

選項給了「去 dashboard 把上限調高到 150」與「改跑 15 輪」，**使用者選後者**——不動 hosted 設定。
15 輪 × 2 ＝ 30 次 signup，**恰好等於預設上限**，所以跑之前配額必須是滿的
（token bucket 以 30／小時回填，抽乾後要等約一小時）。

### ③ 一個**沒有**被證明的東西（誠實揭露）

newman 與煙霧測試都是**從跑容器的那台 Mac 自己**打自己的 tailnet 位址，
**不是**從第二個 tailnet 節點打進來的——所以「夥伴的裝置連得到」這件事**沒有被實測證明**。
已證明的只是路徑上沒有已知阻擋：

- macOS 應用程式防火牆 **disabled**（`socketfilterfw --getglobalstate` → State = 0）
- 容器 port 綁在 `0.0.0.0:8080`（`docker ps` 的 `0.0.0.0:8080->8080/tcp`）
- tailnet 裡 `kevinchenmac-mini` 線上，但**沒有節點開 Tailscale SSH**，
  所以無法從別台跑一次 curl 來收掉這個缺口。

**夥伴第一次連線時要當面確認**；連不到先查 tailnet 成員資格與 ACL，別先懷疑後端。

**這兩個裁決都是「不動外部帳號設定」的同一個取捨**：測試期的環境保持零設定變更，
成本記在文件與輪數上。上線前搬到 Fly（https）時兩個限制都自動消失。

## 交付物

| 檔 | 是什麼 |
|---|---|
| `docs/api/check-contract.py` | **新增**。三份契約產出的交叉比對，`exit 1` 才算數 |
| `docs/api/postman/hapeetrail.postman_collection.json` | 四個錯誤示範的斷言硬化（+10 行） |
| `supabase/tests/hosted-smoke.sh` | 改寫為 v4／Java 版，六節 33 項 |
| `docs/api/notes.md` | §10 兩處與實測不符，已改 |

**2026-08-27 追加（tailnet 半段，三份契約產出同步）**：

| 檔 | 改了什麼 |
|---|---|
| `docs/api/openapi.yaml` | `servers` 第一項 `https://hapeetrail.fly.dev` → `http://100.94.228.79:8080`，描述寫明「要先加入 tailnet」「是 http 不是 https」「上線前換 Fly」 |
| `docs/api/postman/hapeetrail-hosted.postman_environment.json` | `base_url` 同上；`name` 改 `HapeeTrail Test (tailnet 服務 ＋ hosted auth)`（原名「Hosted (Tokyo)」現在會誤導：auth 在 hosted，服務在 tailnet） |
| `docs/api/notes.md` | §0 的 `$BASE` 範例值同步 |

三處改完 `check-contract.py` 仍 exit 0、`redocly lint` 仍 valid（1 個既有 warning）。
⚠️ **但「三方 base URL 一致」不是這兩支工具驗的**——`check-contract.py` 只比 token／狀態碼／
鍵名，`servers` 不在它的掃描範圍。base URL 的一致性目前**只有人工比對**（複核那一軸
獨立比過一次，三處逐字相同）。要讓它進工具是另一張票的事，本票沒做。
**`supabase/tests/hosted-smoke.sh` 的預設值刻意沒動**（仍是 `https://hapeetrail.fly.dev`）：
它是部署當天 2 參數用法的預設，測試期一律給第 3 個參數。

## 證據

### ① newman 30 輪（本機服務）

```
$ npx newman run docs/api/postman/hapeetrail.postman_collection.json \
    -e docs/api/postman/hapeetrail-local.postman_environment.json -n 30
iterations 30／0 failed   requests 480／0   assertions 480／0   duration 6.5s
```

16 支斷言 × 30 輪。每輪由「匿名登入（A）」的 pre-request script 換一個隨機地點
（緯度 −60..60、經度 −180..180，位移只動緯度），所以上一輪殘留的便條永遠落在本輪 100m 之外。

### ② collection 斷言硬化（+10 行，四個錯誤示範）

改前只有 `invalid_cursor` 看 body 的 `status`，其餘三個只看 `code`。現在四個都：

- `pm.expect(e.status).to.eql(pm.response.code)` —— **狀態碼與 body 的 `status` 是同一個數字的兩種表達**，
  只驗一邊就漏掉它們漂移的那天；
- `pm.expect(e.status).to.eql(<literal>)` —— 再釘死那個數字本身；
- `too_far` 的 `details`：維持 `to.be.an('object')` ＋ 新增 `to.have.property('distanceM')`，
  **刻意不用 `have.all.keys`**——往 `details` 加鍵是契約明說的非破壞性變更（§8），
  凍結整組鍵名會讓「加一個鍵」變成假紅。註解寫在斷言旁邊。

### ③ 煙霧測試改寫（33 項，全綠）

用法多一個可選參數：`hosted-smoke.sh <project-ref|url> <publishable-key> [service-base-url]`，
service 預設 `https://hapeetrail.fly.dev`；ref 給完整網址就能對本機乾跑。**只用 client 憑證，
沒有任何管理金鑰。**

```
$ supabase/tests/hosted-smoke.sh http://127.0.0.1:54321 sb_publishable_… http://127.0.0.1:8080
① 匿名登入            旅人 A／B 各一顆 token（撿取要第二個身分）
② 五支業務端點        Note 9 鍵、座標巢狀、trim、代號原樣、expiresAt；NearbyHint 7 鍵、
                      距離約 30m、pickable；130m 外 403 too_far 附 details.distanceM≈130；
                      30m 內 200 撿到；兩支列表 envelope；收藏含剛撿的那張
③ Unicode 空白        全形空白 → 400 content_empty
                      ⚠️ v4 起 trim 在 Java，**這條不再驗資料庫 locale**——C locale
                      那個舊風險隨 RPC 一起走掉了，留著只驗「映像帶的是對的字元集」
④ JWT fail-closed     無 token 四支全 401（GET 兩支再驗 code=not_authenticated）、簽章被改過的
                      token 401、**publishable key 當 Bearer 401、只帶 apikey 401**（anon 身分）
⑤ /actuator/health    200 status=UP，不帶任何認證
⑥ Supabase 那一側     表兩種 select= 變體與寫入面 403、六支內部 helper 403／404、anon 兩條 401；
                      **過渡期正面斷言 v3.3 五支契約 RPC 仍活著**（票 13 把這一組翻面）
── 通過 33 項，失敗 0 項
```

每次跑換隨機地點，且**建立的那張便條在同一次跑裡就被旅人 B 撿走**，不留垃圾。

**票 13 要翻面的那一組**：⑥ 末尾正面斷言 v3.3 五支契約 RPC **仍可達**
（`my_notes`／`my_collection`／`nearby_notes`／`drop_note` 帶合法參數 → 200；
`pickup_note` 拿隨機 uuid → 400 `note_not_found`，用「業務錯誤 ≠ 權限錯誤」證明它在）。
切換後整組改成斷言五支全部 401／403／404。註解就寫在那裡。

### ④ 三份契約交叉比對（`docs/api/check-contract.py`）

```
① token 清單（openapi enum ↔ notes.md §8）        14 個逐字一致
② token → 狀態碼                                   14 組逐項一致
③ 資料形狀鍵名（openapi schema ↔ notes.md 範例）   Note 9／NearbyHint 7／NotePage 2
④ collection 的斷言                                 4 個 token 的狀態碼配對一致、
                                                    三組 have.all.keys 等於對應 schema
✅ 三份契約產出一致
```

比對過程本身抓到一件事：`invalid_cursor` 在 openapi 走
`components/responses/ListBadRequest` 的 `$ref`，第一版掃描器沒跟著 `$ref` 走，
誤報成「openapi 沒有這個 token 的狀態碼」。**文件是對的，掃描器錯了**——已修成跟著 `$ref` 解。

**突變測試五個，全部被殺**（不然這支就是個很有說服力的綠燈）：

| 突變 | 結果 |
|---|---|
| notes.md 刪掉 `own_note` 那列 | ✅ 紅 |
| notes.md 把 `too_far` 改成 400 | ✅ 紅 |
| openapi 的 `Note` 多一個鍵 | ✅ 紅 |
| collection 把 `too_far` 的狀態碼斷言改 400 | ✅ 紅 |
| collection 的 `Note` `have.all.keys` 少一鍵 | ✅ 紅 |

煙霧測試也突變過三個：服務網址指到沒開的 port → 紅 13 項；`Note` 9 鍵斷言改 8 鍵 → 紅 5 項；
把「五支 RPC 仍活著」的期望值改成 403（模擬 RPC 被提早收掉）→ 紅 4 項。

### ⑤ §10「契約外路徑」對實測（**抓到兩處不符，改的是文件**）

對 hosted（`iwkuywlrggxolyoiyrui`，client 憑證＋匿名 token）與本機服務逐條打：

| §10 的說法 | 實測 | 判定 |
|---|---|---|
| 表與任何 `select=` 變體、寫入面 401／403／404 | 兩種 `select=` 變體與 POST 全 **403** | ✅ 相符 |
| 內部 helper 不可達 | 六支全 **403／404** | ✅ 相符 |
| 「**以及任何 RPC**，一律 401／403／404」 | `my_notes`／`my_collection` 送 `{}` 回 **200 帶資料**（其餘三支 404 只是因為缺必填參數，函式仍在） | ❌ **與同一節末的「過渡期聲明」自相矛盾** |
| `/actuator/health`「只回 `{"status":"UP"}`」 | 實際是 `{"groups":["liveness","readiness"],"status":"UP"}` | ❌ **不符** |
| 其餘 Actuator 端點沒有開 | `env`／`beans`／`metrics`／`configprops` 帶合法 token 也 **404**；`/actuator` 索引與 `health/liveness`／`readiness` 帶 token 回 200，只有 `status` | ⚠️ 大致相符，但漏講了 health 的兩個 group 子路徑 |
| 日誌不記座標與便條內容 | 跑完 500+ 請求後服務日誌共 26 行，`latitude`／`longitude`／`coordinate`／便條內文 **零命中** | ✅ 相符 |

改動（`docs/api/notes.md` §10，兩處）：

1. 「以及任何 RPC」→「以及所有內部 helper 函式……**v3.3 的五支契約 RPC 是切換日前的唯一例外**，
   見本節末的過渡期聲明」。原句讀起來會讓 iOS 以為 RPC 已經死了，而它們還活著。
2. health 那句改成寫出實際 body，並明講**「body 的內容不屬於契約，只保證 200 與 `status`」**，
   順帶補上兩個 group 子路徑與「其餘端點帶 token 也是 404」。

### ⑥ 迴歸

`cd api && ./mvnw test` → **174 支綠**（surefire 目錄裡另有 `ExplainHarness`／`ProbeTest`
兩份 8/25 的殘留報告，不是這次跑的，別把總數當成 176）。

### ⑦ tailnet 半段的兩支實測（2026-08-27，容器 ↔ hosted Supabase）

環境：`docker run -d --name hapeetrail-api`（映像 `hapeetrail-api:local`，建於 2026-08-27 01:26 UTC，
晚於最後一次動 `api/src` 的 commit `795568c`）＝ **DB 走 hosted session pooler
（`hapeetrail_api.iwkuywlrggxolyoiyrui`）、JWKS 指 hosted GoTrue**；
newman／煙霧測試打 `http://kevinchenmacbook-air.tailac7ba7.ts.net:8080`。
Supabase 專案狀態 ACTIVE（沒被暫停）。

⚠️ **兩支不能在同一小時內跑**：newman 15 輪要 30 次 signup ＝ 剛好吃光整桶配額，
煙霧測試自己還要 2 次。實際執行時間就是被這件事排開的——
煙霧測試 **10:14**（裸 IP 版）與 **11:30**（MagicDNS 版）、
newman 15 輪 **11:20**（裸 IP 版）與 **12:31**（MagicDNS 版）。
四次之間的間隔都是被配額逼出來的，順序照抄成「連著跑」一定紅。

**newman 15 輪：**

跑了兩輪，第二輪是複核之後的補驗：

```
（11:20，裸 IP 版——當時契約產出裡還是 IP）
$ npx newman run … -e …hapeetrail-hosted.postman_environment.json \
    --env-var base_url=http://100.94.228.79:8080 \
    --env-var apikey=sb_publishable_… -n 15
iterations 15／0 failed   requests 240／0   assertions 240／0   duration 27.1s
average response time 99ms [min 4ms, max 1021ms, s.d. 100ms]

（12:31，MagicDNS 版——**刻意不帶 `--env-var base_url`**，直接吃 environment 檔裡的值，
  驗的就是交給夥伴的那份檔本身）
$ npx newman run docs/api/postman/hapeetrail.postman_collection.json \
    -e docs/api/postman/hapeetrail-hosted.postman_environment.json \
    --env-var apikey=sb_publishable_… -n 15
iterations 15／0 failed   requests 240／0   assertions 240／0   duration 25.9s
average response time 94ms [min 4ms, max 1005ms, s.d. 97ms]
```

第二輪把「契約字面與實測不同址」這個缺口收掉了：**交付檔案原封不動跑，240 支斷言 0 失敗**。
apikey 一律用 `--env-var` 帶進去，沒有落進 git（hosted environment 檔的 `apikey` 仍刻意留空）。

16 支斷言 × 15 輪。**apikey 用 `--env-var` 帶進去，沒有落進 git**
（hosted environment 檔的 `apikey` 仍刻意留空）。
對照本機半段的 6.5s／480 支：這次慢是因為 auth 與 DB 都在東京 hosted，`max 1021ms` 是第一顆 signup 的冷啟。

**煙霧測試：**

```
$ supabase/tests/hosted-smoke.sh iwkuywlrggxolyoiyrui sb_publishable_… \
    http://kevinchenmacbook-air.tailac7ba7.ts.net:8080
── 服務 http://kevinchenmacbook-air.tailac7ba7.ts.net:8080
── Supabase https://iwkuywlrggxolyoiyrui.supabase.co
① 匿名登入 2 ② 五支業務端點 6 ③ Unicode 空白 1 ④ JWT fail-closed 7
⑤ /actuator/health 1 ⑥ Supabase 那一側 16（含過渡期五支 RPC 仍活著）
── 通過 33 項，失敗 0 項
```

**⑥ 那 16 項對的是 hosted 的 PostgREST**，不是本機的——ADR-0007 的「client 對 `/rest/v1/*` 零權限」
這次是在**真的那台**上驗掉的（六支 helper 403／404、表與寫入面全 403、anon 兩條 401）。
`too_far` 的 `details.distanceM` 實測 129，走的是 hosted 的 PostGIS geography。

### ⑧ 迴歸（2026-08-27 重跑）

`cd api && ./mvnw test` → **174 支綠、0 失敗、0 錯誤**（本次 fresh 的 8 份 surefire 報告加總；
`ExplainHarness`／`ProbeTest` 兩份是 40 小時前的殘留，已排除，別把總數當 176）。
本次 session **沒有動任何 Java 程式碼**，這支只是確認契約文件的改動沒有牽動實作。

## 留給部署當天的三件事

1. `fly apps create hapeetrail` → `fly secrets set …` → `fly deploy --ha=false`（`api/README.md`）。
2. `npx newman run … -e docs/api/postman/hapeetrail-hosted.postman_environment.json -n 30`
   ——**hosted 環境的 `apikey` 是空的，要先填 publishable key**。
   ⚠️ **匿名登入速率限制已實測會擋**（2026-08-27）：hosted 是 **30 次／小時／IP**，
   而 30 輪 × 2 次 signup ＝ **60 次** ⇒ 後半段 signup 全部 429、每輪從「匿名登入（B）」整串垮掉
   （本機不擋，60 次 6.5 秒內全過，所以本機半段看不出來）。
   **抽乾後要等約一小時才回填得回 30 個**（token bucket）。兩條路：
   dashboard → Authentication → Rate Limits 把 Anonymous sign-ins 調高，或跑 `-n 15`
   （＝30 次 signup，恰好卡在上限，跑之前配額必須是滿的）。**別以為是後端壞了。**
3. `supabase/tests/hosted-smoke.sh iwkuywlrggxolyoiyrui <publishable-key>`（不必給第三個參數）。
   ⚠️ **第 2 與第 3 點不能連著跑**：`-n 15` 已吃光整桶 30 個配額，煙霧測試自己還要 2 次
   ⇒ 中間要等回填，或先把上限調高（見第 2 點）。上限調高後兩支才排得進同一小時。
   ⚠️ `api/README.md` 的取 key 指令 `… | grep publishable | awk '{print $4}'` **已經失效**：
   現在那一列的 NAME 欄是 `default` 不是 `publishable`，抓不到值。
   改用 `grep -o 'sb_publishable_[A-Za-z0-9_-]*'`。

## 順帶發現（**未動，等裁決**）

1. **401 的 Content-Type 帶了 `;charset=ISO-8859-1`**，其他所有錯誤都是乾淨的
   `application/problem+json`。401 走的是 Spring Security 的 entry point，不經 `ApiErrors`。
   body 全是 ASCII 所以沒有亂碼風險，但對 content-type 做嚴格比對的 client 會炸。
2. **collection 沒有一支驗「型別錯誤的 400 **沒有** `code`」**——而那正是 §2 說的唯一判斷閘門。
   它若哪天長出 `code`，iOS 的整個錯誤分支會靜默走錯，目前沒有任何斷言擋得住。
   （票上的情境清單沒有這一項，所以沒加。）

## 獨立複核（`/code-review` 兩軸，未繼承實作假設）

兩個 subagent 各自跑完，**共抓到 4 個實質缺陷，全部已修並逐一複現**。
這一節就是「驗證不球員兼裁判」的落檔——上面那些綠燈在複核之前有兩個是假的。

### Spec 軸

**（必修，已修）票上「煙霧測試暫時保留 RPC 版的斷言」被我做反了。**
改寫時把舊版對 `drop_note`／`nearby_notes`／`my_notes` 的斷言整組刪掉，還在註解裡
寫「這裡**不**斷言它們的狀態」——結果五支契約 RPC 在煙霧測試裡零覆蓋，
**票 13 的「改成正面斷言」也就沒東西可改**。已補回 ⑥ 末尾的五條正面斷言。

其餘：Fly 兩項的分半揭露判定合格；證據數字逐項複現無誤（480/30 自洽、174 綠扣掉
兩份 8/25 殘留報告屬實、§10 交叉引用存在）；指出「五節」實為六節（已改）。

### Standards 軸

**（必修，已修）三個會讓綠燈失去意義的正確性缺陷：**

1. **`check-contract.py` 有一條恆真的斷言。** `tokens` 先被 `if t in TOKENS` 濾過，
   後面再問「有沒有 enum 以外的 token」必然是空集合——**collection 裡打錯字的 token
   是被靜默丟掉，不是被抓到**。已拿掉過濾；補突變：把 `too_far` 改成 `typo_token`，
   改前恆綠、改後紅。
2. **`jget` 對 JSON `null` 回傳字串 `"None"`，而 `"None"` 對 `[ -n ]` 為真。**
   撿取那條 `[ -n "$(jget pickedUpAt)" ] && [ -n "$(jget content)" ]`
   **在兩者都是 null 時會假綠**。已改成 `print('' if v is None else v)`，並複現封閉。
3. **四個 `python3 -c "…" 2>/dev/null` 把 assert 訊息整個吃掉**，
   「距離算出來是 87m」這種診斷永遠印不出來，連 SyntaxError 都會偽裝成「形狀不對」。
   已移除三處（`jget` 與取 `details.distanceM` 那兩處保留，它們要的就是靜默）。
   **修完立刻自證**：下一次跑就吐出 `FileNotFoundError: '$BODY'`，
   抓到 heredoc 是 `<<'PY'`（quoted）所以 `$BODY` 沒展開——舊版只會說「形狀不對」。

**（順手一起修）** `curl -s -o` 在連不上時（`http_code` 000）不寫檔，
`bad` 於是印出**上一個請求**的 body；固定 `/tmp` 檔名也會讓兩個人同時跑互相覆蓋。
已改 `mktemp` ＋ `trap` ＋ 每次打之前先清空。`code()` 與 `jget code` 撞名（一個是 HTTP
狀態碼、一個是錯誤 token）已改名 `http()`。`check()` 對布林呼叫會印
「openapi：True／另一份：False」，已改成印出真正找到的鍵組。

### 複核提出、我**沒有**照做的兩點（附理由）

- **「`check-contract.py` 是範圍外的常駐工具，票只要一次性比對，且全 repo 沒人引用它。」**
  保留。理由：CLAUDE.md 要求「三份契約產出任何介面變更必須同步更新」，
  一次性比對驗不了「下次」；票 13 會動契約，那時要再跑一次。
  house 先例是 `docs/api/build-doc.py` 同樣只從自己的 docstring 被提到。
  相依（PyYAML）已補進 docstring。
- **「§10 新增『body 的內容不屬於契約』超出『與實測一致』。」**
  保留，但**這是契約語意決策，需要你追認**。不加這句的話，把實際 body
  `{"groups":[…],"status":"UP"}` 寫進文件就等於**把 Spring Boot 的實作細節凍結成契約**
  ——那比原本的錯誤更糟。
