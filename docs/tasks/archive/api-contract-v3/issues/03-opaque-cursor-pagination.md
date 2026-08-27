# 03 — 列表的新格式與不透明游標

**What to build:** 旅人翻閱自己的便條與收藏時不會掉列，翻到最後一頁立刻知道沒有更多，
不必再多打一次拿到空陣列才確認。兩支列表 RPC 改為回傳 `{ items, nextCursor }`，並以
單一不透明游標字串取代原本「排序鍵 ＋ id」兩個必須成對的參數。

client 只需把 `nextCursor` 原樣回傳即可翻頁，不需要理解也不可能不小心破壞它的內部結構
——原本要求 client 保證時間戳 byte-for-byte 不變的義務就此消失。

**Blocked by:** 02 — 便條與探索提示的新 wire 格式

**Status:** done — `supabase/migrations/20260728020000_opaque_cursor.sql`（2026-07-28）

- [x] 兩支列表回傳含 `items` 與 `nextCursor` 兩個鍵；`nextCursor` 為 null 即表示沒有更多
      ✅ 精確鍵集斷言 `array['items','nextCursor']`；**多取一筆**（`limit v_lim + 1`）判斷是否還有下一頁，
      故非 null 保證確實還有資料。邊界測試：頁大小恰等於總筆數（`my_notes(29)` 對 29 張）→ nextCursor 為 null
- [x] 游標為不透明字串，內含版本標記，讓日後更換排序策略時能辨識並拒絕舊游標而不必改變 API 形狀
      ✅ 唯一編解碼處 `public.as_cursor` / `public.parse_cursor`；內容為
      `base64({"v":1,"k":<排序鍵名>,"t":…,"i":…})`，兩道閘門各擋一種漂移：`v` 擋編碼格式、
      `k` 擋排序語意。實測 wire 值單行（`encode` 每 76 字元的換行以 `translate` 去除）
- [x] 游標不簽章不加密——它不授予任何權限，查詢永遠限縮在呼叫者自己的資料範圍內
      ✅ 兩支 RPC 的 where 仍是 `author_id = auth.uid()` / `picked_up_by = auth.uid()`，且 RLS 同時生效；
      竄改游標最多改變自己看到的起點
- [x] 無法解碼、格式不符或版本不符的游標一律拋出既有的 `invalid_cursor`，不得靜默退化
      ✅ SQL 四例皆 `invalid_cursor`：`'not-a-cursor'`、空字串、**截斷的真游標**、
      **跨列表游標（兩個方向）**；HTTP 實測 `{"code":"P0001","message":"invalid_cursor"}` ＋ 400。
      未新增任何 token。斷言一律下在外部行為，不手工組游標、不觸碰內部編碼
      （spec Testing Decisions：「不對…游標的內部編碼細節做斷言」）
- [x] 筆數上限參數行為不變（預設 50、上限 100）
      ✅ `least(greatest(coalesce(p_limit,50),1),100)` 原樣沿用；下界 clamp 測試（`my_notes(0)` → 1 筆）保留
- [x] 排序規則不變：我的便條依建立時間新到舊、我的收藏依撿起時間新到舊
      ✅ `(created_at, id) desc` / `(picked_up_at, id) desc` 未動；測試逐頁斷言嚴格遞減
- [x] **所有時間戳同刻**的平手情境下翻頁不掉列、不重複
      ✅ B 的 29 張同刻、每頁 10 筆走完：29 筆、零重複（`seen` 陣列）、(createdAt,id) 嚴格遞減；
      D 的 60 張同刻收藏：50 ＋ 10、兩頁 id 無交集、第二頁 nextCursor 為 null
- [x] 兩支 RPC 維持 SECURITY INVOKER，權限仍由 RLS 與資料表授權把關
      ✅ `pg_proc` 查驗：`my_notes`/`my_collection` `prosecdef = f`、`provolatile = s`、`search_path=""`，
      ACL 為 `postgres,authenticated,service_role`（anon 無）；空 claims → `not_authenticated`、anon → 42501 的既有測試保留
