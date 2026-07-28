# 06 — 業務錯誤附帶 details

**What to build:** 四種業務錯誤開始附上伺服器當下算出的真實數字，讓 app 的提示文案可以
顯示「還差 87 公尺」而不是沿用上一次探索結果的估計值，也不必為了取得數字去解析錯誤字串。

- 距離不足 → 實際距離
- 內容過長 → 字數上限
- 便條數達上限 → 上限數字
- 撿取過頻 → 建議重試秒數

**Blocked by:** 05 — 私人便條（audience）
（便條數上限的語意在 05 才定案為「只計算公開便條」，其附帶數字須反映定案後的規則。）

**Status:** done — `supabase/migrations/20260728050000_error_details.sql`（2026-07-28）

- [x] 上述四種錯誤附帶 `details`；其餘錯誤的 `details` 為 null
      ✅ `raise exception … using detail = jsonb_build_object(…)::text` 四處：
      `too_far` → `{"distanceM": <實際距離>}`、`content_too_long` → `{"maxChars": 500}`、
      `active_note_limit` → `{"maxActiveNotes": 50}`、`pickup_rate_limited` → `{"retryAfterS": <秒>}`。
      「其餘為 null」由測試 helper 的預設值全面守住（見下）
- [x] **`details` 是內容為 JSON 的字串，不是巢狀物件**
      ✅ HTTP 實測（本機 PostgREST）：
      `{"code":"P0001","details":"{\"distanceM\": 130}","hint":null,"message":"too_far"}`、
      `{"code":"P0001","details":"{\"maxChars\": 500}","hint":null,"message":"content_too_long"}`、
      對照組 `{"code":"P0001","details":null,"hint":null,"message":"content_empty"}`
- [x] 業務錯誤仍為 HTTP 400 加 `P0001`；`message` 仍是唯一的判斷依據
      ✅ transport 未動（同上實測 body）；`details` 只是多出來的選配欄位
- [x] 錯誤 token 字串完全未變動（凍結契約）
      ✅ migration 內 `raise exception` 的 token 字面量與 05 逐字相同，零新增、零改名
- [x] SQL seam 斷言涵蓋四種錯誤各自的 `details` 內容與數值正確性
      ✅ `expect_error(sql, want, want_detail jsonb default null)`——**省略第三參數 ＝ 斷言該錯誤
      不附帶任何資料**，於是 20+ 個既有呼叫點順便守住「只有這四種帶 details」，不必逐一補斷言。
      三個常數以逐字 jsonb 比對（`{"maxChars": 500}`／`{"maxActiveNotes": 50}`／`{"retryAfterS": 3600}`
      ——單一交易內 now() 恆定，60 次撿取同刻，時間窗一秒未滑動故恰為窗長）；
      `too_far` 的距離是**量測值**，故以 60–80 範圍斷言（寫死數字等於把 PostGIS 的算法抄進測試），
      這一處刻意手寫 begin/exception 而不硬塞進 helper
- [x] newman 加入斷言：`details` 確實出現在回應 body，且確實是字串而非物件
      ✅ 新增請求「pickup_note 錯誤示範：距離不足（details 附真實距離）」，
      排在成功撿起之前（否則會變成 note_taken）：斷言 400／`P0001`／`too_far`／
      `e.details` 為 `string`／`JSON.parse(e.details).distanceM` 落在 120–140（該點約 130m）。
      newman **12 requests / 12 assertions / 0 failed**
- [x] OpenAPI 規格與語意文件同步更新，明載需二次解析且解析失敗視為「沒有附帶資料」
      ✅ `openapi.yaml`：`ApiError.details` 補 description（四個 token 與其鍵、二次解析、
      解析失敗視為沒有附帶資料、可能為 null 不得依賴其存在、新增鍵為非破壞性）＋ example；
      `BusinessError` 的 example 由 `details: null` 改為 `'{"distanceM": 87}'`；redocly lint 通過。
      `notes.md`：§2 加兩條規則、§6 改寫 `too_far`、§8 新增「附帶 details 的 token」表、§10 changelog
- [x] 語意文件刪除已不適用的「距離不足不附數字、不得解析錯誤字串取數字」的替代做法說明
      ✅ `notes.md` §6 該條已改寫；全檔僅存於 §10 changelog 的「隨之消失的規則」敘述

**順帶改掉的一處（同一條要求推導而來，非額外功能）：** `pickup_note` 的頻率閘門由
`count(*) >= 60` 改為「取窗內第 60 新的那次撿取」——**它存在 ⇔ 窗內已有 ≥60 次**（等價），
而**它滑出時間窗的時刻正是計數降回 59、可以再撿的時刻**，於是閘門與建議秒數同一次索引掃描
就拿到，不必為了算秒數再查一次。用 `min()` 會算錯（窗內最舊的那次滑出時，若窗內有 71 次，
計數只降到 70，仍然被擋）。同一份資料（2 萬列收藏）上的 EXPLAIN 對比：

| | BEFORE `count(*)` | AFTER `offset 59 limit 1` |
|---|---|---|
| 掃描節點 | `Bitmap Heap Scan` ＋ `Bitmap Index Scan on notes_picker_ix` | `Index Only Scan using notes_picker_ix` |
| 實際取出列數 | 3599 | 60 |
| Buffers | shared hit=203 | shared hit=5 |
| Execution Time | 1.623 ms | 0.116 ms |

**code-review 後的修正：** ①測試 helper 的 `nullif(got,'')::jsonb` 改為「只在預期有 JSON 時才轉型」
——否則日後某個非預期的 DETAIL（如 check constraint 的 `Failing row contains …`）會在 exception
handler 內拋轉型錯，蓋掉真正該看到的 FAIL 訊息。②Postman 的距離斷言由 `above(50)` 收緊為
`within(120, 140)`：測試名寫「約 130m」卻放行 51，算錯也會綠。③migration 註明字數上限還有
**第三份**——table 的 `notes_content_len` CHECK（約束無法引用函式常數，只能靠註解把它拴住）。
④`openapi.yaml` 的 details 說明由 Markdown 表格改回全檔一致的散文列舉。

**回報但未做（依 CLAUDE.md「嚴禁順手做」，需另立 ticket）：**
`round(extensions.st_distance(a, b))::int` 現在出現在兩處（`nearby_notes` 的提示與
`pickup_note` 的 `too_far`）。兩處必須恆等，否則 app 會看到「探索說 60m、撿取說 70m」的
自相矛盾畫面——目前只靠註解拴住。收斂為一支 `public.distance_m(geography, geography)` 的
成本不高（兩支呼叫端都是 SECURITY DEFINER，不需授權給 `authenticated`，也就不會擴大
ticket 07 要揭露的契約外呼叫面），但不在本票範圍內。

**環境備註：** `supabase db reset` 的 8 支 migration 全部套用成功，但收尾時
`supabase_storage_trailstamp container is not ready: unhealthy`——storage 與本專案無關，
此現象在本票動工前即存在，未影響 db／PostgREST／測試。
