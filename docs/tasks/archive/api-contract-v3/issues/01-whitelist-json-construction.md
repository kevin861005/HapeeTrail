# 01 — 回傳形狀改為白名單建構

**What to build:** 五支便條 RPC 的回傳 JSON 改為明列欄位建構，取代目前「整列轉 JSON
再扣掉幾個欄位」的黑名單寫法。**對 client 而言完全沒有任何變化**——這張票的價值在於，
此後於便條表新增欄位不會再預設出現在 wire 上，而是必須刻意寫一行才會露出。
後續每一張票都要往便條形狀裡加欄位，所以這是讓那些變更變安全的前置重構。

**Blocked by:** None — can start immediately

**Status:** done — `supabase/migrations/20260728000000_whitelist_json.sql`（2026-07-28）

- [x] 五支 RPC（留下、探索、撿起、我的便條、我的收藏）的回傳形狀改為明列欄位建構
      ✅ `drop_note`/`pickup_note` 改 `to_jsonb(as_note_wire(...))`；`nearby_notes`/`my_notes`/`my_collection`
      改 `returns setof <composite>`（回傳型別變更 ⇒ 先 drop 再 create）
- [x] 便條物件與探索提示物件的形狀各自只在一處建構、五支 RPC 共用，避免形狀日後在不同 RPC 間漂移
      ✅ 形狀＝型別：`public.note_wire`（四支共用，經 `public.as_note_wire`）、`public.nearby_hint`（唯一產生處
      為 `nearby_notes`）。往形狀加欄位只改型別＋建構處，對不齊會在套用 migration 時型別錯誤，無法靜默漂移
- [x] 既有的行為驗證套件**未經任何修改**仍全綠
      ✅ `git status supabase/tests` 零變更；`psql -f supabase/tests/notes.test.sql` → `ALL TESTS PASSED`，
      `grep -cE 'ERROR|FAIL'` = 0。另以 3 個時區逐字節比對舊黑名單運算式與新白名單輸出：完全相同
- [x] 於便條表暫時新增一個欄位，確認它不會出現在任何 RPC 的回傳中，驗畢移除
      ✅ `alter table public.notes add column leak_probe text not null default 'LEAKED'` 後五支 RPC 的鍵集：
      note ×4 = `{content,created_at,id,lat,lng,picked_up_at}`、hint = `{created_at,distance_m,id,lat,lng,pickable}`，
      `leak_probe` 均不存在；探針在單一 transaction 內 rollback，未留殘跡（`information_schema.columns` 查驗 0 筆）
- [x] 既有的 Postman collection 以 newman 執行仍全綠
      ✅ 9 requests / 9 assertions / 0 failed（drop/create 函式後先 `notify pgrst, 'reload schema'`）；
      回應總位元組數與變更前一致（3.47kB）
- [x] 函式的既有權限分工不變
      ✅ `pg_proc` 查驗：`drop_note`/`nearby_notes`/`pickup_note` = DEFINER，`my_notes`/`my_collection` = INVOKER，
      六支皆 `search_path=""`，ACL 皆 `postgres,authenticated,service_role`（anon 無）

**額外驗證（code-review 後補）：**

- EXPLAIN：`nearby_notes` 仍走 `notes_active_location_gix`；`my_notes` 的 lateral 仍走 `notes_author_ix`
  （與舊寫法同一個 Bitmap Index Scan，僅多一個 Function Scan 節點）
- `as_note_wire` 因兩支 INVOKER 列表需要而授權給 `authenticated`，經 PostgREST 成為 `notes` 的 computed column。
  HTTP 實測：作者看得到自己的列、他人拿到 `[]`（RLS 生效）、anon 為 42501。它回傳的正是已白名單化的公開形狀，
  未擴大任何讀取面（`notes` 的直讀路徑本就存在，見 `docs/api/notes.md`「契約外路徑」）

**注意：** 若過程中變更了函式簽名或增刪函式，PostgREST 的 schema cache 不會自動更新，
跨 HTTP 呼叫會得到 404 而非預期結果。任何 newman 驗證前必須先觸發 schema 重載。
