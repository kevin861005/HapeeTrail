# Trailstamp 交接看板

## 目前架構

- Supabase Postgres + PostGIS（東京 ap-northeast-1）；MVP 無獨立 API server，
  業務邏輯在 Postgres RPC + RLS（見 `CLAUDE.md` 架構原則）。
- 第一階段便條功能：`supabase/migrations/20260712000000_notes.sql`
  （notes 表、3 個 SECURITY DEFINER RPC、RLS select-own policy）。
- iOS 介面契約：`docs/api/notes.md`（錯誤碼字串已凍結，改名=breaking change）。

## 目前進度

- T1（第一階段便條後端）＋ T6（API 契約 v2）＋ T7（payload 去 uuid）：✅ 全部完成。
  契約現況＝**v2.1**：`docs/api/openapi.yaml`（wire format 權威、可 import Postman）＋
  `docs/api/notes.md`（Swift 整合指南）。iOS 夥伴照這兩份開工。
- 5 支 RPC：drop_note / nearby_notes / pickup_note / my_notes / my_collection
  （列表 keyset 分頁、Note 統一 6 鍵 shape、11 個凍結錯誤 token）。

## 接下來要做

- T2 部署 hosted 專案、T3 檢舉機制、T4 便條 TTL（見 `docs/TASKS.md`）。

## 近期變更

- 2026-07-12（cli）：T9——Postman Collection＋Environment（docs/api/postman/，
  newman 全綠）；匯入即可測，token 自動帶入。
- 2026-07-12（cli）：T8——契約文件語言中立化（notes.md v2.2 去 Swift、改 curl；
  CLAUDE.md 協作規則同步改；此後 docs/api/ 不放 client 語言程式碼）。
- 2026-07-12（cli）：T6/T7——openapi.yaml、my_notes/my_collection keyset RPC、
  Note 去 uuid、invalid_cursor 防呆；4 個 MINOR 發現經 panel 仲裁處置
  （紀錄：`docs/tasks/archive/T6-api-contract-v2.md`）。
- 2026-07-12（cli）：專案初始化（git、supabase）；T1 全部產出；ADR-0001~0003。

## 雷區

- **匿名帳號清除作業**（若日後上線）：`notes.author_id` 是 `ON DELETE CASCADE`——
  清除腳本必須排除「有便條已被人收藏」的作者，否則會刪掉別人收藏裡的便條。
- 錯誤碼契約：RPC 的 `RAISE EXCEPTION` 字串是 iOS switch 的依據，動字串前先看
  `docs/api/notes.md` §8 變更政策。
- SECURITY DEFINER 函式全部 `SET search_path = ''`＋schema 限定引用；新增函式記得
  逐支 `REVOKE ... FROM public, anon`（Supabase 預設 auto-grant）。
- 部署 hosted 前確認 PostGIS 在 `extensions` schema：
  `select extnamespace::regnamespace from pg_extension where extname='postgis'`。
- **Note payload 禁 uuid**：`author_id`/`picked_up_by` 不上 wire（T7，測試有斷言）；
  新增回傳欄位前先過這條。列表 RPC 是 SECURITY INVOKER——別對 `public.notes`
  做欄位級 grant（INVOKER 的 WHERE 讀 author_id/picked_up_by 會 permission denied，已實測）。

## 最後更新

2026-07-12（cli / Claude Code session）
