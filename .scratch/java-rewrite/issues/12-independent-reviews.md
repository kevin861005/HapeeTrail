# 12 — 兩個獨立複核（安全、正確性）

**What to build:** 依專案守則，在切換前由兩個只拿 spec、不繼承實作假設的獨立視角複核 Java 版。發現先回報，經同意才修——本票不含修正，修正各自開 ticket 或併入 13 前的小修。

**Blocked by:** 11

**Status:** ready-for-agent

- [ ] 安全複核（只給 `.scratch/java-rewrite/spec.md` 與 ADR-0011）：JWT 五種變形 fail-closed；每支端點的授權隔離（換 `sub` 拿不到別人的資料）；`hapeetrail_api` 在 hosted 專案的實際權限只有 `public.notes` 的 SELECT／INSERT／UPDATE（以 `pg` 目錄查詢證明）；私人／過期便條對外人與不存在無法區分；限流下無距離 oracle；Note／NearbyHint 無 uuid 身分欄位；日誌不含座標與內容；secrets 不在 repo
- [ ] 正確性複核（同樣只給 spec）：10 連線同搶一張恰 1 贏；冪等回原 `pickedUpAt`；TTL 89／90／91 三處一致；50／5000／60 邊界精確；游標同刻平手與跨列表拒絕；`limit` 夾住；型別錯誤 400 無 `code`；時間戳六位小數
- [ ] 兩份複核的 CRITICAL／MAJOR／MINOR 逐項列出，附重現步驟；**未經同意不修**
- [ ] 複核結論與處置記進本票文末（沿用 T11-07 的格式）
