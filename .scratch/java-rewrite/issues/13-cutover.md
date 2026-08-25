# 13 — 切換：drop RPC、鎖死 `/rest/v1/*`、通知夥伴、退役舊測試、收工

**What to build:** iOS 改打 v4 的同一天，五支 RPC 與五支 helper 從 hosted 專案消失，Supabase 的 `/rest/v1/*` 對 client 角色只剩 401／403／404；煙霧測試正面斷言這件事；舊的 SQL 測試套件退役；TASKS／HANDOFF 收工。此後 HapeeTrail 服務是唯一的資料路徑，ADR-0007 的保證在新架構下延續。

**Blocked by:** 12

**Status:** ready-for-agent

- [ ] 與夥伴約定切換日；他確認 app 已改打 v4 且不再依賴 `/rest/v1/rpc/*`
- [ ] 新增 Supabase migration「切換」：drop 五支 RPC（`drop_note`／`nearby_notes`／`pickup_note`／`my_notes`／`my_collection`）與五支 helper（`as_note_wire`／`as_wire_ts`／`as_cursor`／`parse_cursor`／`distance_m`／`note_ttl`——以實際 `pg_proc` 清單為準）；收回 client 角色殘餘 EXECUTE；`db push` 到 hosted
- [ ] 煙霧測試改為**正面斷言**：`/rest/v1/notes` 各變體、五支舊 RPC 路徑對 `authenticated` 全部 401／403／404，`GET /rest/v1/` 根路徑清單為空；anon 401
- [ ] 切換後立刻對 Fly 重跑 newman 全綠（服務不依賴任何被 drop 的函式——Testcontainers 早已套過切換 migration 證明過，此處是真機再證一次）
- [ ] `supabase/tests/notes.test.sql` 退役（刪除，git 歷史即檔案）；其情境清單對照表（搬到哪張 ticket）記進本票
- [ ] `docs/api/notes.md` §10 依實測改寫；HANDOFF「目前架構」改為 as-built 的新架構；CLAUDE.md 若有「施工中」字樣移除
- [ ] TASKS：T19 打勾附證據（migration 檔名、newman 輸出、smoke 輸出、複核結論）；本 feature 的 spec 與 issues 搬 `docs/tasks/archive/`
- [ ] 保留：`hapeetrail_api` 的準備 migration、RLS policy；不動 schema
