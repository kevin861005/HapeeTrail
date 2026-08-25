# ADR-0011：後端全換 Java／Spring Boot，DB 與 auth 留在 Supabase

日期：2026-08-25　狀態：已採納（T19 施工；Java 版通過驗收前，Supabase RPC 版仍是生產版本）

## 決策

業務邏輯**全部**從 Postgres 函式搬進 Spring Boot 服務；切換完成後 drop 五支 RPC。
DB 不換（Postgres 17＋PostGIS）、schema 一字不動；Supabase 留下但只剩兩個角色：代管 Postgres＋PostGIS（Pro，東京）
與 GoTrue auth。PostgREST 關不掉，因此改以權限鎖死：client 角色對表與所有函式零權限，`/rest/v1/*` 只會回 401／403／404。

本 ADR 推翻 CLAUDE.md 架構原則「MVP 不建獨立 API server，出現遷移訊號才建」——訊號已出現
（見下）——並把後端語言從 TypeScript 改為 Java。ADR-0001～0010 的**語意全部沿用**，只是實作位置
從 SQL 改為 Java；ADR-0007「client 對表零權限」原則延續，資料進出的唯一路徑從 RPC 改為 Java API。

## 觸發訊號

- **擁有者無法獨立審查安全關鍵碼**：DEFINER 函式、`search_path`、權限收回這些東西 Kevin 讀不動，
  等於系統最敏感的一層沒有人能覆核。這是結構性的，不是某次事件。
- 後續功能（T3 檢舉、成就、打卡）多半不是 SQL 形狀；CLAUDE.md 為此預留的 Edge Functions（TS）層
  從未出生，Java 直接取代它。

刻意不採的理由：「熟悉感」本身不是訊號——DB 不管誰代管都是同一台 Postgres。

## 範圍（逐層）

| 層 | 決定 |
|---|---|
| 業務規則 | **全搬 Java**：距離判定、50／5000 上限、撿取頻率閘門、TTL、Unicode 空白 trim、游標分頁 |
| DB 存取 | `JdbcClient` 下普通 SQL，**不用 JPA**（一張表、三句帶 PostGIS 的查詢，entity 對映是純成本）。撿取仍是單句 conditional UPDATE，ADR-0001 的原子性鎖在語句層，與函式無關 |
| DB 角色 | 新開 `hapeetrail_api`：只給 `public.notes` 的 DML ＋ `extensions` 的 usage；**不用 `postgres` 超級使用者連線**。授權判斷（作者才看得到 my_notes、撿取者才看得到收藏）全在 Java 依 JWT `sub` 做——DB 端已無 RLS 可靠，連線角色權限必須最小 |
| Schema | 不動。`author_id`／`picked_up_by` 仍 FK 到 `auth.users`；migration 仍由 `supabase/migrations` ＋ Supabase CLI 管，不引入 Flyway |
| Auth | **留 GoTrue**：Spring 只驗它簽的 JWT（`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`）。後果：iOS 有兩個 base URL——`/auth/v1/*` 打 Supabase，業務打 Spring |
| 契約 | **v4，只換 transport**：`POST /v1/notes`、`POST /v1/notes/nearby`（座標不進 URL）、`POST /v1/notes/{id}/pickup`、`GET /v1/me/notes`、`GET /v1/me/collection`；錯誤走 RFC 9457 `ProblemDetail`（HTTP 狀態碼＋`code` token＋details 為真物件）。凍結 token、Note 9 鍵、游標語意、未知值政策全部沿用 |
| 部署 | 三個不可換的約束：①容器化 ②東京 ③常駐不縮零（JVM 冷啟動不可接受）。平台可換，目前選 Fly.io `nrt` |
| 本機 | Testcontainers `postgis/postgis` |
| Repo | 單一 repo（契約、schema、實作同一份 git 歷史）；現有的 `hapeetrail/` 骨架改名為 `api/`（repo 已叫 HapeeTrail，再一層同名是贅字）；Boot 4.1／Java 21（Boot 3.5 OSS 支援已於 2026-06 到期） |
| RPC | 切換後 **drop、不並存**：同一規則兩份實作遲早分岔，且 RPC 對 client 角色仍可執行，等於留一條繞過 Java 的路 |

## 否決的替代方案

