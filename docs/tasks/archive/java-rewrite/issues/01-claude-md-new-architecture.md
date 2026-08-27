# 01 — CLAUDE.md 與 HANDOFF 改寫為新架構

**What to build:** 下一個 session 打開 repo 時，讀到的技術棧、架構原則、協作分工與文件地圖與 ADR-0011 一致：後端是 Java／Spring Boot 服務，Supabase 只剩「代管 Postgres＋PostGIS」與「GoTrue auth」兩個角色，PostgREST 不再是介面。舊原則「MVP 不建獨立 API server」「後端語言 TypeScript」「Edge Functions 處理非 SQL 邏輯」全部移除，不留矛盾。這是 prefactor：不先改地圖，後面每張 ticket 都會被舊原則「修正」。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [x] CLAUDE.md「技術棧」改為：Spring Boot 4.1／Java 21 服務（容器化、東京、常駐）、Supabase Postgres 17＋PostGIS（東京）、GoTrue auth；iOS 有兩個 base URL（auth 打 Supabase、業務打服務）
- [x] CLAUDE.md「架構原則」改為：業務規則全在 Java；`JdbcClient` 普通 SQL、不用 JPA；距離只在 SQL 算；撿取原子性靠單句條件式 UPDATE；服務以最小權限角色 `hapeetrail_api` 連線；schema 仍由 Supabase CLI migration 管；client 角色對 `/rest/v1/*` 零權限（ADR-0007 延續）
- [x] CLAUDE.md「開發守則」保留原文，另加一條：日誌不記座標與便條內容
- [x] CLAUDE.md「文件地圖」補上 ADR-0011 與 `.scratch/java-rewrite/spec.md`；提到 Edge Functions 的句子全部移除
- [x] 產品名依 `CONTEXT.md` 寫 HapeeTrail（全文改名屬 T20，本票只改動到的段落）
- [x] HANDOFF 的「目前架構（as-built）」段註明「RPC 版仍是生產版本，Java 版施工中（T19）」——as-built 不寫成未完成的樣子
- [x] 全文 grep 確認 CLAUDE.md 不再出現「不建獨立 API server」「TypeScript」「Edge Functions」

---

## 結果（2026-08-25）

- `CLAUDE.md` 技術棧／架構原則／協作分工／文件地圖四段重寫；`## 開發守則` 全段逐字保留，
  只新增 `### 隱私`（日誌不記座標與便條內容）。
- H1 與內文產品名改 HapeeTrail（本檔只動到的段落；全文正名仍屬 T20）。
- 協作分工「每個 RPC／endpoint」→「每個 endpoint」。
- `.claude/HANDOFF.local.md`：as-built 段首行註明「生產版本仍是 RPC 版，Java 版施工中（T19、ADR-0011），
  驗收通過前不切換」；同時移除已失效的雷區「CLAUDE.md 目前與 ADR-0011 矛盾」（本票即是解除它的動作）。
- 驗收 grep（CLAUDE.md）：`不建獨立 API server`／`TypeScript`／`Edge Function`／`Trailstamp` 全部 0 命中。
- 複核（/code-review 兩軸）後經同意補兩處：技術棧段首加一行「Java 版施工中（T19），
  驗收通過前線上仍是 RPC 版」——CLAUDE.md 進 git 且 repo 公開，HANDOFF 的 as-built
  註記別人看不到；並刪除文件地圖中從未存在的 `docs/architecture.md` 連結。
- 複核留給後續的殘留（不在本票範圍）：開發守則仍提「RLS 是否可繞過」「Supabase RPC 語法」，
  與 ADR-0011 相左但 checkbox 3 要求逐字保留 → 併入 T20。
