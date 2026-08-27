# 13 — 切換：drop RPC、鎖死 `/rest/v1/*`、通知夥伴、退役舊測試、收工

**What to build:** iOS 改打 v4 的同一天，五支 RPC 與五支 helper 從 hosted 專案消失，Supabase 的 `/rest/v1/*` 對 client 角色只剩 401／403／404；煙霧測試正面斷言這件事；舊的 SQL 測試套件退役；TASKS／HANDOFF 收工。此後 HapeeTrail 服務是唯一的資料路徑，ADR-0007 的保證在新架構下延續。

**Blocked by:** 12

**Status:** ready-for-agent

- [x] 與夥伴約定切換日；他確認 app 已改打 v4 且不再依賴 `/rest/v1/rpc/*` — **2026-08-27，Kevin 確認可切**
- [x] 新增 Supabase migration「切換」：drop 五支 RPC（`drop_note`／`nearby_notes`／`pickup_note`／`my_notes`／`my_collection`）與五支 helper（`as_note_wire`／`as_wire_ts`／`as_cursor`／`parse_cursor`／`distance_m`／`note_ttl`——以實際 `pg_proc` 清單為準）；收回 client 角色殘餘 EXECUTE；`db push` 到 hosted
- [x] 煙霧測試改為**正面斷言**：`/rest/v1/notes` 各變體、五支舊 RPC 路徑對 `authenticated` 全部 401／403／404，`GET /rest/v1/` 根路徑清單為空；anon 401
- [ ] ⏳ 切換後立刻對 Fly 重跑 newman 全綠（服務不依賴任何被 drop 的函式——Testcontainers 早已套過切換 migration 證明過，此處是真機再證一次）
- [x] `supabase/tests/notes.test.sql` 退役（刪除，git 歷史即檔案）；其情境清單對照表（搬到哪張 ticket）記進本票
- [x] `docs/api/notes.md` §10 依實測改寫；HANDOFF「目前架構」改為 as-built 的新架構；CLAUDE.md 若有「施工中」字樣移除
- [x] TASKS：T19 打勾附證據（migration 檔名、newman 輸出、smoke 輸出、複核結論）；本 feature 的 spec 與 issues 搬 `docs/tasks/archive/`
- [x] 保留：`hapeetrail_api` 的準備 migration、RLS policy；不動 schema — migration `20260825000000` 與 policy `notes_api_all` 原封不動，`notes` 的欄位與索引零變更

---

## 施工紀錄（2026-08-27）

### 切換 migration

`supabase/migrations/20260827000000_cutover_drop_rpc.sql`——drop 11 支函式（5 支契約 RPC ＋
6 支 helper）＋ 改寫 `public.notes` 的表註解（舊註解寫「一律經五支 RPC 存取」，切換後不再成立）。

清單以**實際 `pg_proc`** 為準（本機 supabase 查得，11 支各只有一個多載）：

```
as_cursor(text,timestamptz,uuid)          my_collection(integer,text)
as_note_wire(notes)                       my_notes(integer,text)
as_wire_ts(timestamptz)                   nearby_notes(double precision,double precision)
distance_m(geography,geography)           note_ttl()
drop_note(text,float8,float8,int,int,text) parse_cursor(text,text)
pickup_note(uuid,double precision,double precision)
```

**「收回 client 角色殘餘 EXECUTE」不需要另外寫**：drop 會連同該函式的所有 grant 一起消失。
切換前的實測——`authenticated` 只在 5 支契約 RPC 上有 EXECUTE（6 支 helper 早在 T12
`20260728080000_close_direct_read.sql` 收乾淨）、`anon` 一支都沒有、兩者對 `public.notes`
零表權限。切換後本機複查：public schema 函式數 = 0，client 角色表權限 = 無。

⚠️ **留下的洞（不在本票範圍，值得單獨決定）**：Supabase 在 public schema 設了 default
privileges，新建的表預設 grant ALL、新建的函式預設 grant EXECUTE 給 anon／authenticated
（`20260728080000` 的註解已標記過）。切換之後 ADR-0007 的保證完全靠「client 角色零權限」，
這條 default privilege 是它唯一的靜默破口。根治要 `alter default privileges in schema public
revoke ...`，會讓往後每支新物件都必須顯式授權——專案級慣例變更。**目前由 `hosted-smoke.sh` ⑥
的根路徑清單斷言把關**（新物件一露出就紅）。

### `supabase/tests/notes.test.sql` 退役：情境對照表

RPC 版的 SQL 測試套件（45KB）刪除，git 歷史即檔案。每一段情境搬到哪裡：

