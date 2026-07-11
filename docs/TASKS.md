# Trailstamp 任務清單

> 唯一任務追蹤器。打勾必附證據（commit / file:line / 指令輸出）。

## 進行中

（無）

## 待辦

- [ ] **T2** 部署到 hosted Supabase 專案（`supabase link` + `db push`；先確認 PostGIS 在 `extensions` schema）
- [ ] **T3** UGC 檢舉機制（App Store 審查前必須；`report_note` RPC + 隱藏 flag）
- [ ] **T4** 便條 TTL 政策（產品決策；技術上為 pg_cron 一句 delete）

## 已完成

（30 天內；更舊直接刪，git 歷史即檔案）

- [x] **T1** 第一階段便條後端端到端：schema + RPC/RLS + docs/api/notes.md
  ✅ 2026-07-12：migration `supabase/migrations/20260712000000_notes.sql`（`supabase db reset` 套用成功）；
  `supabase/tests/notes.test.sql` 全綠（輸出 `ALL TESTS PASSED`，重跑 grep ERROR|FAIL = 0）；
  契約 `docs/api/notes.md`；ADR-0001~0003；經獨立 subagent 複核
  （初審 FAIL 抓到測試腳本 3 個 RLS 空測 bug，修正後全綠）
