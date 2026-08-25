# 01 — CLAUDE.md 與 HANDOFF 改寫為新架構

**What to build:** 下一個 session 打開 repo 時，讀到的技術棧、架構原則、協作分工與文件地圖與 ADR-0011 一致：後端是 Java／Spring Boot 服務，Supabase 只剩「代管 Postgres＋PostGIS」與「GoTrue auth」兩個角色，PostgREST 不再是介面。舊原則「MVP 不建獨立 API server」「後端語言 TypeScript」「Edge Functions 處理非 SQL 邏輯」全部移除，不留矛盾。這是 prefactor：不先改地圖，後面每張 ticket 都會被舊原則「修正」。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] CLAUDE.md「技術棧」改為：Spring Boot 4.1／Java 21 服務（容器化、東京、常駐）、Supabase Postgres 17＋PostGIS（東京）、GoTrue auth；iOS 有兩個 base URL（auth 打 Supabase、業務打服務）
- [ ] CLAUDE.md「架構原則」改為：業務規則全在 Java；`JdbcClient` 普通 SQL、不用 JPA；距離只在 SQL 算；撿取原子性靠單句條件式 UPDATE；服務以最小權限角色 `hapeetrail_api` 連線；schema 仍由 Supabase CLI migration 管；client 角色對 `/rest/v1/*` 零權限（ADR-0007 延續）
- [ ] CLAUDE.md「開發守則」保留原文，另加一條：日誌不記座標與便條內容
- [ ] CLAUDE.md「文件地圖」補上 ADR-0011 與 `.scratch/java-rewrite/spec.md`；提到 Edge Functions 的句子全部移除
- [ ] 產品名依 `CONTEXT.md` 寫 HapeeTrail（全文改名屬 T20，本票只改動到的段落）
- [ ] HANDOFF 的「目前架構（as-built）」段註明「RPC 版仍是生產版本，Java 版施工中（T19）」——as-built 不寫成未完成的樣子
- [ ] 全文 grep 確認 CLAUDE.md 不再出現「不建獨立 API server」「TypeScript」「Edge Functions」
