# Notes API（第一階段）— iOS 介面契約

後端：Supabase Postgres RPC ＋ RLS，無獨立 API server。所有呼叫走 supabase-swift。
本文件是唯一契約：介面變更必須先改這裡並通知 iOS。

RPC 一覽：`drop_note`（留便條）、`nearby_notes`（100m 提示）、`pickup_note`（50m 獨佔撿起）。
「我的便條」「我的收藏」直接查 `notes` 表（RLS 只放行自己的列），見 §6。

---

## 1. Conventions（先讀這節）

### Session bootstrap（匿名登入）

- 冷啟動：檢查 `supabase.auth.currentSession`；**只有為 nil 時**才呼叫 `signInAnonymously()`。
  SDK 會把 session 存 Keychain 並自動刷新，不需要自己管理 token。
- ⚠️ **嚴禁對既有身分重新匿名登入**：再次 `signInAnonymously()` 會鑄一個全新的 user id，
  舊 id 的所有便條與收藏將永久無法存取。重裝 App 且未綁定帳號 = 足跡遺失（第一階段已知限制）。
- 本階段不開 CAPTCHA，不需整合 Turnstile。
- 日後綁定正式帳號用 `updateUser()` / `linkIdentity()`——user id 不變，資料自動延續。

### 錯誤處理（兩層閘門）

1. **業務錯誤**：`PostgrestError` 且 `code == "P0001"` → `message` 是凍結的機器碼
   （見 §8 錯誤碼表），用它 switch。
2. **其他一切**（HTTP 401、`PGRST301`、`42501`、網路錯誤、非 P0001）→ 不是契約的一部分，
   一律走「session 刷新／通用重試」UI，**不要**對 message 字串做比對。

```swift
enum NotesAPIError: String {
    case notAuthenticated = "not_authenticated"
    case invalidCoordinates = "invalid_coordinates"
    case contentEmpty = "content_empty"
    case contentTooLong = "content_too_long"
    case activeNoteLimit = "active_note_limit"
    case noteNotFound = "note_not_found"
    case noteTaken = "note_taken"
    case ownNote = "own_note"
    case tooFar = "too_far"
    case pickupRateLimited = "pickup_rate_limited"

    init?(_ error: any Error) {
        guard let pg = error as? PostgrestError, pg.code == "P0001" else { return nil }
        self.init(rawValue: pg.message)
    }
}
```

### 參數編碼

- RPC 參數鍵名必須**完全等於** SQL 參數名（`p_content`、`p_lat`、`p_lng`、`p_note_id`）。
- `[String: Any]` 不是 `Encodable`——用小型 `Encodable` struct（每個 RPC 的範例附上）。
- `UUID` 直接編碼為字串即可，PostgREST 會轉型。

### 時間戳

- 全部 UTC ISO-8601，**小數秒位數可變**（可能整秒無小數）——
  **必須用 supabase-swift 預設 decoder**，不要自設嚴格的 `ISO8601DateFormatter`。

### 其他

- 第一階段**沒有 Realtime**：輪詢是唯一的新鮮度機制（見 §2）。
- 數量（收藏數、未撿數）client 端從列表自算，沒有 count endpoint。

---

## 2. 輪詢指引（nearby 的呼叫節奏）

- 前景使用 standard location updates，`distanceFilter` 設 25–50m，位置更新時呼叫
  `nearby_notes`；另提供手動下拉刷新。
- ⚠️ 不要用 iOS 的 Significant-Location-Change service——它是基地台等級（數百公尺以上）
  的粒度，對 100m 探索半徑毫無用處。
- 第一階段不做背景探索。
- 撿起成功或收到 `note_taken`/`note_not_found` 後：本地移除該 pin 或重新輪詢。

---

## 3. 資料模型（兩個 JSON shape）

### Note（`drop_note`、`pickup_note` 回傳，以及 §6 直接查表的列——同一個 shape）

```json
{
  "id": "5f8f1c1e-9a4b-4c58-a9b1-2f6d3f0f7c21",
  "author_id": "b7e6d2aa-1234-4f0a-9c1d-000000000001",
  "content": "神社後面的拉麵店超好吃",
  "lat": 35.6595,
  "lng": 139.7005,
  "created_at": "2026-07-12T03:21:45.123456+00:00",
  "picked_up_by": null,
  "picked_up_at": null
}
```

