# ADR-0013：驗 JWT ＝ 驗簽章與 claims ＋ session 存活

日期：2026-09-22　狀態：已採納（T28 票 03 實作）

## 決策

服務對每個需認證的請求，在 JWT 簽章與 claims 驗過之後，**再以 token 的 `session_id` claim
查 `auth.sessions` 是否還有這一列**。列不在 → 401，形狀與其他所有 401 逐字相同
（`not_authenticated`，凍結的契約）。

- `session_id` 缺席、不是 UUID → 401（fail-closed）。GoTrue 發給使用者的 token 必定帶它
  （`tokens/service.go` 沒有 session 就拒發），缺了就不是使用者 token。
- **nil UUID 也是 401**。GoTrue 自己的中介層把缺席／nil 當「沒有 session」放行
  （那是給 service_role 那類 token 的例外）；本服務只收使用者 token，不抄這條例外。
- 不比對 `sessions.user_id` 與 `sub`（GoTrue 自己也不比）：兩者都在同一張 GoTrue 簽的 token 裡，
  偽造其一就等於握有簽章金鑰，那時比對也救不了。

效果：iOS 呼叫 Supabase `signOut()`（預設 global scope ⇒ GoTrue 刪掉該使用者全部 session 列）
的瞬間，所有已簽發的 access token 立即 401。**服務零新端點**——登出仍是 iOS ↔ Supabase 的事。
註銷（admin 硬刪 `auth.users`）經 FK cascade 帶走 session 列，同樣立即生效。

## 為什麼

需求是「登出當下所有憑證立即失效」（CONTEXT.md〈登出〉）。無狀態驗 JWT 做不到：
GoTrue 的登出只撤 refresh token，access token 活到 `exp`（預設 1 小時），
官方文件自己也寫 *"Access Tokens of revoked sessions remain valid until their expiry time"*。
而 Supabase 官方〈User sessions〉給的解法正是本決策：*"You can check that the `session_id` claim
in the JWT corresponds to a row in the `auth.sessions` table."*

放棄的替代方案：

| 方案 | 為什麼不 |
|---|---|
| 縮短 access token 有效期 | 窗變小但還在；refresh 次數倍增。需求要的是「沒有殘存有效期」 |
| 服務自建 denylist | 服務得知道「誰登出了」⇒ 要嘛代理 auth 路徑（違反架構原則），要嘛多一個登出端點讓 iOS 打兩次 |
| 每請求打 GoTrue `GET /auth/v1/user` | 它內部查的就是同一張表，卻多一趟跨服務 HTTP，延遲與可用性綁在 GoTrue 上 |
| 查詢結果快取 | **快取窗就是失效延遲窗**，等於推翻需求本身 |

## 為什麼經由 view，不是直接 GRANT，也不是 definer 函式

`hapeetrail_api` 讀不到 `auth` schema：schema 擁有者是 `supabase_admin`，`postgres`（migration 的
身分）對它的 USAGE 沒有 grant option。`grant usage on schema auth` 只印 WARNING、`ON_ERROR_STOP`
攔不到，migration 照綠、上線才每請求 `permission denied`（T28 票 02 研究 Q4，本機探針實測）。

Kevin 2026-09-22 裁決採 **view**：`hapeetrail_private.auth_sessions`（`select id from auth.sessions`，
擁有者 `postgres`，以擁有者權限讀底層表），服務只拿到它的 SELECT。

- 對 definer 函式的取捨：view 讓 Java 照樣用 `JdbcClient` 寫普通 SQL，DB 端不放函式（ADR-0011）。
- 只露 `id`；只給 SELECT——簡單 view 是自動可更新的，而 `postgres` 對 `auth.sessions` 有 DELETE，
  多給一個寫權限，服務就能把任何人登出（`SmokeTest` 釘住）。
- 自建 schema、不放 `public`：`public` 有 Supabase 的 default privileges（T24）且對 PostgREST 暴露；
  新 schema 兩者皆無，client 角色連 USAGE 都沒有（`SmokeTest` 釘住）。

## 代價與後果

- **每請求一次 PK lookup**：簡單 view 被 inline，`where id = ?` 走 `sessions_pkey` 的 Index Only Scan
  （EXPLAIN 實測）。sub-ms，但服務的驗證路徑從此依賴 DB——「無狀態」換「立即失效」。
- **對 GoTrue schema 的耦合**：view 在 `pg_depend` 上依賴 `auth.sessions.id`，GoTrue 哪天改這欄型別或
  重建整張表，它的 migration 會被擋。`notes.author_id → auth.users` 的 FK 是同一類耦合，且兩者都在
  Supabase 明文允許的範圍內。definer 函式沒有這個耦合，是被拒絕的那一邊的優點。
- **測試底座要補 `auth.sessions`**：這張表由 GoTrue 的 migration 建（hosted 與 supabase CLI 都在我們的
  migration 之前跑），Testcontainers 的映像沒有 GoTrue，`SupabaseDbTest` 補一張同擁有者、同授權的等價表。
- **驗證路徑上的伺服器故障要自己接成 500**：session 查詢在 filter 層，`ApiErrors` 管不到。
  查詢一拋例外（DB 斷線、權限錯），例外逃出 filter chain，容器轉 error dispatch 時已沒有認證，
  **不處理的話會回 401**（探針實測）——iOS 會把伺服器故障當 session 問題去刷新，匿名旅人被登出
  就永遠回不來。所以 entry point 看到「error dispatch 帶著例外」就回 500，形狀同 catch-all
  （Kevin 2026-09-22 裁決在本票內修；`ErrorEnvelopeTest` 以撤掉 view 授權模擬）。
- **validator 鏈不短路**：簽章有效但已過期／aud／iss 不符的 token 也會查一次（回應照樣 401）。
  只有 GoTrue 簽得出的 token 走得到這裡，簽章不符的垃圾碰不到 DB（Kevin 2026-09-22 接受）。

## 不是保證的邊界

「立即失效」只在登出請求真的抵達 GoTrue、且帶著未過期的 access token 時成立。以下 session 列**還在**，
殘存的 access token 仍會通過本檢查，直到 `exp`——與本決策之前的現狀相同，不是新破口：

- access token 已過期時 `signOut()`：GoTrue 回 403、supabase-swift 吞掉；離線時則先清本機再拋錯。
- session 的 time-box／inactivity 到期、single-session 被擠掉：GoTrue 只拒 refresh，不刪列。

契約描述 401 語意時不寫成絕對保證。
