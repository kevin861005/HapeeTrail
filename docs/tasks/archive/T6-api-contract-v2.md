# T6：API 契約 v2 — OpenAPI 化 ＋ cursor 分頁 ＋ 索引驗證

> 2026-07-12 經 Kevin 核准的範圍。完結後本檔搬 `docs/tasks/archive/`。

背景：開發守則要求（1）統一格式 API 文件（2）列表查詢第一天就 cursor-based 分頁
（3）地理查詢用 EXPLAIN 確認走索引。既有契約的列表用 offset `.range()`，趁未上線改便宜。

## Checklist

- [x] 1. migration `20260712010000_my_lists.sql`：新增 `my_notes` / `my_collection` RPC
      （SECURITY INVOKER ＋ RLS 為權限層；keyset 分頁：`(created_at|picked_up_at, id)` 複合游標；
      limit 預設 50 上限 100；統一 `not_authenticated` 防禦碼）✅ db reset 套用成功
- [x] 2. 擴充 `supabase/tests/notes.test.sql`：兩支列表 RPC 的排序／游標翻頁（無重疊、無遺漏）／
      limit 邊界／not_authenticated ✅ 含同刻 timestamp 平手壓力測試（單一 transaction 的 now() 恆定）
- [x] 3. `supabase db reset` ＋ 測試全綠 ✅ 輸出 `ALL TESTS PASSED`
- [x] 4. EXPLAIN 驗證（灌 2 萬筆假資料後）✅ 證據見附錄：兩條查詢均走預期索引
- [x] 5. 新增 `docs/api/openapi.yaml`（OpenAPI 3.0.3）：6 個 endpoint
      （auth signup ＋ 5 支 RPC）、統一 request/response/error schema、可 import Postman
      ✅ redocly lint 通過
- [x] 6. 改寫 `docs/api/notes.md` 為 Swift 整合指南 ✅ wire format 移交 openapi.yaml；
      游標欄位原始字串保存（實測 timestamp 小數位數可變，證明必要）
- [x] 7. OpenAPI lint ＋ HTTP smoke test ✅ 游標翻頁 round-trip 走真實 PostgREST 驗證
- [x] 8. 兩個獨立 subagent 驗證 ✅ 安全 PASS＋正確性 PASS，零 CRITICAL、4 MINOR；
      經四視角 panel 辯論（9 agent、兩輪、全數 4-0）＋ Kevin 核准後處置如下
- [x] 9. TASKS.md 更新、commit、push ✅ 見本檔所在 commit

## MINOR 發現處置紀錄（panel 仲裁 + Kevin 核准，2026-07-12）

- [x] **F1** 游標半組防呆：XOR 守衛 raise `invalid_cursor`（新 token）、WHERE 三分支簡化為兩分支、
      openapi 補成對說明、2 個負向測試 ✅ 測試全綠＋HTTP 驗證
- [ ] ⏸️ **F2** p_limit 上限 clamp 測試：deferred；喚醒條件（二擇一）：
      (i) 任何 fixture 因其他原因達 101 列時順手加斷言；
      (ii) F3 直讀路徑若被收回（RPC clamp 成為唯一限流層）時必須加。
      行為本身已由驗證者實測正確（1000→100、null→50）。
- [x] **F3** 直讀路徑：notes.md 新增「契約外路徑」段（含 uuid 欄位揭露）；
      migration grant 旁加欄位漂移警語；**否決 DEFINER 封鎖**（panel 4-0）✅
- [x] **F4** 去 uuid：`author_id`/`picked_up_by` 自全部四個回傳移除（Note 定為 6 鍵）；
      欄位級 grant 實測會炸 INVOKER 的 WHERE（permission denied）→ 依 panel 預授權
      fallback：保留全表 grant＋F3 段落揭露 ✅ wire 實測無 uuid

## 附錄：EXPLAIN 證據（2026-07-12，2 萬筆假資料 + ANALYZE 後）

nearby_notes 查詢形狀 → 走 partial GIST：

```
Limit -> Result -> Sort
  -> Index Scan using notes_active_location_gix on notes n
       Index Cond: (location && _st_expand('0101...'::geography, '100'::double precision))
Execution Time: 0.038 ms
```

my_notes keyset 查詢形狀 → 走複合 B-tree：

```
Limit -> Sort -> Bitmap Heap Scan on notes n
  -> Bitmap Index Scan on notes_author_ix
       Index Cond: ((author_id = '...'::uuid) AND (created_at <= now()))
Execution Time: 0.479 ms
```
