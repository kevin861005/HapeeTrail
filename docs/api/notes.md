# Notes API（第一階段）— iOS 整合指南

本文件講**語意與 Swift 整合模式**；endpoint 的權威規格（path、header、request/response
schema、錯誤格式）在 [`openapi.yaml`](openapi.yaml)——可直接 import 進 Postman 測試。
介面變更必須同步更新兩檔（openapi.yaml 管 wire format，本檔管語意，分工不重疊）。

端點總覽（詳見 openapi.yaml）：

| operationId | 用途 | 語意重點（本檔章節） |
|---|---|---|
| `signInAnonymously` | 匿名 session | §1 |
| `dropNote` | 留便條 | §3：不冪等、字數規則 |
| `nearbyNotes` | 100m 提示 | §2 輪詢、§4：pickable 快照 |
| `pickupNote` | 50m 獨佔撿起 | §5：競態、冪等重試 |
| `myNotes` / `myCollection` | 我的便條／收藏 | §6：cursor 分頁 |

---

## 1. Session 與錯誤處理

### 匿名登入

- 冷啟動：檢查 `supabase.auth.currentSession`；**只有為 nil 時**才 `signInAnonymously()`。
  SDK 自動存 Keychain 並刷新 token。
- ⚠️ **嚴禁對既有身分重新匿名登入**：會鑄新 user id，舊 id 的所有足跡永久無法存取。
  重裝 App 且未綁定帳號 = 足跡遺失（第一階段已知限制）。
- 綁定正式帳號用 `updateUser()` / `linkIdentity()`——user id 不變，資料自動延續。

### 錯誤兩層閘門

1. `PostgrestError` 且 `code == "P0001"` → `message` 是凍結 token（見 §7），switch 它。
2. 其他一切（401、`PGRST3xx`、`42501`、網路錯誤）→ 走 session 刷新／通用重試，
   **不要**比對 message 字串。

```swift
enum NotesAPIError: String {
    case notAuthenticated = "not_authenticated"
    case invalidCoordinates = "invalid_coordinates"
    case invalidCursor = "invalid_cursor"
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

### 時間戳統一規則（重要）

伺服器的 timestamp 是 UTC ISO-8601、**小數秒位數可變**。本契約規定：
**所有 model 的時間欄位一律解碼成 `String` 原樣保存**，顯示時再轉 `Date`——
因為分頁游標（§6）必須把伺服器給的值一字不差傳回去，經過 `Date` 轉換會有精度損失、
造成翻頁掉列。轉換用這個 helper：

```swift
enum TS {
    static func parse(_ s: String) -> Date {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]   // 整秒無小數的情況
        return f.date(from: s) ?? .distantPast
    }
}
```

### 參數編碼

RPC 參數鍵名完全等於 SQL 參數名（`p_content`、`p_lat`…，見 openapi.yaml）；
用 `Encodable` struct 傳（`[String: Any]` 不是 `Encodable`）。`UUID` 編碼為字串即可。
第一階段**沒有 Realtime**，輪詢是唯一新鮮度機制；數量（收藏數等）client 端自算。

---

## 2. 輪詢指引

- 前景 standard location updates（`distanceFilter` 25–50m）觸發 `nearby_notes`＋手動刷新。
- ⚠️ 不要用 Significant-Location-Change service——基地台粒度，對 100m 半徑無用。
- 第一階段不做背景探索；撿起成功或 `note_taken`/`note_not_found` 後本地移除 pin 或重查。

---

## 3. Swift Models

```swift
struct Note: Decodable, Identifiable {
    let id: UUID
    let content: String
    let lat: Double             // 投放位置（不記錄撿起位置）
    let lng: Double
    let createdAtRaw: String    // 原樣字串：my_notes 游標用
    let pickedUpAtRaw: String?  // 原樣字串：my_collection 游標用；自己投放的便條上非 nil ⇒ 被撿走

    var createdAt: Date { TS.parse(createdAtRaw) }
    var pickedUpAt: Date? { pickedUpAtRaw.map(TS.parse) }

