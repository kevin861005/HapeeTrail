# 08 — 距離算法收斂為單一真相

**What to build:** `round(st_distance(a, b))::int` 目前寫在兩處——`nearby_notes` 的探索提示
與 `pickup_note` 的 `too_far` 附帶資料。兩者必須恆等，否則旅人會看到「探索說 60 公尺、
走過去撿卻說還差 70 公尺」的自相矛盾畫面。目前只靠一句註解拴住，收斂成一支函式。

**執行順序：** 排在 **07 之前**——07 的職責是凍結與交付，不該在兩位獨立複核者動工後
才改 RPC 本體；反過來說，先做完這張，07 的複核就自然涵蓋它。

**Blocked by:** 06 — 業務錯誤附帶 details（`too_far` 的那一處由 06 引入）

**Status:** done — `supabase/migrations/20260728060000_distance_helper.sql`（2026-07-28）

- [x] 新增 `public.distance_m(geography, geography)`，兩處呼叫它，字面的算法只留一份
      ✅ `select round(extensions.st_distance(a, b))::int`；`nearby_notes` 的內層 select 與
      `pickup_note` 的 `too_far` detail 各改為呼叫它。全庫 `st_distance` 字面量剩 1 處
- [x] **不授權給 `authenticated` 與 `anon`**
      ✅ `revoke execute … from public, anon, authenticated`（新函式預設 EXECUTE 給 PUBLIC，
      不收回就等於多開一支 RPC）。HTTP 實測 `POST /rest/v1/rpc/distance_m`：
      authenticated → **403**、anon → **401**
- [x] SQL seam 斷言：`authenticated` 與 `anon` 呼叫它都是 `permission denied`
      ✅ 測試兩處各加一行 `expect_error($$select public.distance_m(null, null)$$, 'permission denied%')`
      （RLS 段以 B 的身分、anon 段以 anon 角色）。先寫斷言得到紅燈
      （`function public.distance_m(unknown, unknown) does not exist`）再實作
- [x] 純重構，外部可觀察行為零變化
      ✅ 既有的 `distanceM` 與 `too_far` 斷言一個字未改仍全綠：`ALL TESTS PASSED`、
      `^psql:.*ERROR|FAIL:` grep = 0、newman 12/12（含 `too_far` 的 `details` 距離斷言）
- [x] 以 EXPLAIN 確認探索查詢仍走 `notes_active_location_gix`，且檢查是否被 inline
      ✅ 6 萬列（5 萬 `anyone` ＋ 1 萬 `self`）：`Bitmap Index Scan on notes_active_location_gix`，
      Index Cond `location && _st_expand(…, '100')` 與變更前逐字相同，無 Seq Scan。
      **但函式未被 inline**——planner 不 inline 帶 `SET` 子句的 SQL 函式，見下
- [x] 契約產出物不動
      ✅ wire 形狀、錯誤 token、`details` 內容皆未變，三份文件零改動。
      **對 07 的影響：這支不授權給任何 client 角色，因此不必進「契約外路徑」的揭露清單**
      （`as_wire_ts`／`as_cursor`／`parse_cursor` 要揭露，是因為兩支 SECURITY INVOKER 列表
      逼得它們必須授權給 `authenticated`；這支沒有那個理由）

**發現並記在程式碼裡的天花板（`ponytail:` 註解）：** 維持專案慣例的空 `search_path`，
代價是 planner **無法 inline** 帶 `SET` 子句的 SQL 函式，於是探索查詢每個候選列多一次
函式呼叫。實測（本機，10 萬次呼叫，`jit=off`，交錯重跑取穩定值）：

| | 帶 `SET search_path`（現況） | 無 `SET`（可 inline） |
|---|---|---|
| 10 萬次呼叫 | 175 / 228 ms | 18 / 27 ms |
| 每列增量 | — | 約 1.3–1.7 µs |

放進真實查詢：MVP 規模（100m 內數十列）約 0.1ms，看不出來；極端密度壓力測試
（6 萬列擠在 166×90m，等於幾萬個候選列）探索由 ~46/73ms 變 ~110/126ms。
**升級路徑**：熱門地點的密度若真的長到那個量級，拿掉那一行 `SET` 即可 inline——
函式本體已全 schema 限定，且兩支呼叫端都是空 `search_path` 的 SECURITY DEFINER，
呼叫端控制不了它看到的 path。刻意不現在拿掉：那是動到專案的安全慣例
（spec「所有函式維持空的 search_path 與 schema 限定引用」），值得單獨決定而非順手改。
