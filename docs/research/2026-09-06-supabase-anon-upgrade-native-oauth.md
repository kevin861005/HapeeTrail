# Supabase 匿名帳號 → Google/Apple 原生升級流程研究

日期：2026-09-06
範圍：iOS 原生 App（supabase-swift）匿名登入 → Apple/Google「升級/連結」流程、Supabase 儀表板設定、GoTrue（`supabase/auth`）伺服器行為、對 HapeeTrail Java 後端與 `docs/api/` 的影響。

方法論：只信一手資料——Supabase 官方文件（supabase.com/docs）、`supabase-swift` GitHub repo（原始碼，用 `gh api` 直接抓 raw content）、`supabase/auth`（GoTrue）GitHub repo 原始碼。文件沒講清楚、必須讀原始碼才能確認的結論，一律標記「**推論自原始碼**」並附精確檔案路徑（可能含行號）。search engine 只用來定位官方頁面/檔案，不作為結論依據。

---

## 一頁結論

**升級路徑一句話**：iOS 端必須呼叫 `supabase.auth.linkIdentityWithIdToken(credentials:)`（supabase-swift ≥ v2.32.0，2025-09-15 併入 main，PR [#776](https://github.com/supabase/supabase-swift/commit/661e3218d18235179efc30c33b456a8f485f9ff7)），**絕不能**在已有匿名 session 時呼叫一般的 `signInWithIdToken(credentials:)` 來做升級——後者在伺服器端完全不看目前的 Authorization header，只會依 email/identity 比對「登入既有帳號」或「建立新帳號」，兩種結果都會讓匿名 UUID 底下累積的便條資料變成孤兒。這是**推論自原始碼**（`supabase/auth` 的 `internal/api/token_oidc.go`、`internal/api/external.go`、`internal/api/identity.go`），官方文件從未把「`signInWithIdToken` 不會連結匿名 session」這件事講清楚。

**兩個 Provider 各要設定什麼**：
- **Apple（原生）**：Supabase 儀表板 Apple provider 的「Client IDs」欄位只需填 App 的 **Bundle ID**；Services ID、Team ID、Key ID、.p8 私鑰只有 web OAuth flow 才需要，且只有 web flow 需要每 6 個月輪替 secret。原生 nonce 流程：產生 raw nonce → SHA256 雜湊後傳給 `ASAuthorizationAppleIDRequest.nonce` → **raw nonce**（未雜湊）傳給 Supabase 的 `OpenIDConnectCredentials(nonce:)`；GoTrue 伺服器會自己算 SHA256 比對 ID token 裡的 hashed nonce claim。
- **Google（原生）**：Google Cloud Console 建立 **Web application** 與 **iOS** 兩個 OAuth Client；Supabase 儀表板 Google provider 的「Client IDs」欄位把兩個 Client ID 用逗號串起來（Web 放前面），並且要開「**Skip nonce check**」——這是因為 Google 原生 iOS SDK 的 ID token 不帶雜湊 nonce claim，若不跳過檢查會 100% 驗證失敗（GoTrue 已知問題，`supabase/auth#412`）。

**後端 JWT 有沒有變**：`sub`（UUID）、`iss`、簽章演算法（ES256）全部**不變**——這是**推論自原始碼**：升級走的是 `linkIdentityToUser`，操作對象是 `getTargetUser(ctx)`，也就是目前已認證那個 user row 本身，不建立新 row；只把該 row 的 `is_anonymous` 從 `true` 改成 `false`（`internal/api/identity.go`）。緊接著同一次 request 內就會用這個已更新的 user 重發一張新 JWT（`internal/tokens/service.go`），所以 `linkIdentityWithIdToken` **回傳的新 session 已經是 `is_anonymous:false`**，不必等下一次 refresh。**HapeeTrail Java 後端不需要改**——它只驗 `iss`／`exp`／`sub` 是 UUID，這三者升級前後完全一致，且目前完全沒用到 `is_anonymous`。

**致命陷阱清單**：
1. 絕不可用 `signInWithIdToken`（不帶 `link_identity`）在已有匿名 session 時嘗試升級——伺服器端完全不看 Authorization header，只會用 email 比對既有帳號或建立新帳號，兩者都讓匿名期間的便條資料跟丟。必須用 `linkIdentityWithIdToken`。
2. `linkIdentity` 全家族在官方文件裡被統一標成「Manual Linking（beta）」，暗示都要開儀表板的「Enable Manual Linking」開關——但**推論自原始碼**：這個開關（`requireManualLinkingEnabled` middleware）在 `internal/api/api.go` 只掛在 `/user/identities/authorize`（web 重導向 linkIdentity）與 `DELETE /user/identities/{id}`（unlink）兩條路由上；`POST /token?grant_type=id_token`（`linkIdentityWithIdToken` 走的端點）完全沒有這個 middleware。**也就是說本專案要用的原生升級路徑，理論上不需要打開「Enable Manual Linking」**——但這點文件完全沒講、且與文件字面意思矛盾，落地前務必用測試帳號實際打一次確認（見「對本專案的影響」章節的待驗證項）。
3. **關掉 email/password 自助註冊時，不能直接關「Allow new users to sign up」（`disable_signup`）這個全域開關**——推論自原始碼：`internal/api/anonymous.go` 的 `SignupAnonymously()` 一開頭就檢查 `config.DisableSignup`，關了這個全域開關會連匿名登入一起關掉（回 422 `signup_disabled`）。正確做法是只關 Dashboard → Auth → Providers → **Email** provider（`config.External.Email.Enabled = false`），這個開關只影響 `internal/api/signup.go` 的 Email 分支，不影響 `SignupAnonymously`。
4. 若要綁的 Google/Apple 身分已經連到「另一個」Supabase 使用者（例如使用者換裝置、匿名帳號重新產生後想連回舊帳號），`linkIdentityWithIdToken` 會直接報 422 `identity_already_exists`（訊息 `"Identity is already linked to another user"`），**不會**自動合併兩邊資料——已知行為，有實際 GitHub issue 佐證（`supabase/auth#1525`，狀態 open）。iOS 端要設計這個衝突的 UX。
5. Apple 只在**第一次**授權時回傳 full name，之後永遠是 `null`；iOS 端必須第一次拿到就存起來，事後透過 `updateUser()` 補寫使用者資料（Supabase 官方文件明確提醒此點）。
6. 匿名帳號**沒有官方自動清理機制**——官方文件原文：「Automatic cleanup of anonymous users is currently not available」，需要自己排程刪除；官方給的 SQL 範例是刪 30 天前建立且 `is_anonymous = true` 的 row。
7. 匿名登入內建 IP-based rate limit（每小時 30 次，可在儀表板調），官方建議搭配 CAPTCHA/Cloudflare Turnstile 防濫用。

---

## 逐題詳答

### Q1. 匿名 → 永久帳號的升級路徑；UUID 是否保證不變

官方文件 [Anonymous Sign-Ins](https://supabase.com/docs/guides/auth/auth-anonymous)（原始檔 [auth-anonymous.mdx](https://github.com/supabase/supabase/blob/master/apps/docs/content/guides/auth/auth-anonymous.mdx)）給了兩條轉正路徑：

> "You can use the `updateUser()` method to link an email or phone identity to the anonymous user." （email/phone：`updateUser({ email })` → 驗證 → 再 `updateUser({ password })` 補密碼）
> "Alternatively, you can use the `linkIdentity()` method to link an OAuth identity to the anonymous user." （OAuth，如 Google/Apple）

本專案不開放 email/password 自助註冊，所以只用得到 `linkIdentity` 這條路（且如下題所述，原生環境要用它的 ID-token 變體）。

文件本身**沒有明講** UUID 是否保持不變，但可從 GoTrue 原始碼確認（詳見 Q2）：連結流程操作的是「目前已認證使用者」的既有 row，不建立新 `user`，只新增一筆 `identities` 紀錄並把 `is_anonymous` 改成 `false`。因此 **UUID（`sub`）保證不變**——這點是**推論自原始碼**（`supabase/auth` `internal/api/identity.go` 函式 `linkIdentityToUser`），文件從未白紙黑字寫「UUID 不變」。

文件也提到轉正時的資料衝突處理是開發者自己的責任（三種常見做法：用匿名資料覆蓋既有帳號、用既有帳號覆蓋匿名資料、或合併）——這與本專案「同一 UUID 延續、便條不能斷」的裁決一致，因為我們的情境根本不會走到「衝突合併」分支：`linkIdentityToUser` 是純粹的「在既有匿名 row 上加一個身分」，沒有兩個帳號要合併。

來源：[supabase.com/docs/guides/auth/auth-anonymous](https://supabase.com/docs/guides/auth/auth-anonymous)

### Q2. 原生 ID Token 流程與升級的交互——`signInWithIdToken` 到底會不會連結匿名使用者

這是本次研究最關鍵、文件完全沒講清楚、必須讀原始碼才能確認的問題。

**結論：不會連結。`signInWithIdToken` 在有匿名 session 時，伺服器端完全忽略目前的 Authorization header，純粹用 email/identity 比對決定「登入既有帳號」或「建立新帳號」，兩者都跟目前的匿名 UUID 無關，匿名帳號會被晾在一邊（其資料變孤兒）。正確的原生升級 API 是 `linkIdentityWithIdToken`。**

#### 2.1 supabase-swift 端的兩個方法，行為完全不同

**推論自原始碼**（`github.com/supabase/supabase-swift`，`Sources/Auth/AuthClient.swift`，用 `gh api repos/supabase/supabase-swift/contents/Sources/Auth/AuthClient.swift` 直接抓源碼確認）：

```swift
// line ~510: signInWithIdToken —— 不帶 Authorization header，永遠是「新登入」
public func signInWithIdToken(credentials: OpenIDConnectCredentials) async throws -> Session {
  try await _signIn(
    request: .init(
      url: configuration.url.appendingPathComponent("token"),
      method: .post,
      query: [URLQueryItem(name: "grant_type", value: "id_token")],
      body: configuration.resolvedEncoder.encode(credentials)
    )
  )
}

// line ~572: _signIn 直接覆蓋目前的 session，不管原本是不是匿名
private func _signIn(request: HTTPRequest) async throws -> Session {
  let session = try await api.execute(request).decoded(as: Session.self, decoder: configuration.resolvedDecoder)
  await sessionManager.update(session)
  eventEmitter.emit(.signedIn, session: session)
  return session
}

// line ~1358: linkIdentityWithIdToken —— 帶目前 session 的 access token 當 Authorization，且在 body 裡加 link_identity=true
public func linkIdentityWithIdToken(credentials: OpenIDConnectCredentials) async throws -> Session {
  var credentials = credentials
  credentials.linkIdentity = true
  let session = try await api.execute(
    .init(
      url: configuration.url.appendingPathComponent("token"),
      method: .post,
      query: [URLQueryItem(name: "grant_type", value: "id_token")],
      headers: [.authorization: "Bearer \(session.accessToken)"],   // ← 關鍵差異
      body: configuration.resolvedEncoder.encode(credentials)
    )
  ).decoded(as: Session.self, decoder: configuration.resolvedDecoder)
  await sessionManager.update(session)
  eventEmitter.emit(.userUpdated, session: session)   // ← 事件也不同：userUpdated 而非 signedIn
  return session
}
```

兩者打的都是 `POST /token?grant_type=id_token`，差別只在於 `linkIdentityWithIdToken` 多帶了目前 session 的 `Authorization: Bearer` header，並在 body 加了 `link_identity: true`。

**`linkIdentityWithIdToken` 不在官方文件上**：`https://supabase.com/docs/reference/swift/auth-linkidentitywithidtoken` 回傳 404（已用 WebFetch 實測確認）。但它確實存在於 SDK 原始碼，且被 SDK 自己的 Examples app 用來示範原生 Apple 連結流程（`Examples/Examples/Profile/UserIdentityList.swift`）：

```swift
try await supabase.auth.linkIdentityWithIdToken(
  credentials: OpenIDConnectCredentials(provider: .apple, idToken: identityToken)
)
```

也在 SDK 自己的測試套件裡（`Tests/AuthTests/AuthClientTests.swift`）。此方法由 PR [#776](https://github.com/supabase/supabase-swift/commit/661e3218d18235179efc30c33b456a8f485f9ff7)「feat(auth): implement linkIdentity with OIDC」在 2025-09-15 併入，同日發布的 `v2.32.0` 是最早可能包含此功能的版本（保守建議要求 `v2.33.0`（2025-09-22）以上）。截至查證當下最新版是 `v2.55.1`（2026-08-13），所以只要 iOS 端用近期版本的 SDK 就沒問題。

來源：[github.com/supabase/supabase-swift](https://github.com/supabase/supabase-swift)（原始碼直接讀取）；PR: [supabase/supabase-swift@661e321](https://github.com/supabase/supabase-swift/commit/661e3218d18235179efc30c33b456a8f485f9ff7)

#### 2.2 GoTrue 伺服器端：同一個端點，靠 `link_identity` + Authorization header 分流

**推論自原始碼**（`github.com/supabase/auth`，`internal/api/token_oidc.go`，直接讀原始碼確認）：

```go
type IdTokenGrantParams struct {
    IdToken      string `json:"id_token"`
    AccessToken  string `json:"access_token"`
    Nonce        string `json:"nonce"`
    Provider     string `json:"provider"`
    ClientID     string `json:"client_id"`
    Issuer       string `json:"issuer"`
    LinkIdentity bool   `json:"link_identity"`   // ← supabase-swift 設的那個欄位
}

func (a *API) IdTokenGrant(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
    ...
    if params.LinkIdentity {
        if r.Header.Get("Authorization") == "" {
            return apierrors.NewOAuthError("invalid request", "Linking requires a valid user access token in Authorization")
        }
        requireAuthCtx, err := a.requireAuthentication(w, r)
        ...
        targetUser := getUser(requireAuthCtx)
        ...
        ctx = withTargetUser(ctx, targetUser)   // ← 把「目前登入者」（匿名使用者）記下來，等下要連結到它
    }
    ...
    if params.LinkIdentity {
        user, terr = a.linkIdentityToUser(r, ctx, tx, userData, providerType)   // ← 連到既有 targetUser
    } else {
        decision, user, terr = a.createAccountFromExternalIdentity(tx, r, userData, providerType, emailOptional)  // ← 依 email 決定登入既有帳號或建新帳號，與目前 session 無關
    }
    ...
    token, terr = a.issueRefreshToken(r, w.Header(), tx, user, models.OAuth, grantParams)  // ← 同一個請求內直接發新 JWT
}
```

`a.linkIdentityToUser`（`internal/api/identity.go`）：

```go
func (a *API) linkIdentityToUser(r *http.Request, ctx context.Context, tx *storage.Connection, userData *provider.UserProvidedData, providerType string) (*models.User, error) {
    targetUser := getTargetUser(ctx)   // ← 就是那個匿名 user，UUID 不變
    identity, terr := models.FindIdentityByIdAndProvider(tx, userData.Metadata.Subject, providerType)
    ...
    if identity != nil {
        if identity.UserID == targetUser.ID {
            return nil, apierrors.NewUnprocessableEntityError(apierrors.ErrorCodeIdentityAlreadyExists, "Identity is already linked")
        }
        return nil, apierrors.NewUnprocessableEntityError(apierrors.ErrorCodeIdentityAlreadyExists, "Identity is already linked to another user")
    }
    identity, terr = a.createNewIdentity(tx, targetUser, providerType, structs.Map(userData.Metadata))
    ...
    if targetUser.GetEmail() == "" {
        // 從 Apple/Google claim 補上 email、confirm 帳號
        ...
        if targetUser.IsAnonymous {
            targetUser.IsAnonymous = false
            if terr := tx.UpdateOnly(targetUser, "is_anonymous"); terr != nil { return nil, terr }
        }
    }
    ...
    return targetUser, nil   // ← 回傳的還是同一個 user
}
```

`createAccountFromExternalIdentity`（`signInWithIdToken` 非連結路徑走的函式，`internal/api/external.go` line 301+）完全不讀 Authorization header 對應的 user，而是呼叫 `models.DetermineAccountLinking(...)` 純粹用 email 判斷要 `LinkAccount`（合併進某個既有帳號——但那個帳號是「email 相符的帳號」，不是目前的匿名 session）還是 `CreateAccount`（建一個全新 user）。這就是「不會連結匿名 session」的鐵證。

**結論同時回答 Q1 的「UUID 是否保證不變」**：`linkIdentityToUser` 從頭到尾只操作 `targetUser`（同一個 `*models.User`，同一個 `ID`），沒有任何一行程式碼改過 `targetUser.ID`。UUID 100% 不變。

來源：[github.com/supabase/auth](https://github.com/supabase/auth)（`internal/api/token_oidc.go`、`internal/api/identity.go`、`internal/api/external.go`，原始碼直接讀取）

### Q3. Apple 原生設定

官方文件 [Login with Apple](https://supabase.com/docs/guides/auth/social-login/auth-apple)（原始檔：[auth-apple.mdx](https://github.com/supabase/supabase/blob/master/apps/docs/content/guides/auth/social-login/auth-apple.mdx)）：

- 儀表板 Apple provider 的「Client IDs」欄位，**原生 App 只需填 Bundle ID**：「Register all of the App IDs that will be using your Supabase project in the Apple provider configuration in the Supabase dashboard under Client IDs.」App 端要先在 Apple Developer 的 App ID 上打開「Sign in with Apple」capability。
- 若專案同時支援 web 與原生：「If your project also uses native Sign in with Apple (for example on iOS, Expo, or Flutter), list this Services ID as the first entry in the Client IDs field.」——意思是 web 的 Services ID 要跟原生的 Bundle ID 一起（逗號分隔）放進同一個「Client IDs」欄位，Services ID 放第一個。
- **Team ID、Services ID、Key ID、.p8 簽章金鑰只有設定 web OAuth flow 才需要**；文件明講金鑰輪替（每 6 個月）的要求也只針對「configuring OAuth settings (Services ID, signing key, etc.)」，原生流程不需要輪替。本專案若完全不做 web OAuth（純原生），理論上可以不建立 Services ID / .p8 金鑰，只填 Bundle ID 到 Client IDs 欄位。

**Nonce 處理**：官方文件的 Flutter 範例把流程講得最完整（Swift 專屬範例反而沒示範 nonce，見下段落）：

```dart
final rawNonce = supabase.auth.generateRawNonce();
final hashedNonce = sha256.convert(utf8.encode(rawNonce)).toString();

final credential = await SignInWithApple.getAppleIDCredential(
  scopes: [...],
  nonce: hashedNonce,     // ← 雜湊過的 nonce 給 Apple
);
...
final authResponse = await supabase.auth.signInWithIdToken(
  provider: OAuthProvider.apple,
  idToken: idToken,
  nonce: rawNonce,        // ← 原始（未雜湊）nonce 給 Supabase
);
```

這個「raw → 給 Supabase；SHA256(raw) → 給 Apple」的方向，與 GoTrue 伺服器端驗證邏輯完全吻合（**推論自原始碼**，`internal/api/token_oidc.go`）：

```go
hash := fmt.Sprintf("%x", sha256.Sum256([]byte(params.Nonce)))
if hash != idToken.Nonce {
    return apierrors.NewOAuthError("invalid nonce", "Nonces mismatch")
}
```

即伺服器拿到客戶端傳來的 `nonce`（應為 raw），自己算 SHA256，去比對 Apple ID token 裡的 `nonce` claim（Apple 會把開發者傳給它的 hashed nonce 原封不動放進 token）。Swift 版官方文件目前的範例程式碼沒有展示 nonce 參數（只傳 `idToken`），這應是文件遺漏；正確做法要對照 Flutter 範例＋GoTrue 源碼補上 nonce。**supabase-swift 本身沒有內建 `generateRawNonce()` 這類工具函式**（已在 `AuthClient.swift` 全文搜尋確認不存在），iOS 端需要自己用 `CryptoKit`／`SecRandomCopyBytes` 產生 raw nonce 字串並算 SHA256（這是 Apple 官方 Sign in with Apple 範例程式碼的標準做法，非 Supabase 特有）。

來源：[supabase.com/docs/guides/auth/social-login/auth-apple](https://supabase.com/docs/guides/auth/social-login/auth-apple)；GoTrue nonce 驗證：`internal/api/token_oidc.go`（`github.com/supabase/auth`，原始碼直接讀取）

### Q4. Google 原生設定

官方文件 [Login with Google](https://supabase.com/docs/guides/auth/social-login/auth-google)（原始檔：[auth-google.mdx](https://github.com/supabase/supabase/blob/master/apps/docs/content/guides/auth/social-login/auth-google.mdx)）：

- **Google Cloud Console**：為 iOS 原生流程建立一個 **iOS 類型**的 OAuth Client ID（不是 Web 類型），需要 App 的 Bundle ID，若 App 已上架還要填 App Store ID、Team ID。另外仍需要一個 **Web application** 類型的 OAuth Client ID（Supabase 後端驗證 ID token 的 audience 需要它）。
- **Supabase 儀表板**：Google provider 的「Client IDs」欄位把 **Web Client ID 與 iOS Client ID 用逗號隔開**填入同一欄（"Add web client ID and iOS client ID from step 1 ... under Client IDs, separated by a comma"）。
- 必須打開「**Skip nonce check**」——這是 Google 原生 iOS SDK（GIDSignIn）的已知限制：iOS 端不會把雜湊 nonce 放進 ID token，若不跳過檢查，GoTrue 的 nonce 比對邏輯會 100% 失敗。這點官方文件有寫，也對應到一個已知問題追蹤（`supabase/auth#412`「Known issues with the id token grant type endpoint」，內容確認 Google ID token 不含 hashed nonce，這是 Google 端本身的行為，不是 Supabase 的 bug）。

iOS 端程式碼範例（官方文件）：

```swift
let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: self)
guard let idToken = result.user.idToken?.tokenString else { ... }
let accessToken = result.user.accessToken.tokenString
try await supabase.auth.signInWithIdToken(
  credentials: OpenIDConnectCredentials(provider: .google, idToken: idToken, accessToken: accessToken)
)
```

本專案要用的是升級版：把 `signInWithIdToken` 換成 `linkIdentityWithIdToken`（見 Q2），其餘（GIDSignIn 取得 idToken/accessToken 的方式）不變。

來源：[supabase.com/docs/guides/auth/social-login/auth-google](https://supabase.com/docs/guides/auth/social-login/auth-google)；Google nonce 已知問題：[github.com/supabase/auth/issues/412](https://github.com/supabase/auth/issues/412)

### Q5. 關閉自行註冊——「匿名＋Google＋Apple 可以，email/password 不行」怎麼設

官方文件對「disable_signup 是否影響匿名」沒有明講；这题**完全靠讀 GoTrue 原始碼確認**，而且找到的答案跟直覺不同，是本次研究第二個重要陷阱：

**推論自原始碼**，`internal/api/api.go` 裡 `/signup` 路由的分流邏輯：

```go
r.With(api.verifyCaptcha).Route("/signup", func(r *router) {
    ...
    // body 沒有 email/phone → 走匿名分支
    if !api.config.External.AnonymousUsers.Enabled {
        return apierrors.NewUnprocessableEntityError(apierrors.ErrorCodeAnonymousProviderDisabled, "Anonymous sign-ins are disabled")
    }
    ...
    return api.SignupAnonymously(w, r)
    ...
    // 否則走一般 email/phone 分支
    return api.Signup(w, r)
})
```

`internal/api/anonymous.go` 的 `SignupAnonymously`：

```go
func (a *API) SignupAnonymously(w http.ResponseWriter, r *http.Request) error {
    ...
    if config.DisableSignup {   // ← 全域「Allow new users to sign up」開關
        return apierrors.NewUnprocessableEntityError(apierrors.ErrorCodeSignupDisabled, "Signups not allowed for this instance")
    }
    ...
}
```

也就是說：**`DisableSignup`（儀表板的「Allow new users to sign up」）這個全域開關，會同時擋住一般 signup 跟匿名 signup**。這跟官方 [General Configuration](https://supabase.com/docs/guides/auth/general-configuration) 文件把「Allow new users to sign up」跟「Allow anonymous sign-ins」列成兩個獨立設定項的印象不同——它們確實是兩個獨立的 config 值（`DisableSignup` vs `AnonymousUsers.Enabled`），但 `SignupAnonymously` 的程式碼**同時檢查兩者**，任一個關掉都會擋住匿名登入。

同時，`internal/api/external.go` 的 `createAccountFromExternalIdentity`（也就是 `signInWithIdToken` 非連結路徑，第一次用 Google/Apple 建立全新帳號時）也會檢查 `config.DisableSignup`（`models.CreateAccount` 分支才檢查；`models.LinkAccount`——自動合併進 email 相符的既有帳號——不檢查）。而 `linkIdentityWithIdToken` 走的 `internal/api/token_oidc.go` 的 `IdTokenGrant`，**完全沒有檢查 `config.DisableSignup`**（已讀過該函式全文確認）——因為升級走的是「已有帳號」，不算「新用戶」。

**因此，本專案要做到「匿名＋Google＋Apple 可以，email/password 自助註冊不行」的正確設定是**：

1. **不要關閉**儀表板的「Allow new users to sign up」（`DisableSignup` 保持 `false`）——關了會連匿名登入、以及第一次用 Google/Apple 直接登入（非匿名升級，走 `createAccountFromExternalIdentity` 的 CreateAccount 分支）都一起擋掉。
2. **關閉** Dashboard → Authentication → Sign In / Providers → **Email** provider（`config.External.Email.Enabled = false`）——這個開關只影響 `internal/api/signup.go` 的 `Signup()` 函式裡 `case EmailProvider` 分支（回 400 `email_provider_disabled`），不影響 `SignupAnonymously` 或 `IdTokenGrant`。
3. 保持 Anonymous provider 開啟（`GOTRUE_EXTERNAL_ANONYMOUS_USERS_ENABLED=true` / 儀表板 Anonymous 開關），Google/Apple provider 開啟。
4. 因為 client 端也完全沒有 email/password 的 UI（產品層面本來就不提供），關 Email provider 是防止有人繞過 App 直接打 GoTrue API 自助註冊的第二道防線。

來源（文件面，確認「兩個開關」的存在）：[supabase.com/docs/guides/auth/general-configuration](https://supabase.com/docs/guides/auth/general-configuration)；判斷兩者交互作用**推論自原始碼**：`internal/api/api.go`（`/signup` 路由分流）、`internal/api/anonymous.go`（`SignupAnonymously`）、`internal/api/signup.go`（`Signup`，`config.DisableSignup` 檢查、Email/Phone provider 檢查）、`internal/api/external.go`（`createAccountFromExternalIdentity`）——皆為 `github.com/supabase/auth` 原始碼直接讀取確認。

### Q6. JWT 對後端的可見變化

**`is_anonymous` claim**：官方文件 [Anonymous Sign-Ins](https://supabase.com/docs/guides/auth/auth-anonymous) 確認此 claim 存在，並給出 RLS 範例：

```sql
create policy "Only permanent users can post to the news feed"
on news_feed as restrictive for insert to authenticated
with check ((select (auth.jwt()->>'is_anonymous')::boolean) is false );
```

**claim 的確切定義與寫入時機**——**推論自原始碼**，`internal/tokens/service.go`：

```go
type AccessTokenClaims struct {
    jwt.RegisteredClaims
    ...
    IsAnonymous bool `json:"is_anonymous"`
    ...
}
...
claims := &v0hooks.AccessTokenClaims{
    RegisteredClaims: jwt.RegisteredClaims{
        Subject:   params.User.ID.String(),          // ← sub = user UUID
        Audience:  jwt.ClaimStrings{params.User.Aud},
        IssuedAt:  jwt.NewNumericDate(issuedAt),
        ExpiresAt: jwt.NewNumericDate(expiresAt),
        Issuer:    config.JWT.Issuer,                 // ← iss，專案層級固定設定值，與使用者操作無關
    },
    ...
    IsAnonymous: params.User.IsAnonymous,              // ← 直接讀 DB 裡這個使用者當下的欄位值
    ...
}
```

**`sub`／`iss`／簽章演算法是否都不變**：
- `sub` = `params.User.ID.String()`，而 Q2 已證明 `linkIdentityToUser` 從不改變 `targetUser.ID`——**不變**。
- `iss` = `config.JWT.Issuer`，這是專案層級的靜態設定（即 `https://<ref>.supabase.co/auth/v1`），跟單一使用者的操作完全無關——**不變**。
- 簽章演算法（ES256 或 HS256）取決於專案的 JWT signing keys 設定（[JWT Signing Keys](https://supabase.com/docs/guides/auth/signing-keys)），同樣是專案層級設定，不因使用者升級而變——**不變**。

**token 何時輪替 / 何時反映 `is_anonymous=false`**：`IdTokenGrant` 在同一個 DB transaction 裡先執行 `linkIdentityToUser`（把 `targetUser.IsAnonymous` 改成 `false` 並寫回 DB），緊接著才呼叫 `a.issueRefreshToken(...)` 用**已更新**的 `user` 物件生成新 JWT。因此 `linkIdentityWithIdToken` **這一次呼叫的回傳值（新 `Session`）已經帶 `is_anonymous:false`**，不必等下一次 token refresh。supabase-swift 端呼叫完會立刻 `sessionManager.update(session)`，本地 session 也是同步更新——這點是**推論自原始碼**（`internal/api/token_oidc.go` 的執行順序 + `internal/tokens/service.go` 的 claims 建構 + `AuthClient.swift` 的 `linkIdentityWithIdToken` 實作），文件完全沒提這個時序保證。

實務含意：舊的、升級前簽出的 access token（若還沒過期）**仍然是 `is_anonymous:true`**，因為 JWT 是無狀態的、簽出後不會被伺服器動態改寫；只有升級呼叫回傳的新 token（以及後續每次 refresh 出來的新 token）才會是 `false`。HapeeTrail Java 後端目前不驗 `is_anonymous`，所以不受此影響；若未來要用這個 claim 做權限判斷，要注意「用戶剛升級但 App 還在用舊 token」的短暫窗口。

來源：[supabase.com/docs/guides/auth/auth-anonymous](https://supabase.com/docs/guides/auth/auth-anonymous)（`is_anonymous` claim 存在與 RLS 用法）；claim 寫入時機與欄位定義**推論自原始碼**：`internal/tokens/service.go`、`internal/api/token_oidc.go`（`github.com/supabase/auth`）

### Q7. 邊角情況

**(a) 身分已被另一個 user 使用時的錯誤**

**推論自原始碼**，`internal/api/identity.go` 的 `linkIdentityToUser`：

```go
if identity != nil {
    if identity.UserID == targetUser.ID {
        return nil, apierrors.NewUnprocessableEntityError(apierrors.ErrorCodeIdentityAlreadyExists, "Identity is already linked")
    }
    return nil, apierrors.NewUnprocessableEntityError(apierrors.ErrorCodeIdentityAlreadyExists, "Identity is already linked to another user")
}
```

錯誤碼統一是 `identity_already_exists`（HTTP 422 Unprocessable Entity，定義於 `internal/api/apierrors/errorcode.go`），訊息依情況是 `"Identity is already linked"`（同一使用者重複連同一身分）或 `"Identity is already linked to another user"`（身分屬於別人）。**不會自動合併兩個帳號的資料**——這是已知、且被回報的行為：GitHub issue [`supabase/auth#1525`](https://github.com/supabase/auth/issues/1525)「Anonymous user identity not linking」描述了完全相同的場景（使用者用 Google 登入過、App 重灌後產生新匿名帳號、想連回舊 Google 身分，結果直接報 `"Identity is already linked to another user"`），issue 目前狀態是 open，官方沒有自動合併機制。

另外還有一個相關但不同的錯誤：若升級時要把 Google/Apple 給的 email 寫回 `targetUser`（因為原本匿名帳號沒有 email），但這個 email 已經被另一個 user 佔用（DB unique constraint），會回傳 400 `email_exists`，訊息固定為 `"A user with this email address has already been registered"`（`internal/api/errors.go` 常數 `DuplicateEmailMsg`）。這與上面的 `identity_already_exists`（身分本身衝突）是两种不同情境：一个是 provider identity（provider+subject）冲突，一个是 email 冲突。

**(b) Manual linking 是否 GA**

官方文件 [Identity Linking](https://supabase.com/docs/guides/auth/auth-identity-linking)（原始檔：[auth-identity-linking.mdx](https://github.com/supabase/supabase/blob/master/apps/docs/content/guides/auth/auth-identity-linking.mdx)）把整個「manual linking」功能標成 **beta**，開啟方式是儀表板開關，或自架時設環境變數 `GOTRUE_SECURITY_MANUAL_LINKING_ENABLED=true`。

但**推論自原始碼**，這個開關（`config.Security.ManualLinkingEnabled`，middleware 名稱 `requireManualLinkingEnabled`，定義於 `internal/api/middleware.go`）實際只掛在下面這兩條路由上（`internal/api/api.go`）：

```go
r.Route("/identities", func(r *router) {
    r.Use(api.requireManualLinkingEnabled)
    r.Get("/authorize", api.LinkIdentity)      // ← web 重導向版 linkIdentity()
    r.Delete("/{identity_id}", api.DeleteIdentity)  // ← unlink
})
```

`POST /token`（`linkIdentityWithIdToken` 走的端點）**完全沒有掛這個 middleware**——已讀過 `IdTokenGrant` 全函式與其呼叫鏈確認。也就是說**官方文件把「原生 ID-token 連結」跟「web 重導向連結」都歸在同一個「Manual Linking beta」標題下講，但從程式碼看，那個開關實際只管 web 版跟 unlink，不管原生 ID-token 版**。這是文件與原始碼之間的落差，屬於本次研究**最需要落地驗證**的一點（見下方「對本專案的影響」）。

**(c) email 衝突（Apple/Google 的 email 已存在於另一帳號）**

依情境分兩種：
- **走 `linkIdentityWithIdToken`（本專案用的升級路徑）**：如上題所述，若目標帳號原本沒有 email（典型匿名帳號情況）、要補寫的 email 已被別人佔用，回 400 `email_exists`。
- **走 `signInWithIdToken`（非升級、單純用 Google/Apple 登入）**：`createAccountFromExternalIdentity` 呼叫 `models.DetermineAccountLinking(...)` 依 email 決定 `LinkAccount`（自動把這個新身分掛到 email 相符的既有帳號上）還是 `CreateAccount`；這是 Supabase 的「Automatic Linking」機制（[Identity Linking](https://supabase.com/docs/guides/auth/auth-identity-linking) 文件原文：「Supabase Auth automatically links identities with the same email address to a single user」），與本專案的升級流程無關，僅供完整性參考。

來源：[supabase.com/docs/guides/auth/auth-identity-linking](https://supabase.com/docs/guides/auth/auth-identity-linking)；GitHub issue：[supabase/auth#1525](https://github.com/supabase/auth/issues/1525)；middleware 掛載範圍**推論自原始碼**：`internal/api/api.go`、`internal/api/middleware.go`、`internal/api/token_oidc.go`（`github.com/supabase/auth`）

### Q8. 匿名帳號清理

官方文件 [Anonymous Sign-Ins](https://supabase.com/docs/guides/auth/auth-anonymous) 原文：

> "Automatic cleanup of anonymous users is currently not available."

官方建議自行排程刪除，並給出範例 SQL：

```sql
delete from auth.users
where is_anonymous is true and created_at < now() - interval '30 days';
```

同一份文件也提醒匿名登入內建 **IP-based rate limit：每小時 30 次**（可在儀表板調整），並建議搭配 invisible CAPTCHA 或 Cloudflare Turnstile 防濫用——這與清理棄置帳號是同一套「防止匿名登入被濫用來灌爆資料庫」的配套建議。

來源：[supabase.com/docs/guides/auth/auth-anonymous](https://supabase.com/docs/guides/auth/auth-anonymous)

---

## 對本專案的影響

### Java 後端要不要改——不用，證據如下

HapeeTrail Java 服務目前只驗三件事：ES256 簽章、`iss` 等於 `https://<ref>.supabase.co/auth/v1`、`exp` 未過期、`sub` 是 UUID。Q6 已用原始碼證明：

- `sub` 在整個升級流程中都是 `targetUser.ID`，從未被改寫（`internal/api/identity.go`）。
- `iss` 與簽章演算法都是專案層級靜態設定（`config.JWT.Issuer`、JWT signing keys config），與單一使用者的連結操作無關。

因此**升級前後，後端驗 JWT 的三個條件（`iss`/`exp`/`sub` 格式）完全不受影響，不需要改動驗證邏輯**。`is_anonymous` claim 目前後端沒有讀取；若未來想用它做「訪客 vs 正式會員」的權限區分（例如某些操作只開放正式會員），可以直接加一行讀取該 claim，不影響既有驗證流程,但要注意 Q6 提到的「舊 token 尚未反映升級」的短暫視窗（不影響 UUID 延續，只影響 `is_anonymous` 這個值本身的即時性）。

### `docs/api/` 契約文件要不要補

`docs/api/` 定位是「HapeeTrail Java 服務」的介面契約（給 iOS 夥伴），依 CLAUDE.md 規定不放 client 語言程式碼、不替 iOS 做實作決定。Supabase 的匿名/升級流程完全發生在 iOS ↔ GoTrue 之間，不經過 Java 服務，所以**不需要**在 `docs/api/` 裡新增 Supabase 呼叫的細節（那會違反「不放 client 語言/不替 iOS 做實作決定」的界線）。唯一值得補的是：若後端文件曾經隱含假設「`sub` 對應的使用者從登入到現在都是同一種狀態」，可以在相關 endpoint 文件加一句「使用者可能從匿名升級為正式帳號，但 `sub` 不變、既有資料自動延續」，純粹是澄清性質，不是新契約。是否要補由你決定，非本研究裁決範圍。

### iOS 夥伴要做的事清單

1. **升級 supabase-swift 到 v2.33.0 以上**（`linkIdentityWithIdToken` 所需的最低版本；建議直接用最新版）。
2. Apple：
   - Apple Developer 的 App ID 開 Sign in with Apple capability。
   - Supabase 儀表板 Apple provider 的 Client IDs 填 Bundle ID（若同時要 web flow，才需要 Services ID/Team ID/Key ID/.p8）。
   - AuthenticationServices 拿到 `ASAuthorizationAppleIDCredential` 後，自己產生 raw nonce（`SecRandomCopyBytes` 或等效方法）、算 SHA256 給 `ASAuthorizationAppleIDRequest.nonce`，raw nonce 傳給 `OpenIDConnectCredentials(nonce:)`（supabase-swift 沒有內建產生 nonce 的工具函式，需自己寫）。
   - 只在第一次授權時拿到 full name，要立刻存起來（之後 Apple 不會再給）。
3. Google：
   - Google Cloud Console 建 Web + iOS 兩個 OAuth Client ID。
   - Supabase 儀表板 Google provider 的 Client IDs 填「Web Client ID,iOS Client ID」（逗號分隔），且打開「Skip nonce check」。
4. 升級呼叫一律用：
   ```swift
   try await supabase.auth.linkIdentityWithIdToken(
     credentials: OpenIDConnectCredentials(provider: .apple, idToken: identityToken)
   )
   ```
   （Google 同理，帶 `accessToken` 參數）——**不要**用 `signInWithIdToken` 做升級。
5. 處理 `linkIdentityWithIdToken` 可能丟出的 422 `identity_already_exists`（該身分已連別的帳號）與 400 `email_exists`（email 衝突），要有對應 UX（例如提示「這個 Apple/Google 帳號已經在別的裝置上用過」）。
6. 確認 App 完全不提供 email/password 輸入介面（產品裁決本來就如此），作為 Dashboard 關閉 Email provider 之外的第二層防護。

### 待落地驗證（本研究無法只靠讀文件/原始碼 100% 確認，需要用測試專案實測）

1. **`linkIdentityWithIdToken`（原生 ID-token 升級）是否真的不需要開「Enable Manual Linking」**——這是從路由 middleware 掛載範圍推論出來的（見 Q7-b），與官方文件把它跟 web 版歸在同一個 beta 標題下的敘述有落差。建議：先在測試專案兩種狀態都各測一次（開/關這個開關），確認實際行為是否與原始碼推論一致，再決定正式環境要不要打開。
2. Apple 官方 Swift 範例程式碼沒有展示 nonce 參數（只有 Flutter 範例完整展示），建議實測「不帶 nonce」與「帶 nonce」兩種情況下 `linkIdentityWithIdToken` 是否都能成功，確認 Apple 原生流程下 nonce 是否真的是必要參數（GoTrue 源碼顯示只要 client 沒傳 nonce 但 token 裡也沒有 nonce claim 就會跳過檢查；但 Apple 端 AuthenticationServices 是否一定會把 nonce 塞進 token，需要實測確認，不能只靠讀碼保證）。
