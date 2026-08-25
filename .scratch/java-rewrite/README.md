# T19 施工順序表（照表執行；完結後整個目錄搬 `docs/tasks/archive/`）

規則：**每張 ticket 一個新 session**；開工貼「開工 prompt」那一行；session 結尾貼 `/handoff-local`
（`/implement` 會跑 tdd／code-review／commit，但不會更新 HANDOFF 與 TASKS）。
線上一直是 RPC 版，直到步 13；**沒有死線**，驗收沒過就不切。

| 步 | Ticket | 你先做的事（只有你能做） | 開工 prompt（照貼） | 做完的證據 |
|---|---|---|---|---|
| 0 | 提交規劃產物 | — | ✅ 已完成 `5fd2b2c` | — |
| 1 | 01 CLAUDE.md 改寫 | — | `/implement .scratch/java-rewrite/issues/01-claude-md-new-architecture.md` | grep 不再出現「不建獨立 API server／TypeScript／Edge Functions」 |
| 2 | 02 契約 v4 交夥伴 | — | `/implement .scratch/java-rewrite/issues/02-contract-v4-deliverables.md` | `redocly lint` 過、Pages 上看得到 v4；**你把三份產出交給夥伴並說「依 v4、勿碰 v3.3」** |
| 3 | 03 骨架＋Testcontainers | Docker Desktop 開著 | `/implement .scratch/java-rewrite/issues/03-skeleton-and-testcontainers.md` | `mvn test` 綠：容器起、14 支 migration 套上、health 200 |
| 4 | 04 JWT＋角色＋第一顆子彈 | session 會查證 JWT 簽章方式；**若是 HS256，由你在 dashboard（JWT Signing Keys）切到非對稱金鑰** | `/implement .scratch/java-rewrite/issues/04-jwt-and-first-tracer-bullet.md` | 五種壞 token 401、合法 token 拿到空列表；本機 `supabase db reset` 後舊測試仍全綠 |
| 5 | 10 首次部署 Fly | ✅ 2026-08-25 **容器／fly.toml／runbook 完成**；上真機那半段**刻意延後到步 11 前**（Fly 無免費方案、夥伴尚未開工）。DB 端已全部就緒 | — | 容器本機起得來；pooler IPv4／`ES256`／角色權限**已以本機探針證實**（表在票內）。Fly 部署與延遲實測隨步 11 一起做 |
| 6 | 05 留便條 | — | `/implement .scratch/java-rewrite/issues/05-drop-note.md` | 全部驗證紅→綠、Note 9 鍵、型別錯誤 400 無 code |
| 7 | 06 我的便條分頁 | — | `/implement .scratch/java-rewrite/issues/06-my-notes-pagination.md` | 29 張走完不重不漏、三種壞游標 400、跨使用者隔離 |
| 8 | 07 探索 | — | `/implement .scratch/java-rewrite/issues/07-nearby.md` | 30／70／130m、上限 20、排除四類、EXPLAIN 計畫形狀記錄 |
| 9 | 08 撿起＋收藏 | — | `/implement .scratch/java-rewrite/issues/08-pickup-and-collection.md` | 10 並行恰 1 贏、冪等回原 `pickedUpAt`、診斷順序七條 |
| 10 | 09 閘門＋TTL＋超越量 | — | `/implement .scratch/java-rewrite/issues/09-rate-gate-ttl-concurrency.md` | 429＋`Retry-After`、89／90／91 三處一致、超越量數字寫進 ticket |
| — | **升 Supabase Pro** | 你在 dashboard 升級（Free 閒置 7 天會暫停，夥伴測到一半會斷） | — | dashboard 顯示 Pro |
| 11 | 11 驗收 | **先做票 10 延後的那半段**（`fly apps create` → `secrets` → `deploy --ha=false`，指令都填好在 `api/README.md`）；與夥伴約好開工日 | `/implement .scratch/java-rewrite/issues/11-acceptance-newman-smoke.md` | newman 對 Fly 30 輪 0 失敗；smoke 全過；**你通知夥伴環境可用** |
| 12 | 12 兩個獨立複核 | — | 見下方「12 的 prompt」 | 兩份報告記進 ticket；**發現先回報、你同意才修** |
| — | **與夥伴約切換日** | 他確認 app 已改打 v4、不再打 `/rest/v1/rpc/*` | — | 日期寫進 HANDOFF |
| 13 | 13 切換 | 切換日當天才開 | `/implement .scratch/java-rewrite/issues/13-cutover.md` | 切換 migration 已 push；smoke 正面斷言 `/rest/v1/*` 全 401／403／404；newman 再全綠；`notes.test.sql` 刪除；T19 打勾 |

## 12 的 prompt（不用 `/implement`）

```
派兩個獨立 subagent 複核 .scratch/java-rewrite/issues/12-independent-reviews.md：
一個安全視角、一個正確性視角。只給 .scratch/java-rewrite/spec.md 與
docs/adr/0011-backend-java-rewrite.md，不繼承任何實作假設。
發現分 CRITICAL／MAJOR／MINOR 附重現步驟，先回報，不要修。
```

## 每個 session 結尾（照貼）

```
/handoff-local
```

## 不可跳過的三個查證（**三個都已結案**，2026-08-25）

- 步 4：JWT 是 HS256 還是非對稱金鑰 → ✅ **非對稱，`ES256`**（EC P-256）。
  ⚠️ Boot 預設只認 RS256，靠 `fly.toml` 的 `JWS_ALGORITHMS=RS256,ES256` 撐著，**這行不能刪**。
- 步 5：Fly → Supabase session pooler 的 IPv4 可達性 → ✅ pooler 是
  `aws-0-ap-northeast-1.pooler.supabase.com`、解析到 IPv4、`<角色>.<專案ref>` 對自訂角色有效
  （本機探針證實；「從 Fly 出發」那半段隨部署延後）。
- 步 3：Supabase Postgres 映像自帶哪些 → ✅ 步 3 已結（`auth.users` 自帶，未補 shim）。