```swift
struct Note: Decodable, Identifiable {
    let id: UUID
    let authorId: UUID
    let content: String
    let lat: Double
    let lng: Double
    let createdAt: Date
    let pickedUpBy: UUID?
    let pickedUpAt: Date?   // 自己投放的便條上非 nil ⇒ 已被人撿走

    enum CodingKeys: String, CodingKey {
        case id, content, lat, lng
        case authorId = "author_id"
        case createdAt = "created_at"
        case pickedUpBy = "picked_up_by"
        case pickedUpAt = "picked_up_at"
    }
}
```

`lat`/`lng` 一律是**投放位置**（第一階段不記錄撿起位置）。
`author_id`/`picked_up_by` 目前沒有對應的 profile 資料，UI 顯示為「匿名旅人」即可。

### NearbyHint（`nearby_notes` 回傳的陣列元素——刻意**不含** content 與作者）

```json
{
  "id": "5f8f1c1e-9a4b-4c58-a9b1-2f6d3f0f7c21",
  "lat": 35.65977,
  "lng": 139.7005,
  "distance_m": 30,
  "pickable": true,
  "created_at": "2026-07-11T22:04:10.5+00:00"
}
```

```swift
struct NearbyHint: Decodable, Identifiable {
    let id: UUID
    let lat: Double
    let lng: Double
    let distanceM: Int      // 伺服器計算的整數公尺，不要自己重算
    let pickable: Bool      // 是否在撿起半徑內（目前 50m，伺服器常數）
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case id, lat, lng, pickable
        case distanceM = "distance_m"
        case createdAt = "created_at"
    }
}
```

---

## 4. `drop_note` — 依當前位置留便條

| 參數 | 型別 | 說明 |
|---|---|---|
| `p_content` | text | 1–500 字元（伺服器會先 btrim 再驗證） |
| `p_lat` / `p_lng` | double | WGS-84 |

回傳：單一 `Note` 物件（content 為 trim 後的正規版本——**以回傳值取代本地草稿**）。

```swift
struct DropNoteParams: Encodable {
    let pContent: String
    let pLat: Double
    let pLng: Double
    enum CodingKeys: String, CodingKey {
        case pContent = "p_content", pLat = "p_lat", pLng = "p_lng"
    }
}

let note: Note = try await supabase
    .rpc("drop_note", params: DropNoteParams(pContent: text,
                                             pLat: loc.latitude,
                                             pLng: loc.longitude))
    .execute()
    .value
```

規則：

- **字數計算**：伺服器算 Unicode code point。client 端預檢用
  `content.unicodeScalars.count`（不是 `content.count`——emoji 家族在 Swift 算 1、
  在伺服器算多個 code point，用 `.count` 會放行伺服器拒絕的內容）。
- ⚠️ **勿盲目重試**：drop 不冪等——timeout 後直接重發會產生重複便條。
  timeout 時先查「我的便條」（§6）確認是否已建立，沒有才補發。
- 錯誤：`content_empty`、`content_too_long`、`invalid_coordinates`、
  `active_note_limit`（每人未撿便條上限 50 張——顯示「等便條被撿走再留新的」）。

---

## 5. `nearby_notes` — 100m 提示

| 參數 | 型別 |
|---|---|
| `p_lat` / `p_lng` | double |

回傳：`[NearbyHint]`（可能為空陣列），最近優先，**最多 20 筆**（截斷不另行標示，對 UX 無影響）。

```swift
struct GeoParams: Encodable {
    let pLat: Double
    let pLng: Double
    enum CodingKeys: String, CodingKey { case pLat = "p_lat", pLng = "p_lng" }
}

let hints: [NearbyHint] = try await supabase
    .rpc("nearby_notes", params: GeoParams(pLat: loc.latitude, pLng: loc.longitude))
    .execute()
    .value
```

規則：

- **不含自己的便條**——地圖上自己的 pin 從「我的便條」（§6）過濾 `pickedUpAt == nil` 疊圖。
- `pickable` 是輪詢當下計算的快照，可能過期；伺服器在撿起時會重新驗證，
  client 不要硬編 50m/100m 門檻。
- content 與作者刻意不回傳：內容是走到 50m 內撿起的獎勵。

---

