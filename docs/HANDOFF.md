# Trailstamp 交接看板

## 目前架構

- Supabase Postgres + PostGIS（東京 ap-northeast-1）；MVP 無獨立 API server，
  業務邏輯在 Postgres RPC + RLS（見 `CLAUDE.md` 架構原則）。
- 第一階段便條功能：`supabase/migrations/20260712000000_notes.sql`
  （notes 表、3 個 SECURITY DEFINER RPC、RLS select-own policy）。
- iOS 介面契約：`docs/api/notes.md`（錯誤碼字串已凍結，改名=breaking change）。

## 目前進度

- T1（第一階段便條後端）：✅ 完成——migration 套用成功、測試腳本全綠、契約與 ADR 齊備
  （證據見 `docs/TASKS.md` 已完成段）。iOS 夥伴可以直接照 `docs/api/notes.md` 開工。

## 接下來要做

- T2 部署 hosted 專案、T3 檢舉機制、T4 便條 TTL（見 `docs/TASKS.md`）。

## 近期變更

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

## 最後更新

2026-07-12（cli / Claude Code session）
