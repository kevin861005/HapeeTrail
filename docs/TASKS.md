# Trailstamp 任務清單

> 唯一任務追蹤器。打勾必附證據（commit / file:line / 指令輸出）。

## 進行中

- [ ] **T11** API 契約 v3（`color`/`style`/`audience`、不透明游標、錯誤 details、JSON 白名單）
  ——spec：`.scratch/api-contract-v3/spec.md`；tickets：`.scratch/api-contract-v3/issues/`（7 張，01→07）；
  設計理由：`docs/tasks/T11-contract-v3-design.md`
  - 01 白名單建構 ✅ 2026-07-28：`supabase/migrations/20260728000000_whitelist_json.sql`；
    既有測試套件未改仍 `ALL TESTS PASSED`、newman 9/9、暫時欄位探針證明不外洩（證據見 ticket 01）
  - 02 wire 格式 v3 ✅ 2026-07-28：`supabase/migrations/20260728010000_wire_format_v3.sql`；
    camelCase＋巢狀 coordinate＋固定六位小數時間戳＋nearby envelope；`ALL TESTS PASSED`、
    newman 9/9、redocly lint 通過、EXPLAIN 索引未退化（證據見 ticket 02）
  - 03 不透明游標 ✅ 2026-07-28：`supabase/migrations/20260728020000_opaque_cursor.sql`；
    兩支列表改 `{items, nextCursor}` envelope、單一 `p_cursor` 取代 `p_before_*`＋`p_before_id`；
    `ALL TESTS PASSED`、newman 11/11（含游標 HTTP 往返）、redocly lint 通過、
    EXPLAIN 掃描節點與變更前逐字相同。code-review 抓到並修掉一個實質缺陷：
    游標未帶排序鍵身分 ⇒ 跨列表混用會靜默回錯頁（證據與修正見 ticket 03）
  - 04 樣式代號 ✅ 2026-07-28：`supabase/migrations/20260728030000_style_codes.sql`；
    `color`/`style` 兩個獨立 smallint（預設 1、可省略、超出對照表照收）、新增
    `invalid_style_code` token（實作前取得同意）；`ALL TESTS PASSED`、newman 11/11、
    redocly lint 通過、EXPLAIN 仍走 `notes_active_location_gix`；獨立 subagent 安全與
    正確性複核無 CRITICAL/MAJOR（含併發實測），code-review 的 4 項已修（證據見 ticket 04）
  - 下一步：對 05 跑 `/implement`

## 待辦
- [ ] ⏸️ **T2** 部署到 hosted Supabase 專案（`supabase link` + `db push`；先確認 PostGIS 在 `extensions` schema）
  ——等什麼：進入大量測試階段（2026-07-12 決定：開發期以本機 supabase 為主）
- [ ] **T3** UGC 檢舉機制（App Store 審查前必須；`report_note` RPC + 隱藏 flag）
- [ ] **T4** 便條 TTL 政策（產品決策；技術上為 pg_cron 一句 delete）

## 已完成

（30 天內；更舊直接刪，git 歷史即檔案）

- [x] **T10** openapi servers 補 Tailscale 位址（夥伴 Swagger UI「Try it out」用）
  ✅ 2026-07-12：mac-mini 遠端實測 Swagger UI 200＋CORS `*` 確認；lint 通過
- [x] **T9** Postman Collection＋Environment（docs/api/postman/）
  ✅ 2026-07-12：newman 實跑 9 requests / 9 assertions / 0 failed；
  紀錄見 `docs/tasks/archive/T9-postman-artifacts.md`
- [x] **T8** 契約文件語言中立化（notes.md 去 Swift、CLAUDE.md 規則同步改）
  ✅ 2026-07-12：notes.md v2.2；subagent 核對 26 條契約規則零遺失 PASS；
  紀錄見 `docs/tasks/archive/T8-language-neutral-contract.md`
- [x] **T6** API 契約 v2：OpenAPI 化＋cursor 分頁＋EXPLAIN 索引驗證
  ✅ 2026-07-12：`docs/api/openapi.yaml`（lint 通過）；`my_notes`/`my_collection` keyset RPC；
  EXPLAIN 證據與 4 個 MINOR 發現處置見 `docs/tasks/archive/T6-api-contract-v2.md`；測試全綠
- [x] **T7** Note payload 去 uuid（author_id/picked_up_by 不上 wire）
  ✅ 2026-07-12：panel 4-0 裁定提前執行（帳號綁定後 uuid 回溯連結真人、發出的資料收不回）；
  wire 實測 6 鍵；詳見 T6 checklist F4 段
- [x] **T5** 推上 GitHub（kevin861005/trailstamp）＋ CLAUDE.md 新增「開發守則」章節
  ✅ 2026-07-12：CLAUDE.md §開發守則；remote origin 設定與 push（證據見 git log）
- [x] **T1** 第一階段便條後端端到端：schema + RPC/RLS + docs/api/notes.md
  ✅ 2026-07-12：migration `supabase/migrations/20260712000000_notes.sql`（`supabase db reset` 套用成功）；
  `supabase/tests/notes.test.sql` 全綠（輸出 `ALL TESTS PASSED`，重跑 grep ERROR|FAIL = 0）；
  契約 `docs/api/notes.md`；ADR-0001~0003；經獨立 subagent 複核
  （初審 FAIL 抓到測試腳本 3 個 RLS 空測 bug，修正後全綠）