## 6. 我的便條／我的收藏 — 直接查表（RLS 保護）

不是 RPC。直接 select `notes` 表，RLS 只放行「自己寫的或自己撿的」列。
**照抄以下範式**（明確欄位、排序、`.range()` 分頁每頁 50）：

```swift
let cols = "id, author_id, content, lat, lng, created_at, picked_up_by, picked_up_at"
let uid = try await supabase.auth.session.user.id

// 我的便條（投放的，含已被撿走的；pickedUpAt != nil ⇒ 被撿走了）
let mine: [Note] = try await supabase.from("notes")
    .select(cols)
    .eq("author_id", value: uid)
    .order("created_at", ascending: false)
    .range(from: 0, to: 49)          // 下一頁 50...99，以此類推
    .execute()
    .value

// 我的收藏（撿到的）
let collected: [Note] = try await supabase.from("notes")
    .select(cols)
    .eq("picked_up_by", value: uid)
    .order("picked_up_at", ascending: false)
    .range(from: 0, to: 49)
    .execute()
    .value
```

---

## 7. `pickup_note` — 50m 獨佔撿起

| 參數 | 型別 |
|---|---|
| `p_note_id` | uuid |
| `p_lat` / `p_lng` | double |

回傳：完整 `Note` 物件（content 在此揭露）。

```swift
struct PickupParams: Encodable {
    let pNoteId: UUID
    let pLat: Double
    let pLng: Double
    enum CodingKeys: String, CodingKey {
        case pNoteId = "p_note_id", pLat = "p_lat", pLng = "p_lng"
    }
}

do {
    let picked: Note = try await supabase
        .rpc("pickup_note", params: PickupParams(pNoteId: hint.id,
                                                 pLat: loc.latitude,
                                                 pLng: loc.longitude))
        .execute()
        .value
    // 成功：加入收藏、移除地圖 pin、顯示內容
} catch {
    switch NotesAPIError(error) {
    case .noteTaken:     break  // 被捷足先登：移除 pin、toast「有人搶先一步」
    case .tooFar:        break  // 「再走近一點」（距離顯示用上次 hint 的 distanceM）
    case .noteNotFound:  break  // 過期 pin：移除並重新輪詢
    case .pickupRateLimited: break  // 通用「稍後再試」
    default:             break  // 其他：通用重試 / session 刷新
    }
}
```

語意（重要）：

- **獨佔**：同一張便條全世界只有一人撿得到，先到先贏；輸家收到 `note_taken`。
- **冪等重試**：如果撿起成功但回應在網路上遺失，**重試會再次回傳成功**
  （不會誤報 `note_taken`）——timeout 後可安心重試同一筆。
- `too_far` 不附距離數字——顯示提示時用上次 `NearbyHint.distanceM`，不要解析錯誤字串。

---

## 8. 錯誤碼表（凍結契約）

只在 `PostgrestError.code == "P0001"` 時比對 `message`：

| `message` | 來源 | 意義 | client 動作 |
|---|---|---|---|
| `not_authenticated` | 全部 | 防禦碼，正常流程不該出現 | 同 session 刷新路徑 |
| `invalid_coordinates` | 全部 | 座標缺失或超出 WGS-84 範圍 | client bug，修 payload |
| `content_empty` | drop | trim 後為空 | 表單行內提示 |
| `content_too_long` | drop | 超過 500 code point | 表單行內提示＋字數計 |
| `active_note_limit` | drop | 未撿便條已達 50 張 | 「等便條被撿走再留」 |
| `note_not_found` | pickup | 便條不存在（作者刪號等） | 移除 pin、刷新 |
| `note_taken` | pickup | 別人先撿走了 | 移除 pin、「有人搶先一步」 |
| `own_note` | pickup | 撿自己的便條 | UI 正常不會觸發（nearby 不含自己的） |
| `too_far` | pickup | 距離 > 50m | 「再走近一點」 |
| `pickup_rate_limited` | pickup | 一小時內撿逾 60 次 | 通用「稍後再試」 |

**變更政策**：以上字串永久凍結；新增錯誤碼為非破壞性變更（未知碼走 default），
改名／刪除為破壞性變更，必須更新本文件並取得 iOS 簽核。

## 9. Changelog

- 2026-07-12：初版。三個 RPC＋直接查表×2、錯誤碼契約 v1。