- [x] newman 加入斷言：游標經完整 HTTP 往返後仍可用於翻頁
      ✅ 11 requests / 11 assertions / 0 failed。A 改留兩張便條，`p_limit:1` 取第一頁 → 游標存進 environment →
      第二頁原樣回傳：id 不重複、涵蓋 note_id、nextCursor 為 null；另加竄改游標 → 400 `invalid_cursor`
- [x] OpenAPI 規格與語意文件同步更新，並刪除三段已不適用的說明
      ✅ `openapi.yaml`：新增 `Cursor`／`NotePage` schema，`MyNotesPage`＋`MyCollectionPage` 合併為 `ListParams`
      （兩支參數已完全相同），兩支回應由裸陣列改 `NotePage`；redocly lint 通過。
      `notes.md` §7 重寫、§8 `invalid_cursor` 行改寫、v3.0 changelog 補記——三段警告
      （byte-for-byte、兩欄位成對、空陣列＝終止）全數刪除，`grep p_before` 於 docs/ 零殘留

**額外驗證：**

- EXPLAIN（20000 列 / 200 位作者，以 `authenticated` 身分含 RLS）：新舊寫法的掃描節點**逐字相同**
  ——`Bitmap Index Scan on notes_author_ix` ＋ RLS 帶來的 `BitmapOr … notes_picker_ix`；
  新增的只是 Limit 之上的 WindowAgg。帶游標時 keyset 條件另外下推成 Index Cond（`created_at <= …`），
  即索引掃描範圍更小
- `as_cursor` / `parse_cursor` 因兩支 INVOKER 列表需要而授權給 `authenticated`，
  故也是 PostgREST 呼叫得到的 RPC（同 T11-02 的 `as_wire_ts`）。兩支皆純字串運算、不碰任何資料，
  未擴大讀取面；契約外路徑的揭露已登記於 ticket 07

**code-review 後的修正（2026-07-28）：** 兩個獨立複核視角都抓到同一個實質缺陷——
初版游標只帶版本號、不帶排序鍵身分，於是 `my_collection` 的游標餵給 `my_notes` 會被接受，
拿 `picked_up_at` 的值去比 `created_at`，**靜默回錯頁且毫無訊號**（已直接重現）。
這正是本票「不得靜默退化」要消滅的失效模式，且當時只能靠在契約文件上新增一條
「游標不可互換使用」的 client 義務來遮蓋——反而推翻了本票「client 不可能不小心破壞它」的立論。
修正：payload 加入排序鍵名 `k`，`parse_cursor` 由呼叫端宣告自己的排序鍵，不符即 `invalid_cursor`；
文件那條 client 義務隨之刪除，改為伺服器把關的保證。兩個方向皆有回歸測試。
同批一併處理：測試不再手工組 base64（改以截斷真游標施測）、`openapi.yaml` 的
`Cursor.example` 由可解碼的真游標換成佔位字串（原本就緊接在「不要解碼」下面）、
schema 更名為 `ListParams`（請求）／`NotePage`（回應）以符實、
unsigned cursor 補上 `ponytail:` 註記與天花板。

**未處理（已回報並取得同意）：** base64 非 URL-safe 一事實測不成立——現行與加 `k` 後的
payload 各 5000 個游標皆零個 `+` 或 `/`（長度固定所致），故不改 base64url，
但已把此前提與升級路徑寫進 migration 的 `ponytail:` 註記。
`my_notes` 與 `my_collection` 的結構重複亦維持現狀：兩者只差欄位名，
SQL 要抽共用得走 dynamic SQL，在空 `search_path` 的慣例下代價大於收益。

**注意：** 兩支 RPC 的參數與回傳型別都改變，**必須先移除舊函式再建立**；移除後需重新
套用權限的收回與授予。函式增刪後 PostgREST 的 schema cache 不會自動更新，跨 HTTP
驗證前必須先觸發重載。
