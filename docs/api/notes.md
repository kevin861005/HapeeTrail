# Notes API（第一階段）— 契約語意文件

endpoint 的權威規格（path、header、request/response schema、錯誤格式）在
[`openapi.yaml`](openapi.yaml)——可直接 import 進 Postman。或直接匯入
[`postman/`](postman/) 下的 Collection＋Environment 兩檔：依資料夾順序執行即為
完整流程（token 由 script 自動帶入），apikey 換成自己機器 `supabase status` 的值。本檔講**規則與語意**，
一律以 wire 層語言（HTTP／JSON／錯誤 token）陳述，附 curl 範例；
**client 端如何實作（語言、SDK、資料型別）由 iOS 自主決定，本文件不涉入**。
**契約產出共三份**：本檔（語意）、[`openapi.yaml`](openapi.yaml)（wire format 的權威）、
[`postman/`](postman/)（可執行範例）。任何介面變更三份必須同步更新。
`color`／`style` 的對照表另見 [`style-codes.md`](style-codes.md)——它**由 iOS 維護**，
後端不實作、不驗證、不做同步檢查，因此不在上述同步義務內。

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
{ "code": "P0001", "message": "too_far", "details": "{\"distanceM\": 87}", "hint": null }
```

- `details` 是**選配的附帶資料**，讓提示文案可以顯示伺服器當下算出的真實數字（§8 列出
  目前帶資料的四個 token，其餘為 null）。
- ⚠️ **它是一個內容為 JSON 的字串，不是巢狀物件**——要用它必須做**第二次 JSON 解析**，
  且**解析失敗一律視為「沒有附帶資料」而非錯誤**。`message` 仍是唯一的判斷依據；
  `details` 可能為 null，client 不得依賴其存在。

## 3. 資料形狀

**Note**（drop_note / pickup_note / my_notes / my_collection 共用同一 shape，恰好 9 鍵）：

```json
{ "id": "5f8f1c1e-…", "content": "神社後面的拉麵店超好吃", "color": 1, "style": 1,
  "audience": "anyone", "coordinate": { "latitude": 35.6595, "longitude": 139.7005 },
  "createdAt": "2026-07-12T03:21:45.123456Z",
  "expiresAt": "2026-10-10T03:21:45.123456Z", "pickedUpAt": null }
