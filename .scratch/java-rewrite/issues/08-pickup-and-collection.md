# 08 — 撿起 `POST /v1/notes/{id}/pickup` ＋ `GET /v1/me/collection`

**What to build:** 旅人走進 50m 撿起便條、首次看到內容；全世界只有一人撿得到同一張（先到先贏、輸家 `note_taken`）；回應遺失後重試同一張仍成功且 `pickedUpAt` 不被改寫；走太遠拿到伺服器當下算的實際距離；別人的旅遊紀錄與已過期的便條回與不存在一模一樣的 404。撿到的便條進收藏列表，依撿起時間新→舊翻頁。

**Blocked by:** 06, 07

**Status:** done（2026-08-25，T19 步 9）

- [x] 請求 body `coordinate`；`{id}` 非 uuid → 400 沒有 `code`；座標越界 → 400 `invalid_coordinates`
- [x] **happy path 一句 SQL**：條件式 UPDATE（id 相符、未撿、`audience=anyone`、未過期、50m 內）`RETURNING`；成功回 Note 9 鍵、content 揭露、`pickedUpAt` 有值
- [x] UPDATE 影響 0 列才診斷，順序固定：不存在 → 404 `note_not_found`；已被他人撿走 → 409 `note_taken`；已是自己撿的 → 200 回**原本的** `pickedUpAt`（冪等）；作者是自己 → 403 `own_note`；旅遊紀錄 → 404 `note_not_found`；已過期 → 404 `note_not_found`；其餘 → 403 `too_far` 附 `details.distanceM`（與探索同一算法）
- [x] **併發**：10 條真實平行 HTTP 請求撿同一張 → 恰 1 個 200、9 個 409；撿取與診斷在同一交易
- [x] 冪等測試把第一次撿取回撥再重試，證明回的是原 `pickedUpAt` 而非當下時間（沿用 T15 的手法）
- [x] 撿走的便條從探索消失；作者的 my_notes 裡它的 `pickedUpAt` 非 null
- [x] `GET /v1/me/collection?limit=&cursor=`：依 `pickedUpAt` 新→舊、（`pickedUpAt`, `id`）平手、`audience` 恆為 `anyone`、`coordinate` 是投放位置；游標機制沿用 06，拿 my_notes 的游標來 → 400 `invalid_cursor`
- [x] 60 筆同刻 `pickedUpAt`：預設 50 → 翻頁拿剩 10、無重疊；A 只撿 1 張 → 恰 1 筆且 `nextCursor` null
- [x] 跨使用者：撿到的只出現在撿起者的收藏，不出現在他人的
- [x] 頻率閘門在 09 才加；本票撿取次數控制在 60 以內
- [x] 全程紅→綠

---

## 結果（2026-08-25）

- 實作：`NoteService.pickup`／`diagnose`／`verdict`（`PICKUP` 條件式 UPDATE ＋ `DIAGNOSE` 一句診斷）、
  `NoteService.myCollection`（與 `myNotes` 共用 `page()`，差別只有查誰的欄位／排序鍵／游標列表識別）、
  `NotesController` 兩支端點、`PickupRequest` record。
- 測試：`api/src/test/java/com/kevin/hapeetrail/PickupTest.java`，27 條，紅→綠。
  全套 155/155 綠（含 `-Dspring.datasource.hikari.data-source-properties.prepareThreshold=0` 那一輪）。
- **查證落檔（值得記住的兩件事）**：
  1. `@Transactional` 只作用在 **public** 方法上——`AnnotationTransactionAttributeSource.publicMethodsOnly`
     預設為 true（spring-tx 7.0.9 的 bytecode 確認過），包內可見的方法上這個註解被**靜默忽略**。
     `pickup` 因此刻意宣告成 public，票上「撿取與診斷在同一交易」才真的成立。
  2. Supabase 映像設 `extra_float_digits = 0` ⇒ float8 以**文字**傳回時截到 15 位有效數字；
     pgjdbc 要同一句 SQL 在同一條池連線跑滿 `prepareThreshold`（預設 5）次才轉二進位、精確往返。
     所以「座標完全相等」的斷言會隨測試順序翻面。`DropNoteTest` 兩處舊斷言（票 05）因此改成
     `isCloseTo(within(1e-9))`，與 `NearbyTest` 同一套寫法——**經使用者同意才改**。

### EXPLAIN checklist（一次性，2026-08-25；10 萬列＋其中 5 萬已撿、探測點附近 40 張未撿，ANALYZE 後）

只記**計畫形狀**，不記毫秒數（理由同票 07 與 `20260729010000_note_ttl.sql` 檔頭）。

