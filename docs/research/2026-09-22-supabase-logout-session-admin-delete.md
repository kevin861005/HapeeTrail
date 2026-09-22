# Supabase 登出／session 存活／Admin 刪除使用者／金鑰制度研究（T28 票 02）

日期：2026-09-22
範圍：T28「登出立即失效＋註銷帳號」動手前要釘死的事實——supabase-swift `signOut()` 與 GoTrue `/logout` 對 `auth.sessions` 的實際作用、access token 的 session 識別 claim、GoTrue Admin 刪除使用者的端點形狀與行為、service_role JWT 與 `sb_secret_*` 金鑰制度，以及「`hapeetrail_api` 讀 `auth.sessions`」在 Supabase hosted 上是否可行。

方法論：只信一手資料。每條結論都標了證據等級：
- 【文件】Supabase 官方文件（直接讀 `supabase/supabase` repo 的 mdx 原始檔，master `e1e16d4a18`，2026-09-22）或 Supabase 官方 GitHub discussion。
- 【原始碼】文件沒講、要讀原始碼才能確認的結論，即「**推論自原始碼**」，附檔案路徑＋行號。
- 【探針】本機實測：GoTrue 容器 `public.ecr.aws/supabase/gotrue:v2.196.0`（本機 CLI 已快取的版本）＋`public.ecr.aws/supabase/postgres:17.6.1.143`（與 `SupabaseDbTest` 同一映像），再套上本 repo 全部 `supabase/migrations`。**完全沒打 hosted。**
- 「未能確認」＝查不到一手證據，不憑記憶填。

版本 pin：
| 對象 | 版本 | 備註 |
|---|---|---|
| `supabase/auth`（GoTrue） | **`v2.194.0`**（commit `0e779f25db`，2026-07-27） | 與 hosted 一致（`supabase/.temp/gotrue-version`）。下文行號皆指此 tag |
| 探針用 GoTrue | `v2.196.0` | 已比對 `v2.194.0...v2.196.0` 的 diff：logout、admin 刪除、session 載入、cleanup 的程式路徑**零變動**（只多了 banned 使用者檢查、`adminUserUpdate` 的 identity 調整、`last_sign_in_at` 更新），探針結果可代表 v2.194.0 |
| `supabase/supabase-swift` | **`v2.55.2`**（最新 release，2026-09-09） | |
| `supabase/supabase-js` | `v2.116.0`（最新 release，2026-09-07） | 只拿來確認官方 SDK 的 admin 呼叫形狀 |
| `supabase/postgres` | 探針用 `17.6.1.143`；hosted 為 `17.6.1.147` | 比對過兩個 tag：兩者之間**沒有任何 migration／init-script 變動**（只動 CI、nix、ansible） |

---

## 一頁結論

**Q1 登出與 session——spec 的核心假設成立，官方文件還直接背書這個做法。**
- supabase-swift `signOut()` 預設 scope 是 **`.global`**：送 `POST {SUPABASE_URL}/auth/v1/logout?scope=global`，帶 `Authorization: Bearer <目前的 access token>`。
- GoTrue `/logout` 三種 scope 都是**真的 `DELETE FROM auth.sessions`**，不是另外做標記。global 刪該使用者全部 session，local 只刪當前這筆，others 刪「當前以外」的全部。refresh token 靠 FK `ON DELETE CASCADE` 一起消失。成功回 204。
- 官方文件〈User sessions〉原文就是本 spec 的做法：*"When a user signs out, the sessions affected by the sign-out are removed from the database entirely. You can check that the `session_id` claim in the JWT corresponds to a row in the `auth.sessions` table."* GoTrue 自己的認證中介層也是同一套判準：session 列不存在就回 403 `session_not_found`。
- claim 名稱是 **`session_id`**，JSON string，小寫連字號格式的 UUID，值**就是 `auth.sessions.id`**（主鍵）。refresh 之後 **session_id 不變**（探針實測）。
- 【邊界】有兩種情況 `signOut()` **刪不到**伺服器端的 session 列：access token 已過期，或離線。GoTrue 回 403，supabase-swift 把 401/403/404 吞掉，而且在打網路**之前**就先清掉本機 session，所以 App 以為自己登出了，伺服器端的列卻還在。
- 【邊界】timebox、inactivity、single-session 這三種 session 到期**不刪列**（等 cleanup 在到期後 24h 才刪）。但到期的 session 已無法 refresh，殘留風險被 access token 的 `exp` 封頂。
- 【匿名】session 機制與一般帳號完全相同。**但官方文件明寫：匿名使用者一旦登出，就再也回不到自己的帳號**（見「對本專案的影響」第 6 點）。

**Q2 Admin 刪除使用者**
- 端點：`DELETE {SUPABASE_URL}/auth/v1/admin/users/{user_id}`。body 是**可省略**的 JSON `{"should_soft_delete": false}`，放 body 不放 query；空 body 就等於硬刪。
- GoTrue 本身只認 `Authorization: Bearer <JWT>`，而且 JWT 的 `role` claim 要在 `GOTRUE_JWT_ADMIN_ROLES` 內（預設 `service_role`、`supabase_admin`）。hosted gateway 另外要求 `apikey` header。
- 硬刪＝`DELETE FROM auth.users WHERE id = ?`，FK cascade 真的會跑。探針實測 sessions、identities、`public.notes` 全部歸零。
- 回應：**成功是 200，body 為 `{}`（不是 204）**。使用者不存在回 404 `user_not_found`。id 不是 UUID 也回 **404**，只是 code 為 `validation_failed`。
- 軟刪只是混淆資料、清 session，**`auth.users` 那列還在**，所以 `public.notes` 不會被 cascade。

**Q3 金鑰：用 `sb_secret_*`。**
- 官方建議 secret key。legacy `anon`／`service_role` 的說法是「2026 年底前棄用」（文件）、「Late 2026, TBC」（公告）。另外從 2025-11 起，新專案就不再有 legacy 金鑰。
- secret key **放 `apikey` header**，Authorization 不帶，由 hosted gateway 換成短效 JWT（role 為 `service_role`）再轉給 GoTrue。GoTrue 原始碼完全不認識 `sb_secret`。
- 向後相容上，`Authorization: Bearer sb_secret_…` 只有在它和 `apikey` 值**完全相同**時才被接受。
- 用法限制：官方寫明 secret key 在瀏覽器裡用不了（依 `User-Agent` 判斷，回 401）。

**Q4 ⚠️ 推翻 spec：「給 `hapeetrail_api` 一條 `GRANT SELECT ON auth.sessions`」單獨做行不通。**
- `postgres` 的確持有 `auth.sessions` 的 SELECT **with grant option**（這條權限來自 GoTrue 自己的 migration），所以 `GRANT SELECT` 本身會成功。
- 但 `hapeetrail_api` 還需要 `USAGE ON SCHEMA auth`，這個 `postgres` **給不出去**：schema 擁有者是 `supabase_admin`，`postgres` 的 USAGE 沒有 grant option。`GRANT USAGE ON SCHEMA auth TO hapeetrail_api` 只會印出 **WARNING: no privileges were granted**，migration 在 `ON_ERROR_STOP` 下**照樣「成功」**，直到執行期才爆出 `permission denied for schema auth`（探針實測）。
- 官方允許、本機也驗證可行的替代做法有兩種：(A) 在**自建、不對外暴露的 schema** 建一個由 `postgres` 擁有的 view；(B) 同樣位置的 `SECURITY DEFINER` 函式。取捨見 Q4 詳答，**需要 Kevin 裁決**。

