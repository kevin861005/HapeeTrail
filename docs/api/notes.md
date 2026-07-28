# Notes API（第一階段）— 契約語意文件

endpoint 的權威規格（path、header、request/response schema、錯誤格式）在
[`openapi.yaml`](openapi.yaml)——可直接 import 進 Postman。或直接匯入
[`postman/`](postman/) 下的 Collection＋Environment 兩檔：依資料夾順序執行即為
完整流程（token 由 script 自動帶入），apikey 換成自己機器 `supabase status` 的值。本檔講**規則與語意**，
一律以 wire 層語言（HTTP／JSON／錯誤 token）陳述，附 curl 範例；
**client 端如何實作（語言、SDK、資料型別）由 iOS 自主決定，本文件不涉入**。
介面變更必須同步更新兩檔。

共同約定：

- 所有業務 endpoint 都是 `POST /rest/v1/rpc/{fn}`，body 為 JSON，**鍵名完全等於參數名**
  （`p_content`、`p_lat`…，見 openapi.yaml）。**請求端與回應端的命名刻意不同**：
  請求鍵名是函式參數名（`p_` 底線），回應鍵名是 camelCase；座標在請求端是兩個扁平
  浮點數參數（扁平才拿得到資料庫的型別檢查），在回應端是巢狀物件。
- 每個請求帶 `apikey: <publishable key>`；除 signup 外另帶 `Authorization: Bearer <access_token>`。
- 回應的時間戳格式**固定**為 `YYYY-MM-DDTHH:MM:SS.ffffffZ`——永遠六位小數、永遠 `Z`，
  不因秒數恰為整數而變動位數。
- 第一階段沒有 Realtime，輪詢是唯一新鮮度機制；數量（收藏數等）由 client 從列表自算。
- 以下 curl 範例假設：`$BASE`（如 `http://127.0.0.1:54321`）、`$KEY`、`$TOKEN` 已設定。

---

## 1. Session

匿名登入（等同各語言 SDK 的 signInAnonymously）：

```bash
curl -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" \
  -H "Content-Type: application/json" -d '{}'
# → { "access_token": "...", "refresh_token": "...", "user": { "id": "...", "is_anonymous": true } }
```

- **只在沒有既存 session 時呼叫**。⚠️ 對既有身分重複匿名登入會鑄出**新的 user id**，
  舊 id 的所有便條與收藏永久無法存取。重裝 App 且未綁定帳號＝足跡遺失（已知限制）。
- 日後綁定正式帳號（updateUser／linkIdentity 對應的 GoTrue 端點）**user id 不變**，資料自動延續。

## 2. 錯誤兩層閘門

1. **業務錯誤**：HTTP 400 且 body 的 `code == "P0001"` → `message` 是凍結 token（§8），
   依 token 決定行為。
2. **其他一切**（HTTP 401、`PGRST3xx`、`42501`、網路錯誤、非 P0001）→ 不屬於本契約，
   一律走「session 刷新／通用重試」，**不得**對 message 字串做比對。

```json
{ "code": "P0001", "message": "too_far", "details": null, "hint": null }
```

## 3. 資料形狀

**Note**（drop_note / pickup_note / my_notes / my_collection 共用同一 shape，恰好 7 鍵）：

```json
{ "id": "5f8f1c1e-…", "content": "神社後面的拉麵店超好吃", "color": 1, "style": 1,
  "coordinate": { "latitude": 35.6595, "longitude": 139.7005 },
  "createdAt": "2026-07-12T03:21:45.123456Z", "pickedUpAt": null }
```

- **不含任何 uuid 身分欄位**（`author_id`/`picked_up_by` 不上 wire）——作者一律顯示
  「匿名旅人」。原因：帳號綁定（Phase 2）後 uuid 會變成可連結真人身分的穩定識別字，
  且已發出的資料收不回來。
- `coordinate` 是**投放位置**（第一階段不記錄撿起位置）。
- 自己投放的便條 `pickedUpAt != null` ⇒ 已被人撿走——這是唯一的「被撿走」訊號。

**NearbyHint**（僅 nearby_notes；刻意不含 content 與作者，但帶代號供地圖 pin 渲染）：

```json
{ "id": "5f8f1c1e-…", "color": 1, "style": 1,
  "coordinate": { "latitude": 35.65977, "longitude": 139.7005 },
  "distanceM": 30, "pickable": true, "createdAt": "…" }
```

回應鍵名一律 camelCase，且**縮寫視為普通單字**（`distanceM`；日後的 `photoUrl`
不會是 `photoURL`）——此後新增欄位一律比照。

