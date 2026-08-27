# 12 — 兩個獨立複核（安全、正確性）

**What to build:** 依專案守則，在切換前由兩個只拿 spec、不繼承實作假設的獨立視角複核 Java 版。發現先回報，經同意才修——本票不含修正，修正各自開 ticket 或併入 13 前的小修。

**Blocked by:** 11

**Status:** done（2026-08-27）

- [x] 安全複核（只給 `.scratch/java-rewrite/spec.md` 與 ADR-0011）：JWT 五種變形 fail-closed；每支端點的授權隔離（換 `sub` 拿不到別人的資料）；`hapeetrail_api` 在 hosted 專案的實際權限只有 `public.notes` 的 SELECT／INSERT／UPDATE（以 `pg` 目錄查詢證明）；私人／過期便條對外人與不存在無法區分；限流下無距離 oracle；Note／NearbyHint 無 uuid 身分欄位；日誌不含座標與內容；secrets 不在 repo
- [x] 正確性複核（同樣只給 spec）：10 連線同搶一張恰 1 贏；冪等回原 `pickedUpAt`；TTL 89／90／91 三處一致；50／5000／60 邊界精確；游標同刻平手與跨列表拒絕；`limit` 夾住；型別錯誤 400 無 `code`；時間戳六位小數
- [x] 兩份複核的 CRITICAL／MAJOR／MINOR 逐項列出，附重現步驟；**未經同意不修**
- [x] 複核結論與處置記進本票文末（沿用 T11-07 的格式）

---

## 複核結論與處置（2026-08-27）

兩份複核各派一個 subagent，只給 `.scratch/java-rewrite/spec.md` 與 ADR-0011，
明令禁讀 01–11 施工票、`.claude/HANDOFF.local.md`、commit message；
`docs/api/` 與既有測試被列為**受審對象**而非需求來源。兩者都不准改檔。

**合計：0 CRITICAL、2 MAJOR、13 MINOR。**

### 安全複核

隔離環境：與 hosted 同映像（`public.ecr.aws/supabase/postgres:17.6.1.143`）、同 15 支 migration，
以出貨用設定（`jwk-set-uri` ＋ `RS256,ES256`）跑真 jar，自鑄 20 餘種 JWT 打真 HTTP。

**六項全綠且是打過不是看過**：授權隔離（逐支端點確認**沒有任何查詢的資料範圍只靠 client 傳參**）、
最小權限（`public` schema 可執行函式數 **0**、`role_table_grants` 恰三列無 DELETE）、
私人／過期／不存在三者 body **逐位元組相同**（250 輪交錯計時中位數差 0.13ms 且方向與循序量測相反 ⇒ 雜訊內）、
限流下 50m 內與 1109m 外都回形狀相同的 429 且無 `distanceM`、
wire 上遞迴掃身分字樣命中 0、
主程式碼 `Logger`／`System.out`／`printStackTrace` **一個都沒有**且未用 Bean Validation
（24 種畸形請求前後 diff 日誌，新增行 0）。
全部 59 個 commit 掃過，無 DB 密碼／service_role／JWT secret。

**MAJOR ×1**：`ApiErrors` 無 catch-all，三條路徑逃出 problem+json 回 Spring 預設 500 頁
（U+0000 那條**任何拿得到匿名 token 的人都能無限次觸發**）。

### 正確性複核

**六項全綠**：併發（55 輪／725 次真平行請求，每輪恰 1 個 200、其餘 409，DB 恰 1 列；
SQL 層 10 連線同樣恰 1 `WON`；`pg_stat_statements` 證明 happy path 只有一句 UPDATE）、
冪等、TTL 邊界那一刻三處**一致**判為過期（用單交易 `now()` 凍結踩準 cutoff——
`TtlTest` 自認測不到的那格其實測得到）、
距離與上限邊界（50m／100m／50／5000／閘門 60 全部精確，`retryAfterS` 實測 2580）、
游標（29 筆與 60 筆同刻平手走完不重不漏，跨列表與 6 種竄改全 400）、
時間戳（24 個全 6 位無截斷）。

**MAJOR ×1**：`JsonConfig` 只硬化數值型別，漏了 `Textual` ⇒ `content: 123` 回 200 存成 `"123"`、
`audience: 5` 回帶 `code` 的業務錯誤。既有測試的 11 個 malformed 變形全是「數值欄位給字串」，
反方向那一格是空的，所以這個洞在紅綠燈上是隱形的。

### 處置（2026-08-27 裁決）

| 分類 | 項目 | 去向 |
|---|---|---|
| **修** | 2 條 MAJOR ＋ JWT 的 `iss`／缺 `exp` | **票 14，已完成**（189 支綠、獨立驗收有條件通過） |
| **不修** | 恰 50.000000m 撿不到（`ST_DWithin` 邊界） | v3.3 RPC 逐字相同、語意凍結；`pickable` 與 pickup 共用同一運算式，恰 50m 兩處都 false ⇒ 沒有「看得到撿不到」的分歧 |
| **不修** | 過期 token 60 秒 clock skew | Spring 預設值是刻意的（GoTrue 與裝置時鐘會漂），歸零會製造偽 401 |
| **不修** | postman local environment 的 publishable key | 設計上就要進 client、只對 127.0.0.1 有效；hosted 那份 apikey 已刻意留空 |
| **上線前** | `limit` 接受 `0x10`／`#10`／前導空白、超 `Long.MAX_VALUE` 回 400 而非夾住、`limit=` 與 `cursor=` 對空值立場相反 | 記在票 14 的「留給上線前」 |
| **上線前** | 請求 body 無大小上限（`-Xmx256m` 下 6 條併發 30MB 打出 `OutOfMemoryError`） | 同上 |
| **上線前** | `openapi.yaml` 的 `servers` 預設仍是 cleartext `http://` | 換 Fly https 時一併處理 |

### 兩個必須帶進票 13 的前提

1. **切換 migration 尚不存在**——`supabase/migrations/` 最後一支是
   `20260825000000_hapeetrail_api_role.sql`，只有準備那半。此刻 `authenticated` 仍握有五支
   SECURITY DEFINER RPC 的 EXECUTE（複本上實證），**Java 的授權模型還不是唯一路徑**；
   「契約外路徑不可達」這條驗收在它落地前無從成立。`anon` 已是零函式、零表權限。
2. **hosted 專案本體的權限未經線上驗證**——安全 agent 從容器取到憑證但連線被權限系統擋下，
   第 3 項是靜態推導 ＋ 等價複本驗證。它留了一段唯讀 SQL 給你貼進 Supabase SQL editor
   自驗（預期：`f|f|t`／0 列／恰 3 列／0 函式／`f|f`），見本次 session 的複核報告。