    enum CodingKeys: String, CodingKey {
        case id, content, lat, lng
        case createdAtRaw = "created_at"
        case pickedUpAtRaw = "picked_up_at"
    }
}
```

Note **不含任何 uuid 身分欄位**（`author_id`/`picked_up_by` 不上 wire）——作者一律
顯示「匿名旅人」。原因：帳號綁定（Phase 2）後 uuid 會變成可連結真人身分的穩定
識別字，且已發出的資料收不回來，所以 client 端從第一天就不給。

```swift

struct NearbyHint: Decodable, Identifiable {
    let id: UUID
    let lat: Double
    let lng: Double
    let distanceM: Int          // 伺服器算的整數公尺，勿自行重算
    let pickable: Bool          // 快照，撿起時伺服器重驗；勿硬編 50/100m
    let createdAtRaw: String

    enum CodingKeys: String, CodingKey {
        case id, lat, lng, pickable
        case distanceM = "distance_m"
        case createdAtRaw = "created_at"
    }
}
```

`drop_note`、`pickup_note`、`my_notes`、`my_collection` 回傳的都是同一個 `Note` shape。

---

## 4. 留便條 ＋ 查附近

```swift
struct DropNoteParams: Encodable {
    let pContent: String; let pLat: Double; let pLng: Double
    enum CodingKeys: String, CodingKey {
        case pContent = "p_content", pLat = "p_lat", pLng = "p_lng"
    }
}
let note: Note = try await supabase.rpc("drop_note",
    params: DropNoteParams(pContent: text, pLat: loc.latitude, pLng: loc.longitude))
    .execute().value

struct GeoParams: Encodable {
    let pLat: Double; let pLng: Double
    enum CodingKeys: String, CodingKey { case pLat = "p_lat", pLng = "p_lng" }
}
let hints: [NearbyHint] = try await supabase.rpc("nearby_notes",
    params: GeoParams(pLat: loc.latitude, pLng: loc.longitude))
    .execute().value
```

留便條規則：

- **字數**：伺服器算 Unicode code point，client 預檢用 `content.unicodeScalars.count`
  （不是 `content.count`，emoji 家族兩邊算法不同）。
- **勿盲目重試**：drop 不冪等——timeout 後先查 `my_notes` 確認是否已建立，沒有才補發。
- 成功後**以回傳的 content 取代本地草稿**（伺服器已 trim）。

查附近規則：**不含自己的便條**——地圖上自己的 pin 從 `my_notes` 過濾
`pickedUpAtRaw == nil` 疊圖；≤20 筆最近優先，截斷不標示。

---

## 5. 撿起（獨佔）

```swift
struct PickupParams: Encodable {
    let pNoteId: UUID; let pLat: Double; let pLng: Double
    enum CodingKeys: String, CodingKey {
        case pNoteId = "p_note_id", pLat = "p_lat", pLng = "p_lng"
    }
}
do {
    let picked: Note = try await supabase.rpc("pickup_note",
        params: PickupParams(pNoteId: hint.id, pLat: loc.latitude, pLng: loc.longitude))
        .execute().value
    // 成功：加入收藏、移除 pin、顯示內容
} catch {
    switch NotesAPIError(error) {
    case .noteTaken:         break // 「有人搶先一步」、移除 pin
    case .tooFar:            break // 「再走近一點」（距離用上次 hint 的 distanceM）
    case .noteNotFound:      break // 過期 pin：移除並重查
    case .pickupRateLimited: break // 通用「稍後再試」
    default:                 break // 其他：通用重試／session 刷新
    }
}
```

- **獨佔**：先到先贏，輸家收到 `note_taken`。
- **冪等重試**：成功但回應遺失時，重試回傳成功——timeout 後可安心重試同一筆。
- `too_far` 不附數字；顯示用上次 hint 的 `distanceM`，不解析錯誤字串。

---

## 6. 我的便條／我的收藏（cursor 分頁）

兩支都是 RPC、預設每頁 50（上限 100）。**翻頁規則**：第一頁不帶游標；
下一頁帶上一頁「最後一列」的 timestamp 原樣字串＋id；**空陣列＝沒有更多**。
游標欄位：`my_notes` 用 `created_at`、`my_collection` 用 `picked_up_at`。

```swift
struct MyNotesParams: Encodable {
    var pLimit: Int? = nil
    var pBeforeCreatedAt: String? = nil   // 上一頁最後一列的 createdAtRaw（原樣！）
    var pBeforeId: UUID? = nil
    enum CodingKeys: String, CodingKey {
        case pLimit = "p_limit"
        case pBeforeCreatedAt = "p_before_created_at"
        case pBeforeId = "p_before_id"
    }
}