**`color`／`style` 代號**（兩者各自獨立、從 1 起算的小整數）：

- 色票與卡片樣式的**對照表在裝置端**，後端只儲存代號、不理解其語意——新增顏色或樣式
  不需要後端 migration 或發版。
- 因此後端**不驗證代號是否存在於對照表**：對照表裡沒有的代號照樣被接受、原樣儲存、
  原樣回傳，且不會有任何錯誤訊號。**client 遇到未知代號一律渲染預設樣式**，
  不得視為錯誤或顯示破圖。
- 後端只做**範圍**粗檢：1–32767 以外的整數 → `invalid_style_code`（§8）。
  非整數（`1.5`）或超出 32 位元整數的值屬**型別**錯誤，與其他參數同一模式
  （如 `p_lat` 給字串），落在 §2 的第二層閘門，不是 `P0001`。

## 4. drop_note — 留便條

```bash
curl -X POST "$BASE/rest/v1/rpc/drop_note" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_content":"神社後面的拉麵店超好吃","p_lat":35.6595,"p_lng":139.7005,"p_color":1,"p_style":1}'
```

- **字數規則**：伺服器以 **Unicode code point** 計 1–500（不是 grapheme——一個 emoji
  家族在使用者眼中是 1 個字，在伺服器可能是 7+ 個 code point）。client 預檢必須用
  code point 計數，否則會放行伺服器拒絕的內容。
- **不冪等，勿盲目重試**：timeout 後直接重發會產生重複便條。正確流程：先查 my_notes
  確認是否已建立，沒有才補發。
- `p_color`／`p_style` **可省略**（或給 null），此時伺服器補預設（皆為 `1`，指向對照表中的
  具體項目——不是一個「代表預設」的抽象槽）。兩者各自獨立，可以只給其中一個。
- 回傳的 `content` 是伺服器 trim 後的正規版本，**client 應以它取代本地草稿**。
- 每人未撿便條上限 50 張（`active_note_limit`）。

## 5. nearby_notes — 100m 提示

```bash
curl -X POST "$BASE/rest/v1/rpc/nearby_notes" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_lat":35.65977,"p_lng":139.7005}'
```

- 回傳 `{ "items": [ … ] }`，≤20 筆、最近優先；無結果為空陣列（不會是 null）；截斷不另行標示。
  結果包成物件而非裸陣列，日後在 envelope 上加欄位才不是破壞性變更。
- **不含呼叫者自己的便條**——地圖上自己的 pin 由 my_notes（過濾 `pickedUpAt == null`）疊圖。
- `pickable` 與 `distanceM` 是呼叫當下的快照，可能過期；撿起時伺服器重新驗證。
  `distanceM` 由伺服器計算，**client 不得自行重算**，也**不得硬編 50m/100m 門檻**
  ——半徑是伺服器常數，調整不需 client 改版。
- **輪詢的設計假設**：本 endpoint 為前景輪詢設計，建議在位移約 25–50m 時重查＋提供
  手動刷新。粗粒度定位服務（基地台等級、數百公尺）不足以支撐 100m 半徑的體驗。
  第一階段無背景探索。撿起成功或收到 `note_taken`/`note_not_found` 後應本地移除或重查。

## 6. pickup_note — 50m 獨佔撿起

```bash
curl -X POST "$BASE/rest/v1/rpc/pickup_note" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_note_id":"5f8f1c1e-…","p_lat":35.65977,"p_lng":139.7005}'
```

- **獨佔**：同一張便條全世界只有一人撿得到，先到先贏；輸家收到 `note_taken`。
- **冪等重試**：撿起已成功但回應在網路上遺失時，同一人重試會**再次回傳成功**
  （不會誤報 `note_taken`）——timeout 後可安心重試同一筆。
- `too_far` 不附距離數字；提示文案可用上次 NearbyHint 的 `distanceM`，
  **不得解析錯誤字串取數字**。

## 7. my_notes／my_collection — 列表（cursor 分頁）

兩支都回傳 envelope：

```json
{ "items": [ /* Note */ ], "nextCursor": "eyJpIjogIjAzMDkyNWI2…" }
```

排序：my_notes 依 `createdAt` 新→舊、my_collection 依 `pickedUpAt` 新→舊。
每頁預設 50、上限 100。翻頁規則：

- 第一頁：不帶 `p_cursor`（或給 null）。
- 下一頁：把上一頁的 `nextCursor` **原樣**放進 `p_cursor`。
- **`nextCursor` 為 null ＝ 沒有更多**（唯一的終止訊號）。伺服器保證非 null 時確實還有
  資料，不必為了確認結束多打一次空頁。
