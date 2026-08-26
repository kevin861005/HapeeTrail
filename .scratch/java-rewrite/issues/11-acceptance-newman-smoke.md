# 11 — 驗收：newman 對 Fly 全綠 ＋ 煙霧測試改打服務

**What to build:** 交付給 iOS 的 Postman collection 對著測試環境完整跑通整條流程（旅人 A 留便條與旅遊紀錄、翻頁、竄改游標；旅人 B 探索、撿別人的旅遊紀錄失敗、距離不足附距離、撿起、冪等重試、收藏）全綠；煙霧測試一分鐘內驗完「換一台機器才會壞」的事。這是 ADR-0011 定義的驗收：同一份契約語意在真機全綠。

**Blocked by:** 02, 09, 10

**Status:** 本機半段完成（2026-08-26）；**Fly 半段待部署**

- [x] ~~newman 對 Fly 上的服務~~ **對本機起的 Java 服務**：16 支斷言全綠、連跑 **30 輪 480 支斷言 0 失敗**（每輪隨機地點，T14 手法）
- [x] collection 斷言看 `status` 與 `code`、`details` 為物件；`details` 斷言不凍結非破壞性變更
- [x] hosted 煙霧測試改寫：六節 **33 項**，對本機服務＋本機 Supabase 乾跑全綠
- [x] 煙霧測試暫時保留 RPC 版的斷言（切換前 RPC 仍在）；切換後的斷言在 13 改
- [x] 語意文件 §10「契約外路徑」與實測一致（實測抓到兩處不符，已改文件）
- [x] 三份契約產出交叉比對：token 清單、狀態碼、鍵名三方逐字一致
- [ ] **newman 對 Fly 上的服務跑 30 輪**（等 `fly apps create` → `secrets` → `deploy --ha=false`）
- [ ] **煙霧測試對 hosted 跑一次**（同上）
- [ ] 通知夥伴測試環境可用（HANDOFF 記錄）

---

## 為什麼分兩半

開工時 `flyctl apps list` → **No apps found**：票 10 刻意延後的「上真機」那半段還沒補，
Fly 帳號也還沒綁付款方式。使用者裁決：**先做本機那半**，Fly 那一哩留給部署當天。
Supabase **維持 Free**（不升 Pro）——閒置 7 天會暫停，通知夥伴時要一併說明。

本機半段用的環境：`supabase start`（54321／54322，15 支 migration 全套上）＋
`./mvnw spring-boot:run` 以 `hapeetrail_api` 角色連本機 DB、JWKS 指本機 GoTrue
（**本機 GoTrue 也是 `ES256`**，與 hosted 一致，所以 `JWS_ALGORITHMS=RS256,ES256` 這條路徑真的被走到）。

## 交付物

| 檔 | 是什麼 |
|---|---|
| `docs/api/check-contract.py` | **新增**。三份契約產出的交叉比對，`exit 1` 才算數 |
| `docs/api/postman/trailstamp.postman_collection.json` | 四個錯誤示範的斷言硬化（+10 行） |
| `supabase/tests/hosted-smoke.sh` | 改寫為 v4／Java 版，六節 33 項 |
| `docs/api/notes.md` | §10 兩處與實測不符，已改 |

## 證據

### ① newman 30 輪（本機服務）

```
$ npx newman run docs/api/postman/trailstamp.postman_collection.json \
    -e docs/api/postman/local.postman_environment.json -n 30
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
⑥ Supabase 那一側     表三種 select= 變體與寫入面 403、六支內部 helper 403／404、anon 兩條 401；
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
| 表與任何 `select=` 變體、寫入面 401／403／404 | 三種 `select=` 變體與 POST 全 **403** | ✅ 相符 |
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

## 留給部署當天的三件事

1. `fly apps create hapeetrail` → `fly secrets set …` → `fly deploy --ha=false`（`api/README.md`）。
2. `npx newman run … -e docs/api/postman/hosted.postman_environment.json -n 30`
   ——**hosted 環境的 `apikey` 是空的，要先填 publishable key**。
   ⚠️ **匿名登入有速率限制**：`supabase/config.toml` 的 `anonymous_users = 30`（每小時每 IP），
   而 30 輪 × 2 次 signup ＝ **60 次**。本機沒擋（60 次 6.5 秒內全過），
   **hosted 很可能擋**——真被擋就先在 dashboard 把該上限調高，別以為是後端壞了。
3. `supabase/tests/hosted-smoke.sh iwkuywlrggxolyoiyrui <publishable-key>`（不必給第三個參數）。
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
