# 04 — 第一顆子彈：JWT 驗證 ＋ `hapeetrail_api` 準備 migration ＋ `GET /v1/me/notes` 回空列表

**What to build:** 一個剛匿名登入的旅人拿 GoTrue 的 token 打 `GET /v1/me/notes`，得到 `{"items":[],"nextCursor":null}`——請求穿過 Spring Security 的 JWT 驗證、以 `hapeetrail_api` 角色下 SQL、過 RLS policy、回 v4 envelope。同時五種壞 token 全部 401。這是第一條穿透所有層的路徑，之後每支端點都是在它上面加規則。

**Blocked by:** 03

**Status:** done（2026-08-25）——一項卡在外部：JWT 簽章方式查不了（hosted 專案不可達，見下）

- [!] **先查證**專案 JWT 簽章方式：若是 legacy HS256 共享密鑰，在 dashboard 切換到非對稱簽章金鑰並記錄；服務只用 JWKS，共享密鑰永不進 Spring
      **查不了，需要你動手**：`iwkuywlrggxolyoiyrui.supabase.co` DNS 回 **NXDOMAIN**（不是連線被拒，是主機名不存在）
      ——Free 專案自 2026-07-29 起閒置近一個月，幾乎確定已被暫停。實作端不受影響（見下方「這一項為什麼不擋施工」）。
- [x] 新增 Supabase migration「準備」：建登入角色 `hapeetrail_api`（密碼不寫在 migration，以一次性手動 SQL 設定，值只在部署平台 secrets）；`public`、`extensions` schema USAGE；`public.notes` 的 SELECT／INSERT／UPDATE（無 DELETE）；不動 `auth.users` 權限
- [x] 同一支 migration 新增只給 `hapeetrail_api` 的全列 permissive policy（讀寫皆放行）；RLS 維持啟用、`notes_select_own` 保留休眠
- [x] migration 在 Testcontainers 套用成功，且與 RPC 版並存無害（既有 `notes.test.sql` 在本機 `supabase db reset` 後仍 `ALL TESTS PASSED`）
- [x] 服務以 `hapeetrail_api` 連線（Testcontainers 內同樣建這個角色）；HikariCP 池大小設個位數（沿用 03 的 5）
- [x] JWT：驗簽章、過期、`aud` 必須為 `authenticated`、必須有 `sub`；`sub` 即使用者身分；`is_anonymous` 不影響任何行為
- [x] 測試 profile 以本機 RSA 公鑰取代 JWKS；測試工具能以私鑰鑄 token 指定 `sub`／`aud`／過期
- [x] 五種變形各一條斷言 **401 ＋ problem+json `code: not_authenticated`**：無 header、簽章不符、過期、缺 `sub`、`aud` 不符
- [x] 合法 token → `GET /v1/me/notes` 200 `{"items":[],"nextCursor":null}`（items 是空陣列不是 null）
- [x] `/actuator/health` 不需 token；其餘路徑無 token 一律 401
- [x] 全程紅→綠：先寫 401 與空列表的測試看它們紅（第一次跑 7/7 紅，全是 403——見下方「403 的坑」）

---

## 結果

### 落檔

| 檔 | 是什麼 |
|---|---|
| `supabase/migrations/20260825000000_hapeetrail_api_role.sql` | 準備 migration：角色、權限、`notes_api_all` policy |
| `api/src/main/java/com/kevin/hapeetrail/SecurityConfig.java` | resource server、problem+json 的 401、`sub` 必填 |
| `api/src/main/java/com/kevin/hapeetrail/NotesController.java` | `GET /v1/me/notes` ＋ `NotePage` envelope |
| `api/src/main/resources/application.properties` | `…jwt.audiences=authenticated`（非機密，進 repo） |
| `api/src/test/java/com/kevin/hapeetrail/TestJwt.java` | 自簽 token 工具（兩把 RSA：服務認得的／不認得的） |
| `api/src/test/java/com/kevin/hapeetrail/AuthTest.java` | 空列表 ＋ 五種變形 ＋ 「其餘路徑一律 401」 |
| `api/src/test/java/com/kevin/hapeetrail/SupabaseDbTest.java` | 改以 `hapeetrail_api` 連線、餵公鑰、加 `admin()` |
| `api/src/test/java/com/kevin/hapeetrail/SmokeTest.java` | 既有三條改用 `admin()`；新增最小權限那條 |

### JWT 查證：**做不到，需要你在 dashboard 處理**

`dig`／`curl` 對 `iwkuywlrggxolyoiyrui.supabase.co` 都是 **NXDOMAIN**（同一時間 google.com 200，
所以不是本機沒網路）。DNS 層就沒有這台主機 ⇒ 專案被暫停或刪除；`docs/TASKS.md` 記的最後一次使用是
2026-07-29，Free 方案閒置 7 天暫停，時間對得上。

**這一項為什麼不擋施工**：spec 已經把結論寫死成「服務只用 JWKS，共享密鑰永不進 Spring」，
所以無論查出來是 HS256 還是非對稱，**Java 這邊的程式碼一個字都不會不同**——差別只在
「你要不要去 dashboard 按那個切換鈕」。實作因此照做完，把查證留成你的動作：

