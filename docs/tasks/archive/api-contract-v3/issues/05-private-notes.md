# 05 — 私人便條（audience）

**What to build:** 旅人可以留下只給自己的便條，當作這趟旅程的紀錄。私人便條不會出現在
任何人的探索結果中、不會被任何人撿走、也不佔用未撿便條的數量上限——旅人可以長期持續
記錄旅程，而不會撞上一個原本是為了防濫用而設的限制。

私人便條正常出現在旅人自己的便條列表中，且列表要能分辨哪些是公開便條、哪些是旅遊紀錄。

**Blocked by:** 04 — 便條樣式代號（color / style）

**Status:** done — `supabase/migrations/20260728040000_private_notes.sql`（2026-07-28）

- [x] 便條資料模型新增 `audience` 欄位，值為 `anyone` 與 `self` 並以 CHECK 約束；既有列回填為 `anyone`
      ✅ `add column audience text not null default 'anyone'` ＋
      `constraint notes_audience_values check (audience in ('anyone','self'))`；既有列由 column default 回填
- [x] 此欄位使用文字型別加 CHECK，**不使用資料庫的 enum 型別**
      ✅ 全程未建立任何 enum 型別（`create type` 零次）；理由寫在 migration 檔頭
- [x] 留下便條時參數可省略，預設為 `anyone`
      ✅ `p_audience text default null` ＋ `coalesce(…, 'anyone')`；HTTP 實測：省略 → `"audience":"anyone"`，
      給 `"self"` → `"audience":"self"`
- [x] 私人便條**不出現在任何人的探索結果中**
      ✅ `nearby_notes` 內層 where 加 `and n.audience = 'anyone'`（在 `author_id <> v_uid` 之前，
      故對作者自己也成立）
- [x] 他人嘗試撿取私人便條時回 `note_not_found`，且**未新增任何錯誤 token**
      ✅ `pickup_note` 的 UPDATE 謂詞加 `and n.audience = 'anyone'`（撿不走），診斷段新增
      `elsif v_row.audience <> 'anyone' then raise 'note_not_found'`——**排在 `own_note` 之後**，
      故作者撿自己的旅遊紀錄仍是 `own_note`（對自己沒有隱藏的必要），外人才拿到與
      「便條不存在」完全相同的答案
- [x] 未撿便條的數量上限**只計算 `anyone` 便條**
      ✅ 計數加 `and n.audience = 'anyone'`，**且整道閘門以 `if v_audience = 'anyone' and …` 短路**。
      只做前者不夠：旅人一旦有 50 張未撿的公開便條，連留旅遊紀錄都會被擋——正是 US7
      「不會撞到為了防濫用而設的限制」要避免的事
- [x] 私人便條正常出現在旅人自己的便條列表中，`audience` 值可見
      ✅ `as_note_wire` 加 `'audience'` 鍵（Note 由 7 鍵變 8 鍵）；五支 RPC 共用同一建構處。
      探索提示刻意**不**加此鍵——那裡永遠只有 `anyone`，是個常數
- [x] 探索用的部分索引，其條件加入 `audience`
      ✅ `drop index` ＋ 重建為 `where picked_up_at is null and audience = 'anyone'`；
      條件與 `nearby_notes` 的 where 逐字一致（不一致查詢計畫就用不到它）
- [x] 以查詢計畫確認地理查詢仍走索引
      ✅ 6 萬列（5 萬 `anyone` ＋ 1 萬 `self`）＋ ANALYZE，同一份資料上比對變更前後：
      | | BEFORE（舊索引＋舊查詢） | AFTER（新索引＋新查詢） |
      |---|---|---|
      | 掃描節點 | `Bitmap Index Scan on notes_active_location_gix` | 逐字相同 |
      | Index Cond | `location && _st_expand(…, '100')` | 逐字相同 |
      | Recheck Cond | `picked_up_at IS NULL` | ＋ `AND audience = 'anyone'` |
      | 索引取出的候選列 | 143 | 120 |
      | 估計 cost | 2407.41 | 2131.58 |
      計畫形狀未退化，候選列與 cost 皆略降（私人便條被擋在索引外）。無 Seq Scan