- 游標是**不透明字串**：不要解碼、解析、竄改或自行組裝，只需原樣回傳。內部結構不屬於
  契約，後端可能隨時改變（例如日後改以距離或熱門度排序）；屆時舊游標會被拒為
  `invalid_cursor` 而非靜默退化，client 重新從第一頁開始即可。
- **拿錯游標不會靜默回錯的資料**：游標編碼了自己屬於哪一支列表的排序，把 my_notes 的
  游標餵給 my_collection（或反之）一律 `invalid_cursor`。這是伺服器把關的，
  不是 client 需要小心的規則。
- 無法解碼、被竄改、或排序語意已變更的游標 → `invalid_cursor`（HTTP 400）。

```bash
# 第一頁
curl -X POST "$BASE/rest/v1/rpc/my_notes" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"p_limit":50}'
# 下一頁（游標＝上一頁的 nextCursor，原樣回傳）
curl -X POST "$BASE/rest/v1/rpc/my_notes" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_limit":50,"p_cursor":"eyJpIjogIjAzMDkyNWI2…"}'
```

## 8. 錯誤碼表（凍結契約）

完整 enum 見 openapi.yaml `ApiError`。建議的 client 行為：

| `message` | 來源 | client 行為 |
|---|---|---|
| `not_authenticated` | 全部 | 防禦碼，同 session 刷新路徑 |
| `invalid_coordinates` | drop/nearby/pickup | client bug，修 payload |
| `invalid_cursor` | my_notes/my_collection | 游標無效或已過期格式，改從第一頁重新載入 |
| `content_empty` / `content_too_long` | drop | 表單行內提示（1–500 code point） |
| `invalid_style_code` | drop | client bug，修 payload（代號須落在 1–32767） |
| `active_note_limit` | drop | 「等便條被撿走再留」（上限 50 張未撿） |
| `note_not_found` | pickup | 移除 pin、刷新 |
| `note_taken` | pickup | 「有人搶先一步」、移除 pin |
| `own_note` | pickup | UI 正常不會觸發（nearby 不含自己的） |
| `too_far` | pickup | 「再走近一點」 |
| `pickup_rate_limited` | pickup | 通用「稍後再試」（60 次/小時） |

**變更政策**：token 字串永久凍結；新增為非破壞性（未知碼走 default），
改名／刪除為破壞性變更，須同步更新 openapi.yaml＋本檔並取得 iOS 簽核。

## 9. 契約外路徑（讀了也別依賴）

技術上 `GET /rest/v1/notes` 直讀存在（v1 曾使用），RLS 限制只能讀到自己寫的或
自己撿的列。**它不是契約的一部分**：回傳形狀與 RPC 不同（會多出 `location` WKB
與 `author_id`/`picked_up_by` 等契約不承載的欄位）、上限規則不同，且後端可能
隨時收緊或收回而不另行通知。一律使用上述 RPC。

## 10. Changelog

- 2026-07-28 **v3.0**（進行中，iOS 動工前一次到位）：Note 與 NearbyHint 新增 `color`／`style`
  兩個獨立代號（對照表在裝置端，後端只存代號；未知代號渲染預設樣式），drop_note 新增
  兩個可省略參數 `p_color`／`p_style` 與 `invalid_style_code` token。
  wire format 改版（**breaking**）——
  鍵名改 camelCase、座標改巢狀 `coordinate` 物件、時間戳格式固定為六位小數 ＋ `Z`、
  nearby_notes 改回傳 `{ "items": [...] }` envelope。
  兩支列表改回傳 `{ items, nextCursor }` envelope，分頁改**單一不透明游標** `p_cursor`
  （取代 `p_before_*` ＋ `p_before_id` 兩欄位）；隨之消失的三條規則：游標 timestamp 必須
  byte-for-byte 原樣回傳、游標兩欄位必須成對、空陣列＝沒有更多。
  請求端其餘部分（`p_` 參數名、扁平座標）不變。
- 2026-07-12 **v2.2**：文件語言中立化——移除全部 Swift 程式碼，規則改以 wire 層語言
  陳述＋curl 範例；契約語意不變（wire format 無任何變動）。
- 2026-07-12 **v2.1**：Note shape 移除 `author_id`/`picked_up_by`（**breaking**）；
  新增 `invalid_cursor` token；新增「契約外路徑」聲明。
- 2026-07-12 **v2**：新增 `openapi.yaml` 為 wire format 權威；列表從直接查表（offset）
  改為 RPC ＋ cursor 分頁（**breaking**）。
- 2026-07-12 v1：初版。