1. 到 Supabase dashboard 把專案 restore（Free 專案暫停後要手動恢復）。
2. Project Settings → JWT Keys：若顯示 legacy HS256 shared secret，切換到非對稱簽章金鑰（Current key 改成 ECC 或 RSA）。
3. `curl https://iwkuywlrggxolyoiyrui.supabase.co/auth/v1/.well-known/jwks.json` 應回一組公鑰，把 URL 記進票 10 的 secrets。
4. 順帶：README 的施工順序表已把「升 Supabase Pro」排在票 11 之前——這次的暫停就是它要解決的問題，可以考慮提前。

⚠️ 如果切換後 JWKS 只出 **ES256**（Supabase 新專案的預設是 ECC P-256），
`spring.security.oauth2.resourceserver.jwt.jws-algorithms` 要加上 `ES256`（預設只認 RS256）。
測試端用 RSA／RS256 自簽，不受影響，但票 10 對真 GoTrue token 的第一次部署會撞到——先記在這裡。

### 403 的坑（紅燈的形狀不是預期的那個）

第一次跑測試，七條全紅，但全部是 **403 而不是 401**。原因：`anyRequest().authenticated()`
在沒有任何 configurer 註冊 entry point 時，`ExceptionHandlingConfigurer` 的預設是
`Http403ForbiddenEntryPoint`。加上 `oauth2ResourceServer(...)` 之後它才有一個 mapping，
而 `ExceptionHandlingConfigurer` 在**只有一個** mapping 時會直接拿它當全域預設值
——於是「完全沒帶 header」的請求也走到我們的 problem+json 401。

原本另外寫了一行 `.exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))`
保險，突變測試證明它拿掉之後**全綠**（＝那行是死碼），已刪。
`everythingElseNeedsAToken` 是它的看門狗：哪天多了第二個註冊 entry point 的 configurer
（例如給 actuator 加 httpBasic），mapping 變兩個、預設值換人，那條會立刻紅。

### `sub` 必填要自己寫

Spring 的預設 validator 管簽章與過期，`aud` 由 Boot 的
`spring.security.oauth2.resourceserver.jwt.audiences` 屬性管（查過 Boot 4.1 的
`JwtDecoderConfiguration` bytecode：`public-key-location` 與 `jwk-set-uri` 兩條路徑都會
套上 audience validator，所以測試與正式環境走同一份驗證）。**只有 `sub` 沒人管**。

Boot 是用 `setJwtValidator` 蓋掉整份 validator 的，從外面加 validator 沒有乾淨的掛點，
所以改在 `jwtAuthenticationConverter` 裡擋：`sub` 空的就丟 `InvalidBearerTokenException`
（是 `AuthenticationException`）⇒ 走同一個 entry point ⇒ 同一個 401 body。
六行，不必自己組 `JwtDecoder`，也就不必在測試與正式環境維護兩套 decoder 組法。

### 測試怎麼拿到金鑰（沒有 test profile 檔，沿用 03 的做法）

`TestJwt` 在 JVM 啟動時生兩把 RSA-2048：`SIGNING_KEY`（公鑰寫成 PEM 暫存檔，由
`SupabaseDbTest` 的 `@DynamicPropertySource` 餵給 `…jwt.public-key-location`）與
`FOREIGN_KEY`（「簽章不符」那條變形用）。**repo 裡沒有任何金鑰檔**，測試不碰網路。
token 用 nimbus-jose-jwt 直接鑄（resource server starter 已經帶進來，不是新依賴），
header 帶 `typ: JWT`（Spring 7 的預設 validator 會看它），claims 帶 `is_anonymous`
——服務不看它，放著是為了讓 token 長得跟 GoTrue 的一樣。

### 測試連線角色換成 `hapeetrail_api`（03 複核記的那筆帳）

`SupabaseDbTest` 現在餵 `hapeetrail_api` 給 datasource（migration 只建角色不設密碼，
測試在套完 migration 後用容器內的 psql `alter role … password` 補一個本機密碼）。
測試自己要做的資料佈置（`auth.users` 的列——服務角色刻意沒有權限）走新加的
`admin()`：超級使用者的 `JdbcClient`，只給測試用。`SmokeTest` 既有三條斷言改用它。

新增 `serviceConnectsAsTheLeastPrivilegedRole`：用**服務自己的連線**問
`current_user`、`rolsuper`／`rolbypassrls`、`public.notes` 的四個 privilege、
`auth` schema 的 usage、`notes` 的 `relrowsecurity`。它同時證明「服務真的是以這個角色連上的」。

### 突變測試（七輪，每輪改一處看它紅）