- [x] SQL seam 斷言涵蓋：私人便條不進他人探索結果、他人撿取回 `note_not_found`、上限不計私人便條、作者自己看得到
      ✅ 新增使用者 F（座標 20,20，遠離既有測試點）：**同一點放公開與私人各一張**，E 的探索
      必須恰好看到 1 張且不是私人那張——刻意不用「只放私人便條、斷言探索為空」，那種測試在
      整支查詢壞掉時也會綠。另含：`audience` 往返、E 撿私人 → `note_not_found`、
      F 撿自己的 → `own_note`、F 的 my_notes 同時看得到兩種且分得出來。
      上限測試改造使用者 C：**先放 3 張旅遊紀錄再放 50 張公開**（上限若誤計私人便條，
      第 48 張就會被擋）＋ 達上限後仍可再放旅遊紀錄（兩個方向都測到）。
      `ALL TESTS PASSED`、newman 11/11、`^psql:.*ERROR|FAIL:` grep = 0
- [x] OpenAPI 規格與語意文件同步更新
      ✅ `openapi.yaml`：新增 `Audience` schema、Note 加 `audience` 並列入 required、
      drop_note 加 `p_audience` 參數、`ApiError` enum 加 `invalid_audience`、
      nearby/pickup/my_notes 三段 description 補上私人便條的行為；redocly lint 通過。
      `notes.md`：§3 Note 形狀改 8 鍵 ＋ **`audience` 行為對照表**（六個路徑各自的差異）、
      §4 參數與上限規則、§5 探索排除、§6 撿取回應、§7 兩支列表的 audience 語意、
      §8 錯誤表加一列並改寫 `active_note_limit`／`note_not_found` 說明、§10 changelog

**新增的錯誤 token（實作前取得同意）：** `invalid_audience`。票上只寫「以 CHECK 約束」而未
指定拒絕方式；若只靠 table CHECK，client 會收到 `23514`，依契約 §2 被導向「session 刷新／
通用重試」——對永久性的 client bug 是錯的指引，且會洩漏約束名。與 04 的
`invalid_style_code` 同一模式，依契約政策為非破壞性變更。

**與 color/style 相反的一點（寫進 migration 與契約）：** 那兩個代號後端不理解語意、
未知值照收；`audience` 後端**必須理解並據以過濾**，因此不認得的值一律拒絕、不走預設——
靜默走預設會把旅人以為私密的便條變成公開的，且兩側都不會有任何錯誤訊號。
也因此不比對大小寫、不 trim：在這個欄位上猜使用者意圖的失敗成本是「私密內容變公開」。

**code-review 後的修正：** ①constraint 名稱 `notes_audience_check` → `notes_audience_values`
（`_check` 是 Postgres 自動生成的後綴、不帶語意；同表其他約束一律語意命名：
`notes_content_len`／`notes_lat_range`／`notes_pickup_pair`／`notes_color_range`）。
②`Audience` schema 的 `default: anyone` 移到請求參數上——回應欄位 `Note.audience` 已在
required，component 層的 default 會讓 codegen 誤解為可省略（04 對 `StyleCode` 修過同一個問題，
這裡又犯了一次）。③Postman 的 drop_note body 補 `p_audience`，讓新參數的 HTTP 路徑也被跑到。
④migration 中「EXPLAIN 複驗見 ticket 05」的裸引用改寫為「條件不一致 ⇒ 查詢計畫用不到索引 ⇒
探索靜默退化成全表掃描」，讓註解自己說得出理由。

**留給 07 處理（本票刻意不做）：** ticket 07 要在語意文件寫「未知 enum 值一律走預設」的
未知值政策。**`audience` 必須有例外條款**——對它套用「走預設」就是 fail-open，正是本票要
擋掉的失敗模式（client 若把日後新增的第三種 audience 顯示成 `anyone`，等於在列表上把旅遊
紀錄標示為公開）。07 撰寫該節時必須明文排除此欄位。

**未處理（判斷為不值得）：** `'anyone'`／`'self'` 這組合法值出現在七處（table CHECK、
RPC 粗檢、索引謂詞、nearby where、pickup UPDATE、pickup 診斷、OpenAPI enum）。收斂成單一
真相需要 domain 型別，但索引謂詞與查詢 where **必須**維持字面量才會被查詢計畫採用，
省不掉——與 04 的 `1..32767` 同一類取捨。Postman 未加私人便條的完整流程：seam B 只該放
SQL 邊界看不到的東西，而 `audience` 的值語意在 seam A 已完整覆蓋，加進 collection 只會
破壞「資料夾順序即完整流程」的交付設計。