---

## 逐題詳答

### Q1. signOut 與 session

#### 1.1 supabase-swift `signOut()`（v2.55.2）

【原始碼】`Sources/Auth/AuthClient.swift` L1090–1120：

```swift
public func signOut(scope: SignOutScope = .global) async throws {
  guard let accessToken = currentSession?.accessToken else { ...; return }
  if scope != .others {
    await sessionManager.remove()              // ← 先清本機 session
    eventEmitter.emit(.signedOut, session: nil)
  }
  do {
    _ = try await api.execute(.init(
      url: configuration.url.appendingPathComponent("logout"),
      method: .post,
      query: [URLQueryItem(name: "scope", value: scope.rawValue)],
      headers: [.authorization: "Bearer \(accessToken)"]))
  } catch let AuthError.api(_, _, _, response)
    where [404, 403, 401].contains(response.statusCode) {
    // ignore 404s since user might not exist anymore
    // ignore 401s, and 403s since an invalid or expired JWT should sign out the current session.
  }
}
```

- **預設 scope＝`.global`**。`SignOutScope` 有 `global`、`local`、`others` 三種（`Sources/Auth/Types.swift` L1197–1209）。【文件】〈Signing out〉也寫：*"JavaScript, Swift, Python, and C# default to the `global` scope. Dart and Kotlin default to `local`."*
- 請求形狀：`POST {SUPABASE_URL}/auth/v1/logout?scope=global`。headers 包括：
  - `Authorization: Bearer <access token>`：請求自帶的 header 會蓋掉 client 預設值，見 `Sources/Auth/Internal/APIClient.swift` L50–56。
  - `apikey: <publishable key>`：由 `SupabaseClient` 注入，見 `Sources/Supabase/SupabaseClient.swift` L242–251。
  - `X-Supabase-Api-Version: 2024-01-01`：`APIClient.execute` 自動補上。
  - 沒有 body。
- **有兩個行為要注意（推論自原始碼）**：
  1. `currentSession`（L201–203）就是 `sessionStorage.get()`，**不會先 refresh**；`signOut` 走的是 `api.execute`，不是會自動 refresh 的 `authorizedExecute`。所以 access token 已過期時，GoTrue 在驗 JWT 階段就回 403 `bad_jwt`，被 swift 吞掉，**伺服器端的 session 列不會被刪**。【探針】已實測：拿過期 token 打 `/logout` 得到 403 `bad_jwt`，session 列仍在。
  2. 除了 `others`，本機 session 都在打網路**之前**就移除。離線或 5xx 時會拋錯，但本機已經「登出」，伺服器端的列同樣留著。

#### 1.2 GoTrue `POST /logout` 對 `auth.sessions` 做什麼（v2.194.0）

【原始碼】路由在 `internal/api/api.go` L271：`r.With(api.requireAuthentication).Post("/logout", api.Logout)`。

`internal/api/logout.go` L21–73：
- L25–41：`scope` 從 query string 讀取。空字串或 `global` 視為 global；`local`、`others` 各自處理；其他值回 **400 `validation_failed`**（`Unsupported logout scope "…"`）。
- L46–65：先在同一個 transaction 裡寫一筆 audit log，然後：
  - `local` 呼叫 `models.LogoutSession(tx, s.ID)`；
  - `others` 呼叫 `models.LogoutAllExceptMe(tx, s.ID, u.ID)`；
  - 其他情況（含 token 沒有 session_id 時，**連 local 也一樣**，L51–53 只記一行 log）一律走 `models.Logout(tx, u.ID)`，也就是 global。
- L70：成功回 **204 No Content**。

`internal/models/sessions.go` L357–371，三支都是**真 DELETE**：

```go
// Logout deletes all sessions for a user.
DELETE FROM sessions WHERE user_id = ?
// LogoutSession deletes the current session for a user
DELETE FROM sessions WHERE id = ?
// LogoutAllExceptMe deletes all sessions for a user except the current one
DELETE FROM sessions WHERE id != ? AND user_id = ?
```

refresh token 的處理：`migrations/20220811173540_add_sessions_table.up.sql` L21 定義了 `refresh_tokens.session_id` 的 FK，**`on delete cascade`**，session 列一刪就一起走。新版 refresh token 演算法（v2）的 HMAC key 和計數器直接存在 `sessions` 列上（`migrations/20251007112900_add_session_refresh_token_columns.up.sql`），列刪了 refresh token 也就驗不過。

【文件】〈Signing out〉：*"Upon sign out, all refresh tokens and potentially other database objects related to the affected sessions are destroyed"*；另有 caution：*"Access Tokens of revoked sessions remain valid until their expiry time"*，這正是 T28 要補的洞。

【探針】匿名使用者 signup → refresh → 以預設 scope 登出，結果：
- 204，該使用者 `auth.sessions` 從 1 列變 0 列，`auth.refresh_tokens` 也是 0，`auth.users` 那列仍在。
- 舊 access token 打 GoTrue `/user` 得到 403 `session_not_found`。
- 舊 refresh token 得到 400 `refresh_token_not_found`。
- 同一個 token 再打一次 `/logout` 得到 403 `session_not_found`（supabase-swift 會吞掉）。

另外用密碼帳號開了三個 session 實測：`scope=local` 只刪自己那列，`scope=others` 只留下自己那列。

#### 1.3 session 識別 claim

【原始碼】`internal/tokens/service.go`：
- L73–85：`AccessTokenClaims` 有 `SessionId string \`json:"session_id,omitempty"\``。
- L657–735 `GenerateAccessToken`：
  - L661–663：沒有 SessionID 就直接 500（"Session is required to issue access token"）。也就是說，**GoTrue 發給使用者的 access token 一定帶 session_id**。
  - L664：`sid := params.SessionID.String()`，型別是 gofrs `uuid.UUID`，`String()` 輸出小寫、連字號分隔的 canonical 格式。
  - L665：簽發前會先 `FindSessionByID` 確認 session 列存在。
  - L712：`SessionId: sid` 寫入 claim。
- L1076：Custom Access Token Hook 的輸出 schema 把 `session_id` 列為 **required**，所以 hook 也拿不掉它。
- `NewSession`（`internal/models/sessions.go` L256–267）用 `uuid.NewV4()` 產生 `sessions.id`；PK 定義在 `migrations/20220811173540_add_sessions_table.up.sql` L3、L7。

【文件】〈User sessions〉：*"Every access token contains a `session_id` claim, a UUID, uniquely identifying the session of the user. You can correlate this ID with the primary key of the `auth.sessions` table."*〈JWT Claims Reference〉則把 `session_id` 列在 "Required claims"，型別為 `string`。

**refresh 後 session_id 不變**：【原始碼】`RefreshTokenGrant`（service.go L188 起）一律在**同一個** `session` 上輪替 refresh token，最後以 `SessionID: &session.ID` 呼叫 `GenerateAccessToken`（L592–597），不會建立新 session。【探針】refresh 前後的 `session_id` 逐字相同。