| # | 動了什麼 | 結果 |
|---|---|---|
| 1 | 刪 `.exceptionHandling(...entryPoint)` | **全綠** ⇒ 死碼，已刪（見上） |
| 2 | `jwtAuthenticationConverter` 換回 `withDefaults()` | 紅：`缺 sub` |
| 3 | 註解掉 `jwt.audiences=authenticated` | 紅：`aud 不符` |
| 4 | migration 拿掉 `select` grant | 紅兩條：`validTokenGetsAnEmptyPage`（500）＋最小權限 |
| 5 | migration 多給 `delete` | 紅：最小權限 |
| 6 | datasource 換回 `supabase_admin` | 紅：最小權限 |
| 7 | 拿掉 `notes_api_all` policy | **全綠**（見下，誠實記帳） |

第 4 輪特別重要：它證明那句 200 不是憑空回的空陣列，**真的下到資料庫**——
沒有 SELECT 權限就 500。

### 誠實記帳：`notes_api_all` policy 目前沒有測試守得住

RLS 開著、該角色沒有 policy 時，`select` 是**靜默回 0 列**而不是報錯，
而這一票的斷言就是「0 列」⇒ 拿掉 policy 照樣綠（突變 7）。
要讓它變成實心，得先有資料能讀，也就是要有 INSERT ——**那是票 05 的第一條測試**
（留一張便條再讀回來，policy 一沒就 `new row violates row-level security policy`）。
在 05 之前，這條 policy 是靠 code review 而不是靠測試守著的。

### `supabase db reset` 與角色

先寫了 `do $$ if not exists … create role` 的 guard，理由是「角色是 cluster 級的，
reset 不會刪它」。**實測推翻**：`supabase db reset`（CLI 2.105.0）會重建整個 db 容器，
角色跟著消失——拿掉 guard 連跑三次 reset 都沒撞名。guard 因此刪掉，只留一句 `create role`。

`supabase db reset` ＋ `psql -f supabase/tests/notes.test.sql` → **`ALL TESTS PASSED`**
（14＋1 支 migration 全套上，準備 migration 與 RPC 版並存無害）。

### 執行時間

`./mvnw clean test`：**11.7 秒**（11 條測試；容器起到 ready 約 1.5 秒）。

### 沒做、要留給誰

- **03 交代下來的「INFO 記路徑／狀態碼／耗時」那半**：**沒做**。04 的驗收清單裡沒有這一條，
  而 Fly.io 的 proxy 本來就會記路徑／狀態碼／耗時——自己再寫一個 filter 是重複的。
  建議直接判定不需要；真要在應用層記（例如要帶錯誤 `code`），另開一條 ticket，別夾帶。
- **`WWW-Authenticate` header**：401 沒有帶。RFC 6750 說 SHOULD，但 v4 契約沒有提，
  iOS 也不需要它來分流（401 一律走刷新流程）。要加是契約變更。
- **`sub` 不是合法 uuid 的 token**：`?::uuid` 會炸成 500。它是 GoTrue 簽出來的 claim，
  結構上不可能不是 uuid；沒有為它加一條路徑。

### 複核（`/code-review` 兩軸，2026-08-25）

Standards 與 Spec 兩個獨立 subagent。**已依複核修掉四處**（都是這一票自己的瑕疵）：

1. **「過期」那條測試卡在時鐘偏移邊界**（Spec 軸抓到，最實在的一顆）：`exp = now - 60s`，
   而 `JwtTimestampValidator` 的預設 `MAX_CLOCK_SKEW` 正好是 60 秒，判斷式是
   `now.isAfter(exp + skew)`——等於在比「鑄 token 到送出之間過了幾毫秒」。
   改成 `now - 3600s`。原本會綠，但綠得沒有道理。
2. `SupabaseDbTest.psql()` 其實跑的是 `bash -c`，第一個呼叫端傳的是 for 迴圈不是 psql → 更名 `exec()`。
3. `admin()` 每次呼叫都新開一個 `DriverManagerDataSource`（與連線）→ 改成靜態欄位。
4. `NotePage(List<?> items, …)` 的 `?` 是替還不存在的需求留空間 → 收成 `List<String>`。

**回報但未修（要你決定，不自己擴大範圍）**：

- **`jws-algorithms` 沒設，預設只認 RS256**。Spec 軸稱之為「首次部署的地雷」：
  Supabase 非對稱金鑰的預設是 ES256，真 token 會全部 401。修法是一行設定，
  但在專案不可達、演算法未確認之前，寫死任何值都是猜。**建議併進票 10**
  （那一票本來就要第一次拿真 GoTrue token 打 Fly，撞到就是那裡撞到）。
- **`GET /v1/me/notes` 目前無 `limit`／游標，且 `where author_id = ?` 這條授權邊界沒有測試守著**
  （兩軸都獨立抓到）。票 06 的「跨使用者隔離」是它的第一個看門狗。
  若要提前補，最便宜的寫法是：用 `admin()` 塞一張 B 的便條，斷言 A 打
  `/v1/me/notes` 仍是 `{"items":[],"nextCursor":null}`——**不動這一票既有的斷言**，
  卻能讓 WHERE 一掉就紅。要不要現在就補，是你的決定。
- **03 交代下來的「INFO 記路徑／狀態碼／耗時」**：Spec 軸指出「判定不需要是擁有者的決定，
  不是實作者的」——同意。目前**沒有任何票擁有它**，請裁決：關掉，或給它一個 T 號。
