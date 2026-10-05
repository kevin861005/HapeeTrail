# T30／T31／T32 Spec — 基本資料、更新檢查、功能開關

日期：2026-10-05。來源：與 iOS 夥伴議定的三支 API。全部是**讀取端點、不新增錯誤 token**，
契約 v4.1.1 → **v4.2.0**（新端點＝非破壞性）。

## 共同決策

- `GET /v1/me` 要 Bearer。**兩支 App 設定端點不需要登入**（Kevin 2026-10-05 裁決：這是整個 App 的設定，
  一打開就要檢查要不要強制更新）：SecurityConfig 對 `/v1/app/**` permitAll **且忽略 Authorization**
  ——permitAll 擋不住 resource server 對壞 token 的 401，iOS 共用的 client 帶著過期 token 打更新檢查
  正是啟動情境。接受的風險：公開端點無限流（每請求一次 PK 查詢／≤50 key 的 any()，表只有幾列）；
  被灌爆時上平台層 per-IP 限流，不在服務裡做。日後開關若要分使用者，改成「有 token 就驗」另立票。
- 設定資料放 **`hapeetrail_private`**（ADR-0013 開的 schema）：沒有 Supabase default privileges
  （T24 的洞）、PostgREST 不暴露、服務只拿 SELECT。改開關／版本門檻＝SQL editor 一句 UPDATE，
  不必重部署（env var 方案要 `gcloud run deploy`，被權限分類器擋過）。
- 型別／格式錯誤＝400 沒有 `code`（契約 §2 既有規則），業務錯誤零新增。

## T30 `GET /v1/me` — 基本資料

回應（鍵序固定，缺值是 `null` 不是缺鍵）：

```json
{ "id": "<uuid>", "nickname": "Kevin Chen" | null, "avatarUrl": "https://…" | null,
  "loginMethod": "anonymous" | "google" | "apple", "lastLoginAt": "2026-10-01T02:03:04.123456Z" | null,
  "memberLevel": "free" }
```

來源與規則（GoTrue 原始碼 2026-10-05 查證：`internal/api/identity.go`、`internal/models/identity.go`、
`internal/api/signup.go`）：
- **登入方式**＝最早建立的 `auth.identities` 列的 `provider`；沒有 identity ＝ `anonymous`。
  這與 GoTrue 自己算 `app_metadata.provider` 的規則相同（`FindProvidersByUser` order by created_at）。
  不用 `raw_app_meta_data->>'provider'`：匿名使用者沒有這個鍵。
- **暱稱／頭像**：`raw_user_meta_data` 的 `full_name`→`name`／`avatar_url`→`picture` 優先，
  其次同名鍵取自那個 identity 的 `identity_data`。綁定（`linkIdentityToUser`）只寫 identities、
  **不回寫 user metadata**，所以訪客升級後資料在 identities。Apple 的 id_token 不帶姓名也沒頭像 → 皆 null。
- **上次登入時間**：`auth.users.last_sign_in_at`；**匿名一律 `null`**（產品語意：訪客＝沒登入過，
  使用者原話「沒登入過就是 key 值 null」）。⚠️ 假設待 T25 票 02 實測：綁定走 `/token?grant_type=id_token`
  會 `IssueRefreshToken` ⇒ 更新 `last_sign_in_at`（`internal/tokens/service.go`）。
- **會員等級**：固定 `"free"`（預留；訂閱上線時才有表）。
- 使用者列不在（session 檢查後、查詢前被註銷）→ 401 `not_authenticated`（同 ApiErrors.identityGone）。

DB：view `hapeetrail_private.auth_users`（ADR-0013 同款）只投影 `id`、`last_sign_in_at`、最早 identity 的
`provider`，以及暱稱／頭像用到的八個 `->>` 純量（user metadata 四個＋identity_data 四個）；
**email 等 PII 不經過服務角色**（複核 A1 改法）。取鍵優先順序的 coalesce 寫在 Java 那句 SQL。
`nickname`／`avatarUrl` 是使用者可控的未驗證字串（Supabase updateUser 改得到），只回本人。

## T31 `GET /v1/app/update?version=<x.y.z>` — 更新檢查

```json
{ "forceUpdate": false, "updateAvailable": true, "minimumVersion": "1.2.0", "latestVersion": "1.4.0" }
```

- `version` 必填，格式 `\d{1,9}(\.\d{1,9}){0,3}`（1–4 段純數字；iOS 的 CFBundleShortVersionString）；
  不合＝400 沒有 code。逐段按數值比，缺段補 0（`1.2` ＝ `1.2.0`；`1.9` < `1.10`）。
- `forceUpdate` ＝ version < minimum；`updateAvailable` ＝ forceUpdate ∨ version < latest（強制必然有新版，
  門檻手改錯序也不矛盾，複核 B1）。門檻原樣回傳供顯示。
- 表 `hapeetrail_private.app_versions(platform pk, minimum_version, latest_version)`，CHECK 同一條 regex；
  migration 先塞 `ios 1.0.0/1.0.0`。平台參數**暫不上 wire**（只有 iOS）；日後加 Android ＝ 加選填
  `platform`（預設 ios）＋一列，非破壞性。沒有 `storeUrl`：iOS 自己知道自己的 App Store 連結。

## T32 `POST /v1/app/flags` — 功能總開關

```json
// 請求
{ "keys": ["map.heatmap", "notes.drop"] }
// 回應
{ "flags": { "map.heatmap": true, "notes.drop": false } }
```

- `keys` 必填陣列，0–50 個，每個符合 `[A-Za-z0-9_.-]{1,64}`；不合＝400 沒有 code。重複 key 合併。
- 表裡沒有的 key ＝ `false`（不是錯誤、不是缺鍵）。空陣列 → `{"flags":{}}`。
- 表 `hapeetrail_private.feature_flags(key pk, enabled)`，CHECK 同一條 regex。
- 包在 `flags` 物件裡而不是裸 map：日後加欄位（如 `evaluatedAt`）不是破壞性變更。

## 驗收

1. `cd api && ./mvnw test` 全綠（新增 ProfileTest、AppTest；SmokeTest 補三個物件的權限斷言）。
2. 契約三檔同步 v4.2.0，`docs/api/check-contract.py` exit 0（③④ 納入 Profile／UpdateCheck 鍵名）。
3. 獨立 subagent 複核（只給本 spec）。
4. migration `db push` 到 hosted、hosted-smoke 全綠、newman 全綠 —— **交付前由 Kevin 執行**。