| 語句 | 計畫 |
|---|---|
| `PICKUP`（條件式 UPDATE） | `Update on notes` → `Index Scan using notes_active_location_gix`，Index Cond 是 `location && _st_expand(…, 50)`；id／audience／TTL／author 落 Filter |
| `DIAGNOSE`（失敗診斷） | `Index Scan using notes_pkey`，Index Cond `id = …`（單列） |
| `COLLECTION_FIRST` | `Limit` → `Incremental Sort`（`Presorted Key: picked_up_at`）→ `Index Scan using notes_picker_ix` |
| `COLLECTION_AFTER` | 同上，且 `picked_up_at <= :key` 被推進 **Index Cond**，只有 `(picked_up_at, id) < (…)` 留在 Filter |

兩件值得記住的事：

1. **`PICKUP` 走得到 partial index，是因為前兩個條件與索引述詞逐字一致**——
   `notes_active_location_gix` 的述詞是 `picked_up_at is null and audience = 'anyone'`
   （`20260728040000_private_notes.sql` 重建過，不是原始的單一述詞）。改動任一邊而不改另一邊，
   撿取會靜默退化。與探索共用同一個不變式。
2. **`Incremental Sort` 是預期的，不是缺陷**：`notes_picker_ix` 是 `(picked_up_by, picked_up_at desc)`，
   沒有 id，所以**只有同刻那幾筆**要再排一次，不是整份列表。要消掉它得把索引改成三欄
   ——schema 變更，票 06 已就 `notes_author_ix` 的同一個形狀請示過，**仍未定案，兩支列表一起決定**。

### code-review 兩軸（2026-08-25）

**已修（都在本票自己的程式碼裡）**

1. **`COLLECTION_FIRST` 的 javadoc 宣稱了未經驗證的計畫形狀**（standards 軸）：原本只寫「走
   `notes_picker_ix`」，漏了 id 平手鍵不在索引裡、要多一次 incremental sort。改成上表的實測結果，
   `PICKUP` 也補上 partial index 逐字一致的那條不變式。
2. **`seed(author, at, north)` 的 `north` 參數 14 個呼叫點全是 0**（standards 軸，Speculative
   Generality）：從 `NearbyTest` 複製來的，本檔沒有一條測試用得到。刪掉。
3. **「撿取與診斷在同一交易」原本零訊號**（spec 軸）：`@Transactional` 被靜默忽略時所有行為測試
   仍全綠。加 `pickupReallyRunsInATransaction` 直接問 Spring 解不解得出交易屬性。
   **突變驗證**：把 `pickup` 改回包內可見，這條立刻紅（`[@Transactional 沒生效（pickup 不是 public？）]`），
   還原後全套 156 支綠。
4. **happy path 沒驗 `expiresAt`**（spec 軸）：只比對鍵集合的話，回 null 也會過。補上
   「已撿走的仍保有 `expiresAt` ＝ `createdAt` ＋ 90 天」。
5. 兩處註解與程式碼不符：座標順序的說明掛錯方法（該屬於 `outOfRangeCoordinatesAreRejected`）、
   「三種壞游標」實際只有兩條。
6. `get()` 改收 nullable token，`theCollectionNeedsAToken` 不必自己組 `HttpRequest`。

**已回報、未修（不在本票範圍，等指示）**

- **「未撿＋公開＋未過期」三述詞現在有三份**（`INSERT_PUBLIC`／`NEARBY`／`PICKUP`）。票 07 的
  review 就建議「票 08 一併抽成共用片段（rule of three）」，但那要動票 05／07 的程式碼，未做。
- **測試腳手架第四份**：`Traveler`／`traveler()`／`post()`／`get()`／`assertProblem()`／`fieldNames()`／
  隨機基準點＋整數度地盤，四個測試類別各一份。票 06 的 review 說「票 08 出現第三份時一次搬進
  `SupabaseDbTest` 最省事」——現在是第四份，仍未搬。
- `NoteService.drop` 的死參數 `ttlCutoff`（票 05 遺留，`INSERT_PUBLIC` 早已改用 `now()`），可刪三行。
- **票 09 要付的成本（spec 軸提醒）**：v3.3 把「已經是你的 ⇒ 不算新撿取」的旁路放在閘門內、
  UPDATE **之前**；Java 版把 `is_mine` 藏在 UPDATE 之後的 `diagnose` 裡。09 加閘門時無法只在最
  前面插一段，得在閘門跳起來時另補一次 `is_mine` 查詢（與 v3.3 的 `20260728070000` 同一個做法）。

**票面順序的一處筆誤（已按正確順序實作）**：checklist 第 3 項字面把「已被他人撿走 → 409」寫在
「已是自己撿的 → 200」之前；照字面實作會讓冪等重試變成 409。實作與 v3.3 一致：`is_mine` 優先。