GoTrue 自己怎麼驗（可作為本服務的參考實作）：【原始碼】`internal/api/auth.go` L112–158 `maybeLoadUserOrSession`：
- `session_id` 為空字串，或等於 nil UUID `00000000-0000-0000-0000-000000000000` 時，**視為無 session、直接放行**（這是為 service_role 這類不帶 session 的 token 設計的）；
- 不是合法 UUID 回 403 `bad_jwt`（"session_id claim must be a UUID"）；
- 查無此列回 403 `session_not_found`。

注意：GoTrue **不比對** `sessions.user_id` 是否等於 `sub`。另外 `internal/api/middleware.go` L198–201 對 admin 路徑的註解寫著 *"a JWT remains usable past logout or revocation otherwise"*，GoTrue 自己也承認這個洞，並用同一套 session 查詢把它補上。

> 對本服務的差異：GoTrue 對「沒有 session_id」是**放行**，本服務依票 03 必須 **fail-closed，回 401**。本服務只接受使用者 token，而使用者 token 必定帶 session_id。

#### 1.4 「session 列存在＝session 活著」夠不夠：哪些終止路徑刪列、哪些不刪

【原始碼】以下是全 repo 對 session 的刪除呼叫點（`grep` 過 `models.Logout*`、`InvalidateSessionsWithAALLessThan`、`RevokeOAuthSessions`、`RevokeTokenFamily`）：

| 終止路徑 | `auth.sessions` 列 | 出處 |
|---|---|---|
| `/logout`（global／local／others） | **刪** | `logout.go` L54–64 |
| Admin 硬刪使用者 | **刪**（`auth.users` cascade） | `admin.go` L620–624＋sessions FK |
| Admin 軟刪使用者 | **刪**（`models.Logout`） | `admin.go` L614；`user.go` L1032 |
| refresh token 重用偵測（v2 演算法、rotation 開啟） | **刪**（`LogoutSession`） | `service.go` L564 |
| MFA 相關（驗證升級、移除 factor 等） | **刪**（刪 aal 較低的或全部） | `mfa.go` L635、L701、L787、L842、L962 |
| OAuth Server 撤銷授權 | **刪** | `oauthserver/handlers.go` L618 |
| **Time-box／Inactivity timeout 到期** | **不刪**（只拒絕 refresh） | `service.go` L247–258；`sessions.go` L215–233 |
| **Single session per user（被新登入擠掉）** | **不刪**（只拒絕 refresh，"Revoked by Newer Login"） | `service.go` L357 |
| v1 refresh token 重用、token family 撤銷 | **不刪**（`refresh_tokens.revoked = true`） | `refresh_token.go` L87–109；`service.go` L396 |
| 背景 cleanup | **刪**：`not_after` 過後 72h；timebox／inactivity 到期後 24h | `models/cleanup.go` L52–89，**但只有 `GOTRUE_DB_CLEANUP_ENABLED=true` 才會跑**（`conf/configuration.go` L134 預設 `false`；`api.go` L189–192） |

【文件】〈User sessions〉也寫：*"sessions are not proactively terminated when their maximum lifetime (time-box) or inactivity timeout are reached. These sessions are cleaned up progressively 24 hours after reaching that status."*

**對 T28 的判讀**：「不刪列」的那幾條路徑有個共同點，session 都**無法再 refresh**，所以殘留的只有已發出、最長活到 `exp`（預設 1h）的 access token。這和 T28 之前的現狀一樣，不是新破口。T28 要解決的「使用者按下登出」和「註銷」都落在「刪列」那一側，所以**這個判準對 T28 已經足夠**。Time-box、inactivity、single-session 都是 dashboard 上的選配設定。本 repo `supabase/config.toml` L272–276 的 `[auth.sessions]` 區塊目前整段是註解，也就是本機沒開；**hosted 的設定未查證**。

#### 1.5 匿名使用者有無不同