// 第一頁
var mine: [Note] = try await supabase.rpc("my_notes", params: MyNotesParams())
    .execute().value
// 下一頁
if let last = mine.last {
    let next: [Note] = try await supabase.rpc("my_notes",
        params: MyNotesParams(pBeforeCreatedAt: last.createdAtRaw, pBeforeId: last.id))
        .execute().value
    mine += next
}

struct MyCollectionParams: Encodable {
    var pLimit: Int? = nil
    var pBeforePickedAt: String? = nil    // 上一頁最後一列的 pickedUpAtRaw（原樣！）
    var pBeforeId: UUID? = nil
    enum CodingKeys: String, CodingKey {
        case pLimit = "p_limit"
        case pBeforePickedAt = "p_before_picked_at"
        case pBeforeId = "p_before_id"
    }
}
let collected: [Note] = try await supabase.rpc("my_collection", params: MyCollectionParams())
    .execute().value
```

⚠️ 游標必須是伺服器給的**原樣字串**（`createdAtRaw`/`pickedUpAtRaw`）。
拿 `Date` 重新格式化會丟微秒精度，翻頁可能掉列——這就是 model 保存原樣字串的原因（§1）。

⚠️ 游標兩欄位**必須成對**（timestamp＋id 一起給或一起不給），只帶一個會收到
`invalid_cursor`——這是 client bug，修呼叫端。

### 契約外路徑（讀了也別依賴）

技術上 `GET /rest/v1/notes` 直讀存在（v1 曾使用），RLS 限制只能讀到自己寫的或
自己撿的列。**它不是契約的一部分**：回傳形狀與 RPC 不同（會多出 `location` WKB
與 `author_id`/`picked_up_by` 等契約不承載的欄位）、上限規則不同，且後端可能
隨時收緊或收回而不另行通知。一律使用上述 RPC。

---

## 7. 錯誤碼表（凍結契約）

完整 enum 與各 endpoint 適用清單見 openapi.yaml 的 `ApiError`。client 動作對照：

| `message` | 來源 | client 動作 |
|---|---|---|
| `not_authenticated` | 全部 | 防禦碼，同 session 刷新路徑 |
| `invalid_coordinates` | drop/nearby/pickup | client bug，修 payload |
| `invalid_cursor` | my_notes/my_collection | client bug——游標兩欄位必須成對 |
| `content_empty` / `content_too_long` | drop | 表單行內提示（500 code point） |
| `active_note_limit` | drop | 「等便條被撿走再留」（上限 50 張未撿） |
| `note_not_found` | pickup | 移除 pin、刷新 |
| `note_taken` | pickup | 「有人搶先一步」、移除 pin |
| `own_note` | pickup | UI 正常不會觸發 |
| `too_far` | pickup | 「再走近一點」 |
| `pickup_rate_limited` | pickup | 通用「稍後再試」（60 次/小時） |

**變更政策**：token 字串永久凍結；新增為非破壞性（未知碼走 default），改名／刪除為
破壞性變更，須同步更新 openapi.yaml＋本檔並取得 iOS 簽核。

## 8. Changelog

- 2026-07-12 **v2.1**：Note shape 移除 `author_id`/`picked_up_by`（**breaking**，防止帳號
  綁定後 uuid 回溯連結真人身分）；新增 `invalid_cursor` token（游標成對防呆）；
  新增「契約外路徑」聲明。
- 2026-07-12 **v2**：新增 `openapi.yaml` 為 wire format 權威；「我的便條／收藏」從直接查表
  （offset `.range()`）改為 `my_notes`/`my_collection` RPC ＋ cursor 分頁（**breaking**）；
  model 時間欄位改為原樣字串保存。
- 2026-07-12 v1：初版（三 RPC ＋ 直接查表×2）。