```

- **不含任何 uuid 身分欄位**（`author_id`/`picked_up_by` 不上 wire）——作者一律顯示
  「匿名旅人」。原因：帳號綁定（Phase 2）後 uuid 會變成可連結真人身分的穩定識別字，
  且已發出的資料收不回來。
- `coordinate` 是**投放位置**（第一階段不記錄撿起位置）。
- 自己投放的便條 `pickedUpAt != null` ⇒ 已被人撿走——這是唯一的「被撿走」訊號。
- **`expiresAt` — 這張便條何時退出地圖**（伺服器推導，不是儲存的欄位）。
  公開便條為 `createdAt` ＋ 90 天；**旅遊紀錄為 null**（不會過期）。
  所以「還在地圖上嗎」的判斷是 `pickedUpAt == null && now < expiresAt`——
  **兩個欄位都要看**，不能只看 `pickedUpAt`。已撿走的便條仍保有 `expiresAt`，
  那是關於這張便條的事實，不隨狀態改變。
  期限是伺服器常數，**client 不得硬編 90 天**——調整期限不需要 app 改版
  （與 50m/100m 半徑同一原則）。

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
  不需要後端 migration 或發版。對照表的文件版在 [`style-codes.md`](style-codes.md)
  （由 iOS 維護；**開頭的「代號是永久 ID」硬性約定務必先讀**——違反會讓所有既有便條
  靜默換色，且兩側都不會有任何錯誤訊號）。
- 因此後端**不驗證代號是否存在於對照表**：對照表裡沒有的代號照樣被接受、原樣儲存、
  原樣回傳，且不會有任何錯誤訊號。**client 遇到未知代號一律渲染預設樣式**，
  不得視為錯誤或顯示破圖（§9）。
- 後端只做**範圍**粗檢：1–32767 以外的整數 → `invalid_style_code`（§8）。
  非整數（`1.5`）或超出 32 位元整數的值屬**型別**錯誤，與其他參數同一模式
  （如 `p_lat` 給字串），落在 §2 的第二層閘門，不是 `P0001`。

**`audience` — 誰撿得到**（`anyone`｜`self`）：

| | `anyone`（預設） | `self`（旅遊紀錄） |
|---|---|---|
| 別人的 nearby_notes | 出現 | **不出現** |
| 自己的 nearby_notes | 不出現（自己的 pin 一律由 my_notes 疊圖） | 不出現 |
| 別人 pickup_note | 走進 50m 可撿走 | **`note_not_found`** |
| 自己的 my_notes | 出現 | 出現 |
| my_collection | 撿到才出現 | 永遠不會出現（撿不走） |
| 未撿便條上限 50 張（`active_note_limit`） | 計入且受限 | **不計入、也不受限** |
| 旅遊紀錄上限 5000 張（`private_note_limit`） | 不計入 | 計入且受限 |

- 私人便條對外人的回應與**不存在的便條完全相同**（`note_not_found`）——刻意不另設
  「這是私人便條」的錯誤，那等於向外人確認該座標存在一張他看不到的便條。
- 這個欄位與 `color`／`style` 相反：**後端理解它並據以過濾**，因此不認得的值會被
  拒絕（`invalid_audience`）而不是走預設——靜默走預設會把使用者以為私密的便條變成公開的。

## 4. drop_note — 留便條

```bash
curl -X POST "$BASE/rest/v1/rpc/drop_note" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_content":"神社後面的拉麵店超好吃","p_lat":35.6595,"p_lng":139.7005,"p_color":1,"p_style":1,"p_audience":"anyone"}'
```

- **字數規則**：伺服器以 **Unicode code point** 計 1–500（不是 grapheme——一個 emoji
  家族在使用者眼中是 1 個字，在伺服器可能是 7+ 個 code point）。client 預檢必須用
  code point 計數，否則會放行伺服器拒絕的內容。
- **先 trim 再計數**，順序固定。只剝字串**頭尾**——多行內容中間那幾行的縮排是內容的
  一部分，不會被動到。trim 後為空 → `content_empty`。
- **trim 的字元集**＝Unicode 的 White_Space（半角空白、tab、換行、CR、NBSP、
  全形空白 U+3000、各種 en/em space、U+2028/2029…）**再加上 U+001C–U+001F**
  這四個 C0 資訊分隔符。前者與各平台慣用的「whitespaces and newlines」一致；
  後者是 Postgres 的字元類別多涵蓋的，實務上打不出來，但**若 client 預檢用的是標準的
  whitespace 字元集，這四個字元會出現「app 說可以送、伺服器回 `content_empty`」的分歧**。
  ⚠️ **格式字元不算空白**：零寬空白（U+200B–200D）、word joiner（U+2060）、
  BOM（U+FEFF）、U+180E 在 Unicode 裡是格式字元而非空白，因此不會被 trim、也不會被判為空
  ——只由它們組成的便條建得起來。這是已知邊界：伺服器擋的是常見的**意外**
  （例如按到全形空白鍵），不是刻意做出來的空白便條；後者屬檢舉機制的範圍。
- **不冪等，勿盲目重試**：timeout 後直接重發會產生重複便條。正確流程：先查 my_notes
  確認是否已建立，沒有才補發。
- `p_color`／`p_style` **可省略**（或給 null），此時伺服器補預設（皆為 `1`，指向對照表中的
  具體項目——不是一個「代表預設」的抽象槽）。兩者各自獨立，可以只給其中一個。
- `p_audience` **可省略**（或給 null），此時為 `anyone`。不認得的值 → `invalid_audience`。
- 回傳的 `content` 是伺服器 trim 後的正規版本，**client 應以它取代本地草稿**（否則本地顯示的字串會與伺服器存的不一致）。
- **兩個上限各管各的**：未撿**且未過期**的公開便條上限 50 張（`active_note_limit`）；
  旅遊紀錄上限 5000 張（`private_note_limit`）。公開便條的額度算「未撿的」，被撿走就釋放；
  旅遊紀錄永遠不會被撿走，所以它的額度是絕對總量。兩者互不影響——公開便條滿了仍可繼續
  記錄旅程，旅遊紀錄滿了也不影響留公開便條。5000 張約等於每天寫一張寫 13 年。

## 5. nearby_notes — 100m 提示

```bash
curl -X POST "$BASE/rest/v1/rpc/nearby_notes" -H "apikey: $KEY" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_lat":35.65977,"p_lng":139.7005}'
```

- 回傳 `{ "items": [ … ] }`，≤20 筆、最近優先；無結果為空陣列（不會是 null）；截斷不另行標示。
- **不含已過期的便條**（`expiresAt` 已過）——它們退出地圖，但仍留在作者的 my_notes 裡。
  結果包成物件而非裸陣列，日後在 envelope 上加欄位才不是破壞性變更。
- **不含呼叫者自己的便條**——地圖上自己的 pin 由 my_notes（過濾 `pickedUpAt == null`）疊圖。
- **不含任何人的旅遊紀錄**（`audience: self`），包含作者自己的。
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
- 別人的旅遊紀錄撿不走，回 `note_not_found`——與不存在的便條同一個答案（見 §3）。
- **已過期的便條同樣撿不走，也回 `note_not_found`**：它已退出地圖，沒有理由讓外人
  分辨「這裡曾經有一張」與「這裡什麼都沒有」。
- `too_far` 的 `details` 附**伺服器當下算出的實際距離**（`{"distanceM": 87}`，與
  NearbyHint 的 `distanceM` 同一算法）——文案可直接用它，不必沿用上一次探索結果的估計值。
  依 §2，沒拿到 `details` 或解析失敗時退回不含數字的文案即可。

## 7. my_notes／my_collection — 列表（cursor 分頁）

兩支都回傳 envelope：

```json
{ "items": [ /* Note */ ], "nextCursor": "eyJpIjogIjAzMDkyNWI2…" }
```

排序：my_notes 依 `createdAt` 新→舊、my_collection 依 `pickedUpAt` 新→舊。
每頁預設 50、上限 100（`p_limit` 越界**不報錯，伺服器靜默夾到 1–100**）。
my_notes 含公開便條與旅遊紀錄兩者，以 `audience` 分辨；
my_collection 的 `audience` 恆為 `anyone`。翻頁規則：

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
| `invalid_audience` | drop | client bug，修 payload（只認得 `anyone`／`self`） |
| `active_note_limit` | drop | 「等便條被撿走再留」（上限 50 張未撿的公開便條） |
| `private_note_limit` | drop | 旅遊紀錄已達 5000 張的絕對上限（與公開便條的額度互不影響） |
| `note_not_found` | pickup | 移除 pin、刷新（別人的旅遊紀錄也回這個） |
| `note_taken` | pickup | 「有人搶先一步」、移除 pin |
| `own_note` | pickup | UI 正常不會觸發（nearby 不含自己的） |
| `too_far` | pickup | 「再走近一點」 |
| `pickup_rate_limited` | pickup | 通用「稍後再試」（60 次/小時） |

**附帶 `details` 的 token**（其餘一律為 null；`details` 是內容為 JSON 的**字串**，
需二次解析、解析失敗視為沒有附帶資料——見 §2）：

| `message` | 解析後 | 拿來做什麼 |
|---|---|---|
| `too_far` | `{"distanceM": 87}` | 「還差 87 公尺」——伺服器當下算的，不是上次探索的估計值 |
| `content_too_long` | `{"maxChars": 500}` | 告訴使用者要刪掉多少字 |
| `active_note_limit` | `{"maxActiveNotes": 50}` | 說明為什麼不能再留 |
| `private_note_limit` | `{"maxPrivateNotes": 5000}` | 同上，旅遊紀錄那一側 |
| `pickup_rate_limited` | `{"retryAfterS": 1800}` | 大概要等多久，不必盲目重試 |

新增鍵、或讓更多 token 附帶 `details`，皆為非破壞性變更。

**變更政策**：token 字串**永久凍結**——一旦發布就不再改名、不再刪除。
新增 token 為**非破壞性**變更（依 §9 未知值政策，client 走 default 分支即可）；
改名或刪除為**破壞性**變更，須同步更新開頭列出的三份產出並取得 iOS 簽核。
讓更多 token 附帶 `details`、或在既有 `details` 裡加鍵，同為非破壞性變更。
本政策一體適用於契約的全部介面，不只錯誤 token。

## 9. 未知值政策（此後絕大多數新增都是非破壞性變更的前提）

三條規則，兩側都必須遵守：

1. **未知欄位一律忽略。** 後端在回應中新增欄位是非破壞性變更；client 的解碼不得因為
   多出不認得的鍵而失敗。
2. **未知 enum 值一律走 default 分支**，不得視為錯誤。適用於 `audience`（日後可能新增值）
   與錯誤 token（§8）——遇到不認得的 token，走通用錯誤路徑即可，不必等 app 更新。
3. **未知代號一律渲染預設樣式。** `color`／`style` 的對照表在裝置端
   （[`style-codes.md`](style-codes.md)），後端只存代號、不驗證它存不存在，
   所以對照表以外的代號會原樣抵達且**沒有任何錯誤訊號**。渲染成預設外觀，不得破圖。

**唯一的例外是請求端的 `p_audience`**：後端不認得的值一律拒絕（`invalid_audience`），
不走預設。理由是它與其他欄位性質不同——後端必須理解它才能過濾，
靜默走預設等於把使用者以為私密的便條變成公開的，而使用者不會收到任何訊號。
其餘請求參數若日後新增 enum，一律比照此標準逐案決定：**純呈現走預設，影響可見性的拒絕。**

## 10. 契約外路徑（讀了也別依賴）

**資料表對 client 完全不可達。** `GET /rest/v1/notes` 與任何 `select=` 變體一律 403，
寫入面（POST／PATCH／DELETE）同樣 403，`anon` 得 401。五支契約 RPC 全是 SECURITY DEFINER，
沒有一支需要 client 摸得到資料表，因此 `author_id`／`picked_up_by`／`location` 這些不上 wire
的欄位**沒有任何 client 路徑讀得到**。內部 helper（時間戳格式化、游標編解碼、wire 形狀建構、
距離計算、便條存活期）同樣不授權給任何 client 角色：它們不出現在 PostgREST 的路徑清單裡，直接以正確簽名
呼叫則得 403（`42501 permission denied for function …`）——存在看得出來，但呼叫不動。

技術上摸得到、但仍**不屬於契約**的只剩兩件，列出來是為了誠實，不是邀請使用：

- **`GET /rest/v1/` 根路徑**：PostgREST 自動產生的清單。以 `authenticated` 呼叫會列出
  上述五支 RPC 的路徑與參數（也就是本契約本身，沒有多的）；`anon` 呼叫得到的是空清單。
- **三支 RPC 另有 GET 形式**：`nearby_notes`／`my_notes`／`my_collection` 是 STABLE，
  所以 `GET /rest/v1/rpc/{fn}?p_lat=…` 也會成功（`drop_note`／`pickup_note` 是 VOLATILE，
  GET 得 405）。**契約只承認 POST**——GET 形式會把座標與游標寫進 URL，因而進到存取日誌
  與各層 proxy 的快取，請不要用。

## 11. Changelog

- 2026-07-29 **v3.3**：Note 新增 `expiresAt`（第 9 個鍵，**新增欄位為非破壞性變更**）。
  未撿的公開便條 90 天後退出探索與撿取（撿取回 `note_not_found`）、並釋放未撿便條的額度；
  **但仍留在作者的 my_notes 裡**，不會憑空消失。旅遊紀錄與已撿走的便條不受影響
  （旅遊紀錄的 `expiresAt` 為 null）。「還在地圖上嗎」自此要看 `pickedUpAt` 與 `expiresAt`
  兩個欄位。期限是伺服器常數，請讀 `expiresAt` 而不要硬編 90 天。
- 2026-07-29 **v3.2**：`content` 的 trim 改認**完整的 Unicode 空白**（原本只剝半角空白
  U+0020，連 tab 與換行都不剝）。於是只由全形空白、tab、換行、NBSP 組成的內容會被判為
  `content_empty` 而不再建立便條；尾端的這類空白也會被剝掉，因此**回傳的 `content` 可能
  與送出的不同**——請以回傳值為準。不新增 token、不改形狀。已知邊界：零寬空白與 BOM
  不是 Unicode 空白，不受影響（見 §4）。
- 2026-07-28 **v3.1**：旅遊紀錄新增絕對上限 5000 張與 `private_note_limit` token
  （附 `{"maxPrivateNotes": 5000}`）。在此之前旅遊紀錄完全沒有數量閘門——它不佔未撿額度、
  也永遠不會被撿走，於是單一帳號可以無限累積。**新增 token 為非破壞性變更**（依 §9，
  未知 token 走通用錯誤路徑即可），其餘契約未動。
- 2026-07-28（**非契約變更**，故不跳版號）：**資料表的直讀路徑整個關閉**
  （`GET /rest/v1/notes` 由「RLS 限自己的列」變成一律 403），內部 helper 也全部收回執行權。
  在此之前，便條作者直讀自己那一列即可取得 `picked_up_by` ＝ 撿走它的人的 `auth.users.id`
  ——契約層早已把 uuid 從 wire 上拿掉（v2.1），這條路徑補上同一個決定。
  **五支 RPC 的參數、回傳形狀、錯誤 token 全部不變，iOS 不需要改任何東西**；
  受影響的只有 §10 的揭露清單（大幅縮水）。決策理由見 ADR-0007。
- 2026-07-28 **v3.0**（iOS 動工前一次到位）：新增 §9「未知值政策」（未知欄位忽略、
  未知 enum 值與未知代號走預設；請求端 `p_audience` 是唯一例外，不認得即拒絕）——
  有了它，此後絕大多數新增都是非破壞性變更。新增
  [`style-codes.md`](style-codes.md)（`color`／`style` 對照表，由 iOS 維護，
  後端不驗證、不同步檢查，不在三份契約產出的同步義務內）。「契約外路徑」段（改編為 §10）補上四支
  `authenticated` 摸得到但不屬於契約的函式。以下為本版的 wire 變更：四個業務錯誤開始附帶 `details`
  （`too_far` 附真實距離、`content_too_long` 附字數上限、`active_note_limit` 附便條數上限、
  `pickup_rate_limited` 附建議重試秒數；其餘為 null）——`details` 是**內容為 JSON 的字串**，
  需二次解析。隨之消失的規則：「`too_far` 不附距離、不得解析錯誤字串取數字」。
  Note 新增 `audience`
  （`anyone`／`self`），drop_note 新增可省略參數 `p_audience` 與 `invalid_audience` token；
  旅遊紀錄不進任何人的探索結果、別人撿取回 `note_not_found`、不佔用未撿便條上限。
  Note 與 NearbyHint 新增 `color`／`style`
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