- **session 機制相同**。【原始碼】`internal/api/anonymous.go` L12 `SignupAnonymously` 在 L47 走一般的 `issueRefreshToken`，也就是 `tokens.Service.IssueRefreshToken`（service.go L859），再到 L877 `models.NewSession`。建 session 列、發 `session_id` claim、logout 刪列的路徑都與一般帳號共用；唯一差別是 claim 裡的 `is_anonymous: true`。【探針】上面 1.2 的登出實測就是用匿名使用者跑的。
- **匿名使用者沒有任何 identity 列**（探針：`auth.identities` 為 0 列），也沒有任何可重新登入的憑證。【文件】〈Anonymous Sign-Ins〉原文：*"It behaves like a permanent user, except the user can't access their account if they sign out, clear browsing data, or use another device."* 也就是說，**匿名旅人按下登出＝永遠回不到這個帳號**。帳號與便條留在資料庫裡成為孤兒，不會被刪。
- 【附帶發現，與 T27 相關】`internal/models/cleanup.go` L67–72：只要開了匿名登入**而且** `GOTRUE_DB_CLEANUP_ENABLED=true`，GoTrue 就會自動 `delete from users where created_at < now() - interval '30 days' and is_anonymous is true`（PR [supabase/auth#1497](https://github.com/supabase/auth/pull/1497)，2024-03）。這一刪會 cascade 帶走匿名旅人的全部便條。但【文件】〈Anonymous Sign-Ins〉寫著 *"Automatic cleanup of anonymous users is currently not available."* **hosted 有沒有打開這個 flag：未能確認**（hosted 設定不公開）。建議 Kevin 在 SQL Editor 跑一句唯讀查詢：`select count(*) from auth.users where is_anonymous and created_at < now() - interval '30 days';`。如果結果 > 0（有建立超過 30 天的匿名帳號還活著），就表示 hosted 沒有在做這個清理；如果是 0，就還要看專案最早的匿名帳號是不是根本還沒滿 30 天，才能下結論。

### Q2. GoTrue Admin 刪除使用者

#### 2.1 端點、方法、body

【原始碼】`internal/api/api.go` L345–378：`/admin` 路由群組整組掛 `requireAdminCredentials`，`/users/{user_id}` 先過 `loadUser`，再 `r.Delete("/", api.adminUserDelete)`（L376）。hosted 上的路徑前綴是 `/auth/v1`，所以完整 URL 為：

```
DELETE https://<project-ref>.supabase.co/auth/v1/admin/users/{user_id}
```

`internal/api/admin.go`：
- L37–39：`adminUserDeleteParams { ShouldSoftDelete bool \`json:"should_soft_delete"\` }`，欄位**在 JSON body 裡**，query string 不讀。
- L573–581：*"ShouldSoftDelete defaults to false"*，而且只有 body 非空時才解析；body 不是合法 JSON 會回 400 `bad_json`（`helpers.go` L84–96）。
- 【探針】空 body 得到 200，使用者被硬刪。

官方 SDK 的呼叫形狀【原始碼】`supabase-js` v2.116.0 `packages/core/auth-js/src/GoTrueAdminApi.ts` L836–846：`DELETE ${url}/admin/users/${id}`，body 為 `{ should_soft_delete: shouldSoftDelete }`，預設 `false`。

> GoTrue repo 內的 `openapi.yaml`（L1623–1647）宣稱 200 會回 `UserSchema`，也沒有列出 body 參數。**這份規格與程式碼不符，以程式碼與探針為準。**

#### 2.2 認證：兩層要分開看

**GoTrue 本身**【原始碼】：
- `middleware.go` L187–224 `requireAdminCredentials`：
  - 用 `extractBearerToken` 取 `Authorization: Bearer <token>`，沒有就回 **401 `no_authorization`**。
  - 用 `parseJWTClaims`（`auth.go` L77–110）驗簽：有 `kid` 就到 JWKS 找公鑰；沒有 `kid` 且 `alg=HS256` 就用 JWT secret。驗不過回 **403 `bad_jwt`**。
  - 若 token 帶了真的 session_id，會額外確認 session 存在且有效（L198–221）；service_role 這類不帶 session 的 token 會跳過這一步。
- `auth.go` L47–65 `requireAdmin`：JWT 的 `role` claim 必須在 `config.JWT.AdminRoles` 內，否則回 **403 `not_admin`**（"User not allowed"）。
- 預設值在 `conf/configuration.go` L1094–1096：`[]string{"service_role", "supabase_admin"}`。
- GoTrue 原始碼**完全不檢查 `apikey` header**，也不認識 `sb_secret_`／`sb_publishable_`：全 repo grep 除了 SMS provider 的設定欄位之外沒有任何命中。

**Supabase hosted API gateway**：
- 【文件】GoTrue `openapi.yaml` 的 `APIKeyAuth`：*"When deployed on Supabase, this server requires an `apikey` header containing a valid Supabase-issued API key to call any endpoint."*
- 【文件】〈JWT Signing Keys〉FAQ：*"This API Gateway component is able to verify the API key (sent in the `apikey` request header …) against your project's publishable and secret key list. If the match is found, it mints a temporary, short-lived JWT that is then forwarded down to your project's servers."*
- secret key 對應的 Postgres 角色是 `service_role`（〈API keys〉L82–88），因此 gateway 鑄出的 JWT 會通過 GoTrue 的 `requireAdmin`（推論：文件說明 secret key 等同 `service_role`，而 GoTrue 的 admin 判準就是 role claim）。
- 【文件】self-hosted Envoy gateway 文件（〈Envoy API Gateway〉的 Authentication 節）公開了同一套機制的實作細節：如果 client 沒在 `Authorization` 送真正的 JWT（或只送了 `Bearer sb_*`），gateway 會用換好的 JWT 合成 `Authorization: Bearer <jwt>`。**hosted gateway 不開源，它的內部細節未能確認**，只能依上面的官方文件描述。

【探針】（legacy 形態：自簽 HS256 JWT，驗的是 GoTrue 這一層，沒經過 gateway）：
- `role=service_role` 的 JWT 得到 200。
- `role=authenticated` 的 JWT 得到 403 `not_admin`。
- **使用者自己的 access token** 得到 403 `not_admin`。
- 不帶 Authorization 得到 401 `no_authorization`。

⚠️ 所以本服務轉呼叫 GoTrue 時**絕不能把使用者的 Bearer 原樣轉送過去**。

#### 2.3 `should_soft_delete` 的實際行為

【原始碼】`admin.go` L583–627（整段在一個 transaction 內，先寫 `UserDeletedAction` audit log）：

- **`false`（硬刪）**：只有一行 `tx.Destroy(user)`（L620–624）。gobuffalo/pop v6.1.1 的 `Destroy` 會產生 `DELETE FROM "users" AS users WHERE users.id = $1`（`dialect_common.go` L140–147 `genericDestroy`；User model 沒有定義 BeforeDestroy／AfterDestroy hook）。這是**真的 `DELETE FROM auth.users`**，所有指向它的 FK action 都會執行：
  - GoTrue 自己的表：`sessions`、`identities`、`mfa_factors`、`one_time_tokens`、passkey 等全部 `on delete cascade`；`refresh_tokens` 則經由 `sessions` 間接 cascade。
  - 本專案的表：`public.notes.author_id` 為 `on delete cascade`，`picked_up_by` 為 `on delete set null`（`supabase/migrations/20260712000000_notes.sql` L6、L15）。
  - 【探針】硬刪後 `auth.users`、`auth.sessions`、`auth.identities`、`public.notes`（該作者的便條）全部變 0。FK action 由 `supabase_auth_admin` 連線觸發，對 `public.notes` 的 cascade 照常生效。
- **`true`（軟刪）**：
  - `user.SoftDeleteUser`（`user.go` L975–1037）：把 email、phone 換成混淆值，清掉密碼與所有 token 欄位，設定 `deleted_at`，清空 `raw_user_meta_data`、`raw_app_meta_data`，刪除 one-time tokens，並呼叫 `Logout`。
  - `SoftDeleteUserIdentities`（L1040–1068）：清空 identity_data，並混淆 `provider_id`。
  - 另外硬刪 MFA factors、WebAuthn credentials、sessions。
  - **`auth.users` 那列保留**，因此 `public.notes` **不會** cascade。【探針】軟刪後 user 列仍在、`deleted_at` 已設定、sessions 為 0。
  - 另外 `FindUserByID`（`user.go` L630–632）不會過濾 `deleted_at`，所以對已軟刪的使用者再送一次軟刪會直接回 200（L592–595）。

→ T28 的「全刪」需求**只能用 `should_soft_delete=false`**（或省略 body）。

#### 2.4 回應碼與 body

**錯誤 body 有兩種格式**【原始碼】`internal/api/errors.go` L80–178：
- 預設格式：`{"code":<http狀態>,"error_code":"<code>","msg":"<訊息>"}`（`apierrors.go` L48–55）。
- 請求帶 `X-Supabase-Api-Version: 2024-01-01`（`apiversions.go` L7）時：`{"code":"<code>","message":"<訊息>"}`（`errors.go` L75–78）。
- **兩種格式都會帶 response header `X-Sb-Error-Code: <code>`**（L137–139）。

| 情境 | 狀態碼 | body（預設格式） | 出處／驗證 |
|---|---|---|---|
| 硬刪成功 | **200** | `{}`（`Content-Type: application/json`，`Content-Length: 2`） | `admin.go` L629；探針 |
| 使用者不存在（含併發重試時已被刪掉） | **404** | `{"code":404,"error_code":"user_not_found","msg":"User not found"}` | `admin.go` L64–67 `loadUser`；探針 |
| `user_id` 不是 UUID | **404** | `{"code":404,"error_code":"validation_failed","msg":"user_id must be an UUID"}` | `admin.go` L56–59；探針 |
| role 不是 admin | 403 | `{"code":403,"error_code":"not_admin","msg":"User not allowed"}` | `auth.go` L61；探針 |
| 沒帶 Bearer | 401 | `{"code":401,"error_code":"no_authorization",…}` | `auth.go` L71；探針 |
| JWT 驗簽失敗或已過期 | 403 | `error_code: bad_jwt` | `auth.go` L105–106 |
| 路徑打錯（例如少了 `/admin`） | 404 | **`text/plain` 的 `404 page not found`，沒有 error code** | 探針（chi router 的預設 404） |
| DB 錯誤 | 500 | `error_code: unexpected_failure`，另附 `error_id` | `errors.go` L132–134、L159–163 |
| hosted gateway 拒絕 apikey | 401 | body 形狀未能確認 | 〈API keys〉只說 browser 會被擋、回 401 |

### Q3. 金鑰制度

#### 3.1 官方當前建議

- 【文件】〈API keys〉L109：*"Secret keys improve on the old JWT-based `service_role` key, and we recommend them wherever possible."*
- 【文件】〈API keys〉L51、L53：secret key（`sb_secret_...`）是 "Elevated"、只能用在後端；`service_role` 是 "Legacy version of secret keys"。
- 【文件】〈Users〉L81、L93–115：Admin API（`supabase.auth.admin`）的範例一律用 secret key（`sb_secret_...`，"which replaces the legacy `service_role` key"）。

#### 3.2 header 用法與對 `/auth/v1/admin/*` 的相容性

- 【文件】〈API keys〉Known limitations L351：*"Send publishable and secret keys on the `apikey` header, not on `Authorization: Bearer`. Because the keys aren't JWTs, anything that tries to verify one as a JWT fails."*
- 【文件】公告 [Discussion #29260](https://github.com/orgs/supabase/discussions/29260)（Supabase 官方）"Key differences"：*"It is no longer possible to use a publishable or secret key inside the `Authorization` header — because they are not a JWT. Instead pass in the user's JWT, or leave the header empty. For backward compatibility, it is only allowed if the value in the header exactly matches the value in the `apikey` header."*
- 【原始碼】官方 SDK 的實際做法：supabase-js v2.116.0 的 Auth client 預設同時送 `Authorization: Bearer <key>` 與 `apikey: <key>`，兩者值相同（`packages/core/supabase-js/src/SupabaseClient.ts` L649–652），`auth.admin` 也沿用這組 header。也就是說，即使 key 是 `sb_secret_…`，SDK 走的仍是「兩個 header 值相同」的相容路徑；只有 Edge Functions 這條另外排除了 Bearer（`src/lib/fetch.ts` L24–31、L77–95）。
- **結論**：
  - **sb_secret**：放在 `apikey`，**不帶 Authorization** 是文件推薦的正路（*"leave the header empty"*）；帶 `Authorization: Bearer <完全相同的 sb_secret>` 是官方 SDK 在用、公告明文允許的相容路徑。**兩者都不可以**是「apikey 放 sb_secret、Authorization 放使用者 JWT」，因為 GoTrue 會拿使用者 JWT 去驗 admin，結果回 403 `not_admin`。
  - **legacy `service_role`**：它本身就是 HS256 JWT（role=service_role），兩個 header 都放同一個值，GoTrue 直接驗簽即可。
  - hosted gateway 把 sb_secret 換成 JWT 的行為屬於**文件描述，不是原始碼可驗證的事實**，要靠票 05 的 hosted 實證收尾。

#### 3.3 棄用時程

- 【文件】目前所有 API key 頁共用的 partial（`_partials/api_keys_deprecation.mdx`）：*"Supabase is deprecating the `anon` and `service_role` keys by the end of 2026."*
- 【文件】Discussion #29260 的時程表（最後編輯 2025-07-14）：
  - 2025-06：early preview。新專案**同時**產生新舊兩組金鑰；既有專案要手動 opt-in。
  - 2025-07：正式上線。
  - **2025-11**：開始每月寄提醒；**2025-11-01 之後才 restore 的專案不再帶 legacy 金鑰**；**新專案不再提供 `anon`／`service_role`**。
  - **Late 2026, TBC**：legacy 金鑰被刪除並從文件、Dashboard 移除，*"You have to migrate … by this point or your app will break."*
- **確切停用日期：未能確認**（官方只寫到「2026 年底／TBC」）。本專案的 hosted 專案（`iwkuywlrggxolyoiyrui`）有沒有 legacy 金鑰：未能確認（沒有打 hosted），但這不影響結論：**T28 一律用 secret key**。
- 新專案與既有專案的差異：【文件】〈Migrating to new API keys〉L26：*"Older projects don't have these keys yet. If you see a **Create new API keys** button, your project is still on legacy keys only."* 新舊兩組可以並存；legacy 金鑰只能在 Dashboard 停用（可逆），secret key 則是刪除（不可逆、立即生效）。

#### 3.4 secret key 的使用限制（全部是【文件】）

- **瀏覽器封鎖**：*"A secret key doesn't work in a browser. Supabase matches on the `User-Agent` header and returns HTTP 401 Unauthorized."*（〈API keys〉L111）。判斷規則的細節未公開（未能確認）。Java 伺服器端 HTTP client 的預設 UA 不屬於瀏覽器；**不要**替服務設定像瀏覽器的 UA。
- 不可放在 URL 或 query 參數；*"In a request header, until you have log sanitization in place"*；不可記進 log，真要記最多只記前綴後的 6 個字元，或者記 SHA-256（〈API keys〉L320–331）。
- 建議每個後端元件各用一把 secret key，外洩時只需輪替那一把（L342）。可在 Dashboard 以名稱建立，例如 `hapeetrail-api`。
- 秘鑰 reveal 會記入 organization audit log；刪除即時撤銷（Discussion #29260）。
- secret key 對應 `service_role`（`BYPASSRLS`）。它只會用在 GoTrue admin 呼叫，本服務連 DB 仍走 `hapeetrail_api`，兩者互不相干。

### Q4. `hapeetrail_api` 能否讀 `auth.sessions`（可行性檢查）

#### 4.1 權限現況

【原始碼】`supabase/postgres`：
- `migrations/db/init-scripts/00000000000001-auth-schema.sql` L3：`CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION supabase_admin`，schema 擁有者是 **`supabase_admin`**（不是 `postgres`）。
- `migrations/db/migrations/10000000000000_demote-postgres.sql` L5：`GRANT ALL ON SCHEMA auth TO postgres`，**沒有** `WITH GRANT OPTION`。
- `20250421084701_revoke_admin_roles_from_postgres.sql` L16–17：`revoke supabase_auth_admin from postgres; revoke create on schema auth from postgres;`。

【原始碼】`supabase/auth` `migrations/20240612123726_enable_rls_update_grants.up.sql` L19–35：由 `supabase_auth_admin`（auth 各表的擁有者）執行 `grant select on auth.sessions to postgres with grant option`（L27），註解寫著 *"allow postgres role to select from auth tables and allow it to grant select to other roles"*。

【探針】（真實 GoTrue 跑完自己的 migration 之後）：

```
auth schema ACL : {supabase_admin=UC/supabase_admin, …, postgres=U/supabase_admin}
                                                         ^ 只有 U，沒有 U*
auth.sessions ACL: {postgres=ar*wdDxtm/supabase_auth_admin, …}
                             ^^ SELECT 帶 grant option
```

以 `postgres` 身分執行（就是 migration 的身分）：

```
postgres=> grant usage on schema auth to hapeetrail_api;
WARNING:  no privileges were granted for "auth"      ← 不是 ERROR，ON_ERROR_STOP 攔不到
GRANT
postgres=> grant select on auth.sessions to hapeetrail_api;
GRANT                                                 ← 成功
hapeetrail_api=> select count(*) from auth.sessions;
ERROR:  permission denied for schema auth             ← 執行期才爆
```

**結論**：spec 的「一支 migration 給 `SELECT ON auth.sessions`」**不可行**，而且這個失敗是**靜默**的：migration 綠燈，服務上線後每個請求都 500。

#### 4.2 官方對 auth schema 的限制

【文件】[Discussion #34270](https://github.com/orgs/supabase/discussions/34270)〈Restricting Access on Auth, Storage, and Realtime Schemas on April 21, 2025〉（Supabase 官方）：
- 禁止：在 `auth` 建表或函式、刪除既有表或函式、在既有表上建索引、對 migration 表做寫入、從 API 角色撤銷 auth 表的權限。
- 仍允許：*"Create foreign keys referencing tables in the `auth` … schemas"*，以及在 `auth.sessions`、`auth.users` 等表上 *"Create RLS policies and database triggers"*。
- 放在 `auth` 裡的自訂物件要搬到 `public` 或自建 schema，否則會被刪掉。

**官方沒有任何文件說明「如何讓自訂角色讀 auth 表」**。【文件】〈Managing user data〉L11 只說 *"the Auth schema is not exposed in the auto-generated API. If you want to access users data via the API, you can create your own user tables in the `public` schema"*，並示範用 security definer trigger 同步資料。下面的替代方案都是**在官方允許範圍內**（不在 `auth` 建任何東西），並經本機探針驗證可行，但**沒有任何一個是官方文件點名的標準做法**。

#### 4.3 替代方案（本機探針全部驗證通過）

| 方案 | 作法 | 優點 | 缺點／風險 |
|---|---|---|---|
| **A. 自建 schema 內的 view** | `create schema <私有 schema>;` → `create view <私有>.auth_sessions as select id from auth.sessions;`（擁有者為 `postgres`，view 預設以擁有者權限存取底層表）→ `grant usage on schema <私有> to hapeetrail_api; grant select on <私有>.auth_sessions to hapeetrail_api;` | 不是函式，Java 端照樣用 `JdbcClient` 寫普通 SQL（`select 1 from <私有>.auth_sessions where id = ?`），最貼近 ADR-0011；簡單 view 會被 inline，PK 查詢仍走 `sessions_pkey`（**需在票 03 用 EXPLAIN 確認**） | view 在 `pg_depend` 上**依賴 `auth.sessions.id` 這個欄位**：GoTrue 將來若改這個欄位的型別或重建整張表，它的 migration 會失敗，hosted Auth 就升級不了。改 PK 型別的機率極低，但確實是一種耦合 |
| **B. SECURITY DEFINER 函式** | `create function <私有>.session_alive(sid uuid) returns boolean language sql stable security definer set search_path = '' as $$ select exists(select 1 from auth.sessions where id = sid) $$;` → **`revoke execute … from public`**（PostgreSQL 預設會把函式 EXECUTE 給 PUBLIC）→ `grant execute … to hapeetrail_api` | 非 `BEGIN ATOMIC` 的 SQL 函式本體**不記錄依賴**，不會卡住 GoTrue 的 migration；暴露面最小（只回傳 boolean） | DB 端多了一支函式，與 ADR-0011「DB 端不放業務函式」精神相牴觸（雖然這支不是業務規則，只是存取閘道）；T19 才剛把 RPC 與 helper 清光 |
| C. 每個請求打 GoTrue `GET /auth/v1/user` | GoTrue 的 `requireAuthentication` 本身會查 session 列（`auth.go` L112–158），查無回 403 `session_not_found` | 零 DB 權限改動，完全走廠商的公開契約 | 每個請求多一趟跨服務 HTTP 呼叫（延遲、可用性都綁在 GoTrue 上），與 spec「sub-ms PK lookup」的前提差很多 |
| ~~D. 把 `anon`／`authenticated` 角色授權給 `hapeetrail_api`~~ | 藉這些角色拿到 auth 的 USAGE | — | **不建議**：會連帶繼承 client 角色的其他權限，違反最小權限原則 |

A、B 共同要注意的地方：
- **私有 schema 不能是 `public`**。`public` 是 PostgREST 對外暴露的 schema，而且 Supabase 在 `public` 設有 default privileges，會把新建的表與函式授權給 `anon`、`authenticated`（`supabase/postgres` `init-scripts/00000000000000-initial-schema.sql` L40–42，範圍僅限 `in schema public`）。自建的新 schema 預設不會給任何 client 角色權限，也不在 PostgREST 的 exposed schemas 裡。
- **Testcontainers 裡沒有 `auth.sessions`**：`auth.sessions` 是 GoTrue 的 migration 建的，`supabase/postgres` 映像的 init script 只建 `users`、`refresh_tokens`、`instances`、`audit_log_entries`、`schema_migrations`（探針實測 GoTrue 啟動前 auth 只有這 5 張表）。所以 A 方案的 migration 在 `SupabaseDbTest` 裡會直接失敗；B 方案（非 ATOMIC 的 SQL 函式）雖然可以建立，但一呼叫就錯。
  - Supabase CLI 本機環境沒有這個問題：它在套用使用者 migration **之前**會先跑 `gotrue migrate`（`supabase/cli` `apps/cli-go/internal/db/start/start.go` L318–347 `initAuthJob`／`initSchema15`）。hosted 本來就有這張表。
  - 因此測試底座必須自行補上一份等價於 GoTrue 的 `auth.sessions`：擁有者為 `supabase_auth_admin`，並對 `postgres` 授予 `select … with grant option`，或者改成直接在測試裡跑 `gotrue migrate`。這是票 03 的實作細節。
- **hosted 驗證（唯讀、選做）**：到 SQL Editor 執行 `select nspacl from pg_namespace where nspname='auth'; select relacl from pg_class where oid='auth.sessions'::regclass;`，預期會看到 `postgres=U/supabase_admin` 與 `postgres=ar*…/supabase_auth_admin`，與本機一致。兩個映像 tag 之間沒有 migration 差異，理應相同，但這件事屬於推論，沒有實測。

---

## 對本專案 T28 的影響

逐條對照 spec（`.scratch/logout-account-deletion/spec.md`）：

1. **✅ 確認：「`signOut()` 會刪除 `auth.sessions` 對應列」**。三種 scope 都是真 DELETE，refresh token 經 cascade 一起消失，官方文件還直接背書「查 `session_id` 對應的列是否存在」這個判準。票 03 的方向不變。
2. **✅ 確認：claim 名稱為 `session_id`**，是字串形式的 UUID，等於 `auth.sessions.id`，refresh 後不變。使用者 token 必定帶這個 claim，所以缺少時 fail-closed 回 401 是正確的。
3. **❌ 推翻：「`hapeetrail_api` 需要 `SELECT ON auth.sessions` 的授權（一支 migration）」**。單一 GRANT 不可行：USAGE 給不出去、失敗是靜默的，到執行期才出現 `permission denied`。票 03 的第一個 checkbox（「`hapeetrail_api` 取得 `auth.sessions` 的最小必要讀取權」）**必須改用 Q4.3 的 A 或 B 方案**，並且要補「Testcontainers 沒有 `auth.sessions`」的測試底座。**需要 Kevin 裁決 A（view，貼近 ADR-0011，但與 GoTrue schema 有依賴耦合）或 B（definer 函式，無耦合，但 DB 端多一支函式）**。不論選哪個，都值得寫進 ADR-0013 的「後果」一節。
4. **⚠️ 修正：「GoTrue 回 404 視為已刪（冪等回 204）」要收窄成「404 **且** error code 為 `user_not_found`」**。GoTrue 對「id 不是 UUID」也回 404（`validation_failed`）；base URL 設錯、路徑打錯時則回 `text/plain` 的 404，沒有任何 code。只看狀態碼的話，**設定錯誤會被靜默當成「刪除成功」**，服務回 204，帳號卻還活著。建議判斷 `X-Sb-Error-Code: user_not_found` header，或解析 body 的 `error_code`（預設格式）或 `code`（帶 `X-Supabase-Api-Version: 2024-01-01` 時）。header 是否能原樣穿過 hosted gateway 未能確認，body 則可以確定由 GoTrue 產生。
5. **⚠️ 修正（測試設施）：GoTrue 硬刪成功是 200、body 為 `{}`，不是 204**。票 04 的 fake GoTrue 應該回 200 `{}` 才貼近真實；服務端把任何 2xx 都當成功即可。
6. **⚠️ 補充（產品面，建議先討論）：匿名旅人「登出」＝永久失去帳號**（官方文件明文）。帳號與便條不會被刪，而是變成孤兒。因此：
   - User Story 3（「登出後重新登入時我的便條與足跡原封不動」）**只對已綁定 Google／Apple 的帳號成立**；
   - User Story 2（在別台裝置登入後 global sign-out）同樣只適用於已綁定帳號，匿名帳號沒辦法在別台裝置登入；
   - 匿名旅人在登出前是否需要警告、或者乾脆不提供登出，是 iOS 與產品層的決定，本研究只陳述事實；
   - 這種孤兒帳號也會加重 T27（棄置匿名帳號清理）的負擔。
7. **⚠️ 補充（邊界，不推翻）：「按下登出的瞬間所有 token 立即失效」只在登出請求真的抵達 GoTrue、且帶著未過期的 access token 時才成立**。access token 已過期時，GoTrue 回 403，supabase-swift 吞掉這個錯誤；離線時則在拋錯之前就清掉本機 session。這兩種情況伺服器端的 session 列都**還在**。實際風險只剩「refresh token 已經外洩」這一種（本機已丟掉 token），而且與 T28 之前的現狀相同。契約文件（`docs/api/`）描述 401 語意時，不要寫成絕對保證。
8. **✅ 定奪（spec 留給 research 決定的項目）：金鑰用新制 secret key（`sb_secret_…`）**，只放在 `apikey` header，不帶 Authorization。在 Dashboard 為本服務建一把專用、具名的 secret key。spec 的 User Story 17 與 Implementation Decisions 裡的「service_role key」字樣應改為 secret key。legacy 金鑰 2026 年底前就會被移除，而且 2025-11 之後建立的專案可能根本沒有。
9. **（範圍外，只記錄）**：spec「執行路徑」一節寫「環境變數＋fly secrets」，但 T26 之後測試環境已經改到 Cloud Run。秘密該放在哪個平台，請以 T26 的部署現況為準。
10. **（範圍外，與 T27 相關）**：GoTrue 程式碼內建「匿名使用者建立滿 30 天就自動刪除」，由 `GOTRUE_DB_CLEANUP_ENABLED` 控制；官方文件卻說沒有自動清理。hosted 是否開啟**未能確認**，驗證方法見 Q1.5 的唯讀查詢。一旦開啟，所有超過 30 天的匿名旅人便條都會被 cascade 刪除。

### 票 03／04 可直接引用的具體值

| 項目 | 值 | 出處 |
|---|---|---|
| session claim 名稱 | `session_id` | `tokens/service.go` L82、L712；〈User sessions〉 |
| claim 格式 | JSON string，小寫 canonical UUID（例：`f078b721-f114-4315-859d-2a5cccd1c85b`） | `service.go` L664；探針 |
| 對應欄位 | `auth.sessions.id`（`uuid`，PK `sessions_pkey`）；`auth.sessions.user_id` = `sub` | `migrations/20220811173540…` L3–8 |
| GoTrue 對 nil UUID 的 session_id | 視為「沒有 session」並放行（本服務應 fail-closed，回 401） | `auth.go` L141 |
| refresh 後 session_id | 不變 | `service.go` L592–597；探針 |
| iOS 登出（供契約澄清句用） | `POST {SUPABASE_URL}/auth/v1/logout?scope=global`（預設）／`local`／`others`；headers `apikey`＋`Authorization: Bearer <access token>`；成功 **204** | `logout.go`；swift `AuthClient.swift` L1094–1113 |
| Admin 刪除端點 | `DELETE {SUPABASE_URL}/auth/v1/admin/users/{sub}` | `api.go` L345–376 |
| 認證 header（sb_secret） | `apikey: sb_secret_…`；**不帶** `Authorization`（相容形態：`Authorization: Bearer <與 apikey 完全相同的值>`）；**絕不**轉送使用者的 Bearer | 〈API keys〉L351；Discussion #29260 |
| 認證 header（legacy，不採用） | `apikey: <service_role JWT>`＋`Authorization: Bearer <service_role JWT>` | `auth.go` L47–65、L96–100 |
| Request body | `Content-Type: application/json`，`{"should_soft_delete":false}`（可省略；空 body 即硬刪） | `admin.go` L37–39、L573–581 |
| 選用 header | `X-Supabase-Api-Version: 2024-01-01`：錯誤 body 改為 `{"code":"<code>","message":"…"}` | `errors.go` L141–157 |
| 成功 | **200**，body `{}` | `admin.go` L629；探針 |
| 已不存在（冪等） | **404**＋`X-Sb-Error-Code: user_not_found`；body `{"code":404,"error_code":"user_not_found","msg":"User not found"}` | `admin.go` L64–67；探針 |
| 非 UUID | 404＋`validation_failed`（**不可**當成冪等成功） | `admin.go` L56–59；探針 |
| 路徑或 base URL 錯誤 | 404 `text/plain`，沒有 error code（**不可**當成冪等成功） | 探針 |
| 權限錯誤 | 401 `no_authorization`／403 `not_admin`／403 `bad_jwt`；gateway 拒絕 apikey 時為 401 | `auth.go`、`middleware.go`；〈API keys〉 |
| cascade 範圍（硬刪） | `auth.sessions`→`auth.refresh_tokens`、`auth.identities`、`auth.mfa_*`、`auth.one_time_tokens`、`public.notes.author_id`（cascade）、`public.notes.picked_up_by`（set null） | GoTrue migrations；本 repo `20260712000000_notes.sql`；探針 |
| DB 讀取路徑 | **待裁決**：A `<私有 schema>.auth_sessions` view，或 B `<私有 schema>.session_alive(uuid)` definer 函式 | Q4.3 |

---

## 來源清單

**GoTrue（`supabase/auth` @ `v2.194.0`，commit `0e779f25db`）**
- [internal/api/logout.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/logout.go)（L21–73）
- [internal/models/sessions.go](https://github.com/supabase/auth/blob/v2.194.0/internal/models/sessions.go)（L78–101 Session、L215–233 CheckValidity、L256–267 NewSession、L357–371 Logout 系列）
- [internal/tokens/service.go](https://github.com/supabase/auth/blob/v2.194.0/internal/tokens/service.go)（L73–85、L188–654 RefreshTokenGrant、L657–735 GenerateAccessToken、L859–954 IssueRefreshToken、L1076）
- [internal/api/auth.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/auth.go)（L20–36、L47–65、L77–110、L112–158）
- [internal/api/middleware.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/middleware.go)（L187–224 requireAdminCredentials、L423–450 databaseCleanup）
- [internal/api/admin.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/admin.go)（L37–39、L52–71、L566–630）
- [internal/api/api.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/api.go)（L189–192、L271、L345–378）
- [internal/api/errors.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/errors.go)、[internal/api/apierrors/apierrors.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/apierrors/apierrors.go)、[internal/api/apierrors/errorcode.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/apierrors/errorcode.go)、[internal/api/apiversions.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/apiversions.go)
- [internal/api/anonymous.go](https://github.com/supabase/auth/blob/v2.194.0/internal/api/anonymous.go)（L12、L18、L47）
- [internal/models/cleanup.go](https://github.com/supabase/auth/blob/v2.194.0/internal/models/cleanup.go)（L52–89）；[internal/conf/configuration.go](https://github.com/supabase/auth/blob/v2.194.0/internal/conf/configuration.go)（L134、L150、L1094–1096）
- [internal/models/user.go](https://github.com/supabase/auth/blob/v2.194.0/internal/models/user.go)（L630–632、L975–1068）；[internal/models/refresh_token.go](https://github.com/supabase/auth/blob/v2.194.0/internal/models/refresh_token.go)（L87–109）
- [migrations/20220811173540_add_sessions_table.up.sql](https://github.com/supabase/auth/blob/v2.194.0/migrations/20220811173540_add_sessions_table.up.sql)、[migrations/20240612123726_enable_rls_update_grants.up.sql](https://github.com/supabase/auth/blob/v2.194.0/migrations/20240612123726_enable_rls_update_grants.up.sql)、[migrations/20251007112900_add_session_refresh_token_columns.up.sql](https://github.com/supabase/auth/blob/v2.194.0/migrations/20251007112900_add_session_refresh_token_columns.up.sql)
- [openapi.yaml](https://github.com/supabase/auth/blob/v2.194.0/openapi.yaml)（L196–219 /logout、L1623–1647 admin delete、APIKeyAuth）
- [PR #1497 fix: add cleanup statement for anonymous users](https://github.com/supabase/auth/pull/1497)
- 版本比對：[v2.194.0...v2.196.0](https://github.com/supabase/auth/compare/v2.194.0...v2.196.0)

**supabase-swift（v2.55.2）**
- [Sources/Auth/AuthClient.swift](https://github.com/supabase/supabase-swift/blob/v2.55.2/Sources/Auth/AuthClient.swift)（L201–203、L1090–1120）
- [Sources/Auth/Types.swift](https://github.com/supabase/supabase-swift/blob/v2.55.2/Sources/Auth/Types.swift)（L1197–1209）
- [Sources/Auth/Internal/APIClient.swift](https://github.com/supabase/supabase-swift/blob/v2.55.2/Sources/Auth/Internal/APIClient.swift)（L50–65）
- [Sources/Supabase/SupabaseClient.swift](https://github.com/supabase/supabase-swift/blob/v2.55.2/Sources/Supabase/SupabaseClient.swift)（L242–251）

**supabase-js（v2.116.0）**
- [packages/core/auth-js/src/GoTrueAdminApi.ts](https://github.com/supabase/supabase-js/blob/v2.116.0/packages/core/auth-js/src/GoTrueAdminApi.ts)（L836–854）
- [packages/core/supabase-js/src/SupabaseClient.ts](https://github.com/supabase/supabase-js/blob/v2.116.0/packages/core/supabase-js/src/SupabaseClient.ts)（L649–652）、[src/lib/fetch.ts](https://github.com/supabase/supabase-js/blob/v2.116.0/packages/core/supabase-js/src/lib/fetch.ts)（L24–95）

**Supabase 官方文件**（原始檔：`supabase/supabase` `apps/docs/content/…`）
- [User sessions](https://supabase.com/docs/guides/auth/sessions)
- [Signing out](https://supabase.com/docs/guides/auth/signout)
- [JWT Claims Reference](https://supabase.com/docs/guides/auth/jwt-fields)
- [Managing user data](https://supabase.com/docs/guides/auth/managing-user-data)（Deleting users／Removing account access）
- [Users](https://supabase.com/docs/guides/auth/users)
- [Anonymous Sign-Ins](https://supabase.com/docs/guides/auth/auth-anonymous)
- [API keys](https://supabase.com/docs/guides/getting-started/api-keys)
- [Migrating to publishable and secret API keys](https://supabase.com/docs/guides/getting-started/migrating-to-new-api-keys)
- [JWT Signing Keys](https://supabase.com/docs/guides/auth/signing-keys)（FAQ：hosted gateway 如何處理 publishable／secret key）
- [Authorization headers (Edge Functions)](https://supabase.com/docs/guides/functions/auth-headers)
- [Self-hosted: Envoy API Gateway](https://supabase.com/docs/guides/self-hosting/self-hosted-envoy)（Authentication 節）、[Self-hosted: New API keys](https://supabase.com/docs/guides/self-hosting/self-hosted-auth-keys)

**Supabase 官方公告**
- [Discussion #29260 — Upcoming changes to Supabase API Keys](https://github.com/orgs/supabase/discussions/29260)（時程表、Authorization header 規則）
- [Discussion #34270 — Restricting Access on Auth, Storage, and Realtime Schemas on April 21, 2025](https://github.com/orgs/supabase/discussions/34270)

**Postgres 映像與 CLI**
- [supabase/postgres init-scripts/00000000000001-auth-schema.sql](https://github.com/supabase/postgres/blob/develop/migrations/db/init-scripts/00000000000001-auth-schema.sql)、[00000000000000-initial-schema.sql](https://github.com/supabase/postgres/blob/develop/migrations/db/init-scripts/00000000000000-initial-schema.sql)
- [migrations/10000000000000_demote-postgres.sql](https://github.com/supabase/postgres/blob/develop/migrations/db/migrations/10000000000000_demote-postgres.sql)、[20211115181400_update-auth-permissions.sql](https://github.com/supabase/postgres/blob/develop/migrations/db/migrations/20211115181400_update-auth-permissions.sql)、[20250421084701_revoke_admin_roles_from_postgres.sql](https://github.com/supabase/postgres/blob/develop/migrations/db/migrations/20250421084701_revoke_admin_roles_from_postgres.sql)
- 版本比對：[17.6.1.143...17.6.1.147](https://github.com/supabase/postgres/compare/17.6.1.143...17.6.1.147)（無 migration 變動）
- [supabase/cli apps/cli-go/internal/db/start/start.go](https://github.com/supabase/cli/blob/develop/apps/cli-go/internal/db/start/start.go)（L318–347 `initAuthJob`）
- [gobuffalo/pop v6.1.1 dialect_common.go](https://github.com/gobuffalo/pop/blob/v6.1.1/dialect_common.go)（L140–147 `genericDestroy`）

**本機探針**（2026-09-22，不留存於 repo）：`gotrue:v2.196.0`＋`postgres:17.6.1.143`＋本 repo 全部 migration。驗證了 1.2、1.3、1.5、2.2、2.3、2.4、4.1、4.3 的行為；結束後容器已全數移除。
