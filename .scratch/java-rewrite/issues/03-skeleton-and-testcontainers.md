# 03 — 骨架落地：`api/`、依賴、Testcontainers 起 Supabase Postgres 並套 migrations

**What to build:** 一個乾淨的 Spring Boot 4.1／Java 21 專案住在 repo 的 `api` 目錄，`mvn test` 在本機一鍵綠：Testcontainers 起 Supabase 官方 Postgres 映像（與 hosted 同大版本 17）、把 `supabase/migrations` 全部套上、`/actuator/health` 回 200。這是後面所有 TDD 的地基。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 今天建立的骨架目錄改名為 `api`；Copilot 的 `.github/modernize` 殘骸刪除；`HELP.md`、`.vscode`、`target` 不進 git；repo 根 `.gitignore` 視需要補
- [ ] 依賴只加四類：JDBC starter ＋ PostgreSQL driver、OAuth2 resource server、Actuator、Testcontainers（test scope）；不加 JPA、不加其他
- [ ] Actuator 只開 health，且不需認證；其餘端點關閉
- [ ] 設定走環境變數：資料庫 URL／帳密、JWKS 位址；repo 內只有非機密預設值與 test profile
- [ ] Testcontainers 用 Supabase 官方 Postgres 映像（查證映像自帶哪些：`extensions` schema、`auth` schema、`auth.uid()`、`anon`／`authenticated` 角色）；缺的以**最小 shim** 補在測試資源，預期只有 `auth.users`——shim 內容記進本票
- [ ] 測試啟動時依檔名順序套用 `supabase/migrations` 全部 SQL，14 支全數成功（含 SECURITY DEFINER 函式與 `auth.uid()` 引用）
- [ ] 一條測試：容器起、migration 套完、`GET /actuator/health` 200
- [ ] `mvn test` 在乾淨機器（只有 Docker）上通過；執行時間記進本票
- [ ] 日誌設定：INFO 只記路徑、狀態碼、耗時；request body 永不落日誌（後面每票沿用）