| notes.test.sql 的段落 | 搬到 | 承接者 |
|---|---|---|
| `drop_note`：驗證與 trim（Unicode 空白、500 字上限、座標範圍） | 票 05 | `DropNoteTest`（26 支） |
| style／color 代號：預設、可省略、互不干擾、超範圍照收 | 票 05 | `DropNoteTest` |
| 私人便條（audience）：驗證與不合法值大聲失敗 | 票 05 | `DropNoteTest` |
| 私人便條不進他人探索 | 票 07 | `NearbyTest.ownPrivatePickedUpAndExpiredNotesAreExcluded` |
| 私人便條他人撿不到（與不存在無法區分） | 票 08 | `PickupTest.notesTheTravelerMayNotSeeAreIndistinguishableFromMissingOnes` |
| `nearby_notes`：30／70／130m、排序、`pickable`、排除自己、上限 20 | 票 07 | `NearbyTest`（7 支） |
| `pickup_note`：距離閘門、獨佔、冪等、四個診斷碼 | 票 08 | `PickupTest`（20 支，含 10 並行恰 1 贏） |
| 反濫用上限：第 51 張 `active_note_limit` | 票 05 | `DropNoteTest` |
| 過期便條釋放未撿額度 | 票 09 | `TtlTest.theSameBoundaryAppliesToExploringQuotaAndPicking` |
| 私人便條絕對上限 `private_note_limit` | 票 05 | `DropNoteTest` |
| 一小時內第 61 次撿取 `pickup_rate_limited`＋建議秒數＋閘門下冪等重試 | 票 09 | `RateGateTest`（7 支） |
| TTL：89／90／91 天三處一致、過期仍在作者 my_notes | 票 09 | `TtlTest`（3 支，含參數化邊界） |
| 列表 envelope ＋ 不透明游標（翻頁不重不漏、平手、竄改、跨清單） | 票 06 | `MyNotesTest`（10 支）＋ `PickupTest` 的收藏段 |
| 空 claims／anon → `not_authenticated` | 票 04 | `AuthTest`（4 支） |
| **資料表與 helper 對 client 完全不可達（T12）** | **本票** | `hosted-smoke.sh` ⑥（改為正面斷言，見下） |

唯一沒有 Java 對應物的是最後一列——它驗的是 **Supabase 那一側**的權限，不是服務的行為，
所以留在煙霧測試裡而不是搬進 `mvn test`。

### 煙霧測試 ⑥ 翻面

`supabase/tests/hosted-smoke.sh` 的 ⑥ 從「過渡期：正面斷言五支 RPC 還活著」翻成
**正面斷言全部不可達**：`/rest/v1/notes` 四種讀取變體 ＋ POST／PATCH／DELETE 三種寫入面、
11 支函式路徑（5 RPC ＋ 6 helper）、anon 三條，一律 401／403／404；
再加一條**根路徑清單斷言**（`GET /rest/v1/` 的 `paths` 除了 `/` 之外必須為空）——
前面的迴圈只證得到「我點名的不可達」，這一條才證得到「沒有我沒想到的東西露出來」。

### `/code-review` 兩軸複核（2026-08-27）

兩個 subagent 各自獨立跑，**都抓到實質問題**。已修的：

**Standards 軸（假綠，這一段最貴的失敗模式）**

1. **接受集合裡有 401 ⇒ 整段可能集體假綠**：⑥ 原本每條都收 `401||403||404`。
   `TOKEN_A` 哪天失效（過期、apikey 與 Bearer 搞混、GoTrue 改行為），每一條都會回 401
   而全部綠掉，**卻一次都沒真的碰到角色權限**。→ 改成**每條斷言恰好一個碼**：表 403、函式 404、
   anon 表 401、anon 函式 404，並抽出 `expect()` 讓接受集合只有一處可改。
2. **函式那組收 403 等於接受 migration 明講不接受的狀態**：403 代表「函式在、只是沒 EXECUTE」，
   而本票的立場是 drop 不是 revoke（見 migration 開頭）。**只有 404 證明它不在了。** → 改 404。
3. **anon 那兩條用 GET 打 rpc**：PostgREST 對 VOLATILE 函式本來就拒絕 GET ⇒ 那個 404 驗的是
   方法限制，不是函式已消失。→ 改 POST，與 authenticated 那組同法。
4. **`notes?id=eq.$NOTE_ID` 沒有 fallback**，而同一段的 PATCH／DELETE 有 ⇒ 前面步驟失敗時
   會變成 `id=eq.` 的 400 假紅。→ 統一走 `PROBE_ID`。
5. **根路徑清單那條證得比宣稱的少**：hosted 對 client 直接回 401，連清單都不給——它擋掉
   「清單洩漏」，但**沒有**證明 authenticated 碰不到任何物件，而那是本票唯一「涵蓋我沒想到的物件」
   的斷言。原本的註解宣稱 401「比拿到空清單更嚴」，不成立。
   → 註解改成誠實描述，並把那半的責任交給新增的
   **`SmokeTest.clientRolesOwnNothingInPublic`**：逐物件問 pg 目錄（表／view／sequence 的任何
   權限、public 的函式數、schema 的 CREATE），每次 `mvn test` 都跑且是精確的。
   **突變驗證**：交易內 `create function public.oops()` → 函式那條立刻 false；
   `create table public.oops_t(i int)` → 表那條立刻 false（順帶實證了 default privileges 的洞真的存在）。

**Spec 軸**

6. `docs/TASKS.md` 的 `NEWMAN_EVIDENCE` 佔位字串未填 → 已補（見下）。
7. 施工順序表 row 13 沒回填證據（12、12.5 都有）→ 已補。
8. `api/README.md` 仍指向 `.scratch/java-rewrite/…` 死路徑 → 已改為 archive 路徑。
9. **default privileges 的洞只記在完結票裡、沒有 T 號** ⇒ 違反「待辦單一棲息地」與收工三動作②。
   → **立成 T24**（`docs/TASKS.md`），本票只留背景說明。

**兩處超出票面、已揭露不撤回**（CLAUDE.md「嚴禁順手做」的例外交代）：

- migration 末尾改寫 `comment on table public.notes`。舊註解寫「一律經五支 SECURITY DEFINER 的
  RPC 存取」，drop 之後那句話是假的；把假話留在資料庫裡比多改一行糟。
- `SmokeTest` 的 javadoc `14 支`→`16 支`、時態由「切換那天會 drop」改為「已 drop」。
  本票加了一支 migration，那個數字是被本票改壞的，不補等於留一個錯。