- **混合**（核心迴圈留 DB、新功能才用 Java）：沒解決訊號——你維護不動的 SQL 還在原地。
- **薄殼**（Spring 只轉呼 `select * from pickup_note(...)`）：同上，只是多一層包裝。
- **換資料庫**：綁住我們的不是 Supabase，是 PostGIS（100m／50m 是產品核心）與 row-lock 撿取原子性；
  換到任何非 Postgres 都要重新設計、重新審查這兩件事。
- **自架 Postgres／離開 Supabase**：auth 是整個系統最不該手寫的一塊——匿名帳號發 token、refresh 輪替、
  Sign in with Apple（有任何第三方登入即為 App Store 強制）、匿名→正式綁定且便條不丟、金鑰輪替、
  auth 端點防濫用。GoTrue 已替我們通過一次獨立安全審查。Supabase 自架版更是兩頭壞：省了錢卻接手 GoTrue 維運。
- **契約相容模式**（Spring 原樣重現 `/rest/v1/rpc/*`＋`P0001`）：App 未上線是唯一便宜的破壞時機，
  相容等於把 PostgREST 的形狀鑄進 Java 一輩子。
- **雙軌過渡**：兩人團隊不做。

## 已接受的後果

- **安全審查與 T1–T18 的驗證重做一遍**——不是從零：`notes.test.sql`（848 行）隨 RPC 退役，
  但它的情境清單是資產（獨佔撿取 10 連線只有 1 贏、閘門下冪等仍成功、89／90／91 天三處一致、
  50／5000 精確、私人便條不進探索、游標跨列表混用被拒、Unicode 空白……），逐條搬成 Java 測試，
  不搬的要寫理由。
- **上限與撿取頻率閘門的併發超越量可能變大**：SQL 函式裡「查數量→動作」在同一交易；Java 端是兩句，50／5000 上限與 `pickup_rate_limited` 閘門同一形狀。
  若實測超越量遠超 advisory 定位（T13：20 連線 5000→5019），修法是補 DB constraint／trigger，
  **不重開 RPC**。
- **正式上線必須 Supabase Pro**：Free 方案閒置 7 天會被暫停、且不能下載備份（官方文件）。
- PostgREST 關不掉：drop RPC 後 hosted-smoke 要正面斷言 `/rest/v1/*` 全部 401／403／404。
- iOS 夥伴 2026-08-25 尚未開始串接：v4 對他零重工，但**必須請他不要對 v3.3 開工**；v4 的 openapi 先出、先交付，讓 iOS 與 Java 依同一份契約並行。

## 驗收與切換

驗收＝**同一份契約語意**在 Fly.io 上全綠：newman 改成 v4 後 15/15 ＋ `hosted-smoke.sh` 改打 Spring
（匿名登入那段仍打 Supabase）＋ 兩個獨立複核（安全：JWT fail-closed、Java 端授權、角色最小權限、
契約外路徑不可達；正確性：併發、邊界）。

切換＝一刀切：Java 過驗收 → iOS 改打 v4 → 同一天 drop 五支 RPC ＋ 收回 client 角色對所有函式的 EXECUTE。
**驗收前不設死線、不強切**；iOS 一旦改打 v4 即不回頭。

## 遷移訊號（向前看的絆線）

| 訊號 | 觸發條件 | 出口成本 |
|---|---|---|
| 離開 Supabase 的 DB | 帳單明顯高於「VPS＋維運時間」／需要 Supabase 沒有的 region（中國）／需要的 extension 被擋 | 便宜：純 Postgres，`pg_dump` 即走 |
| auth 搬出 GoTrue | 離開 Supabase 時必然／需要 GoTrue 不支援的登入流程 | 貴：換 JWT 發行者＋自建 `users` 表＋匿名→正式綁定＋獨立安全審查 |
| 離開 Fly.io | 成本／需要 autoscale／需要與 DB 同雲私網 | 便宜：容器可攜 |
| 規則回 DB 端 | Java 端規則在併發下守不住 | 補 constraint／trigger，不重開 RPC |

## 產品名

自本 ADR 起產品正名 **HapeeTrail**（`CONTEXT.md`）；文件中的 Trailstamp 殘留另開 ticket 清理，
Java package、DB 角色、artifact 一律用新名。
