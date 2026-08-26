# 09 — 頻率閘門 ＋ TTL 三處一致 ＋ 併發超越量量化

**What to build:** 滾動一小時內第 61 次撿取被擋（429、標準 `Retry-After`、算出來的 `retryAfterS`），但對「已經是自己的那張」重試仍成功且不洩漏距離；未撿的公開便條在第 90 天於探索、撿取、額度三處**同一刻**退出地圖；Java 版兩個上限與閘門的併發超越量被量出來寫進本票——這是 ADR-0011 明列「已接受的後果」，要有數字。

**Blocked by:** 08

**Status:** done（2026-08-25）

- [x] 一小時窗內第 61 次撿取 → 429 `pickup_rate_limited`，`details.retryAfterS` ＝ 第 60 近那次離開窗口的秒數（回撥第一次撿取 17 分鐘 → 2580，證明是算的不是寫死）；回應帶 `Retry-After` header（秒）
      → `RateGateTest.theSixtyFirstPickupWithinAnHourIsRateLimited`（3590–3600 ＋ header 同值）、
      `theRetryDelayIsMeasuredFromTheSixtiethNewestPickup`（撥回 17 分鐘 → 2570–2580）、
      `theSixtiethPickupStillGoesThrough`、`pickupsOlderThanAnHourHaveRolledOutOfTheWindow`
- [x] 閘門跳起時對自己已撿到的那張重試 → 200 回原 `pickedUpAt`；真正新的撿取照樣 429；**限流下不回 `too_far`**（無距離 oracle，T15 立場）
      → `underTheGateRetryingYourOwnPickupStillSucceeds`、`theGateNeverLeaksDistance`
      （70m 外只回 429，`details` 恰只有 `retryAfterS`）
- [x] 閘門查詢只在達到 60 次時才多做一次「這張是不是我的」查詢；happy path 不多花
      → `theHappyPathDoesNotAskWhetherTheNoteIsAlreadyYours`：以 `pg_stat_statements` 數
      `ALREADY_MINE` 的 `calls`，沒被擋的撿取 delta 0、閘門跳起時 delta 1
- [x] TTL 邊界：89 天可見／可撿／佔額度，90 天與 91 天三處皆退出——半開區間在探索、撿取、額度計數**逐字同一條件**（測試以直接寫入 `created_at` 製造邊界）
      → `TtlTest.theSameBoundaryAppliesToExploringQuotaAndPicking`，**五輪**：89 天、
      差 10 秒滿 90 天、剛過 90 天 10 秒、整整 90 天、91 天。一條測試同時量三處；
      順序是探索 → 額度 → 撿取（撿取放最後才不會把後兩處變成「已撿走」）。
      **±10 秒那兩輪是複核後補的**——只有 89／91 那三輪的話，某一處用 90.5 天照樣全綠。
      ⚠️ **`>` 與 `>=` 測不到**，票面的「半開區間」只證到 ±10 秒：兩者僅在 `created_at`
      恰等於 cutoff 的瞬間不同，而查詢時的 `now()` 已前進數毫秒，那個瞬間造不出來。
- [x] 過期釋放額度：50 張全過期後可再留新的；過期便條仍在作者 my_notes 且 `expiresAt` 正確；已撿走的不受 TTL 影響（過期且已撿走 → 409 `note_taken`，不是 404）
      → 同上過期各輪的額度分支、`anExpiredNoteStaysInItsAuthorsListAndAJournalNeverExpiresAtAll`
      （順帶搬 `notes.test.sql` 的「400 天旅遊紀錄仍在、`expiresAt` 為 null」）、
      `anExpiredNoteThatWasAlreadyTakenIsStillAConflict`
- [x] 併發超越量量化：20 與 40 條平行請求對 5000 上限與 50 上限各量一次，記錄實際張數（對照 SQL 版 5019／5034、65）；若遠超 advisory 定位，**先回報**，修法是 DB constraint／trigger，不重開 RPC
      → 見下方「併發超越量實測」：**比 SQL 版小一個數量級**，不需要 DB constraint
- [x] 突變測試：拿掉 TTL 條件、拿掉閘門條件、拿掉「是不是我的」分支，各至少一條測試變紅
      → 見下方「突變測試」五個突變，全部被殺
- [x] 全程紅→綠（`RateGateTest` 先 7 支跑出 5 紅，實作後全綠；全套 172 支綠）

## 併發超越量實測

`DropNoteTest.concurrentDropsOvershootTheLimitByAtMostTheNumberInFlight`：
先塞到 `上限 − 1` 張，再同時送出 N 條 `POST /v1/notes`，最後數實際列數。三次獨立執行：

| 上限 | 平行請求 | 實際張數（三次） | 超越量 |
|---|---|---|---|
| 50（`anyone`） | 20 | 50 / 51 / 50 | 0–1 |
| 50（`anyone`） | 40 | 52 / 51 / 50 | 0–2 |
| 5000（`self`） | 20 | 5003 / 5003 / 5004 | 3–4 |
| 5000（`self`） | 40 | 5002 / 5004 / 5004 | 2–4 |

**對照 SQL 版：5019（20 連線）／5034（40 連線）／65（50 上限）。Java 版是 0–4，小一個數量級。**

原因兩個，都不是巧合：
1. 計數與 INSERT 是**同一句** SQL（`insert ... select ... where (select count(*)) < N`），
   競態窗口只有語句本身，不是整個 round trip——與 SQL 版同一形狀，沒有退化。
2. 真正的界線是 **Hikari 池大小 5**（`application.properties`），不是在途請求數：
   同時只有 5 條語句到得了資料庫，第 6 條拿到連線時看到的已是更新後的計數。
   40 條平行不比 20 條糟，數據也印證了。

結論：**遠在 advisory 定位之內，不補 DB constraint／trigger**（ADR-0011 的升級條件未觸發）。
若日後把池子調大，超越量的上界會跟著池子走——這是那個調整要一起看的數字。

測試的斷言上界因此是**池大小**（`@Value` 從設定讀，不寫死）而不是在途請求數：
後者在 50 上限、40 平行時會允許到 89 張，連 SQL 版的 65 都擋不住，等於沒有斷言。

## 突變測試

| 突變 | 變紅的測試 |
|---|---|
| 拿掉**探索** SQL 的 TTL 條件 | `TtlTest.theSameBoundary...`[90 天]、[91 天]（探索斷言） |
| 拿掉**撿取** UPDATE 的 TTL 條件 | 同上兩輪（撿取斷言：回 200 而不是 404） |
| 拿掉**額度**計數的 TTL 條件 | 同上兩輪（額度斷言：回 422 而不是 200） |
| 只把**探索**的 TTL 差 60 秒（`TTL.toSeconds() + 60`） | `theSameBoundary...`[差 10 秒]、[剛過 10 秒]——這個突變在只有 89／91 輪時是活的 |
| 拿掉閘門（`if (retryAfterS != null)` 恆假） | `RateGateTest` 5 支紅 |
| 拿掉「是不是我的」分支（閘門下一律拋 429） | `underTheGateRetryingYourOwnPickupStillSucceeds`、`theHappyPathDoesNotAskWhetherTheNoteIsAlreadyYours` |

## 實作摘要

- `NoteService.GATE`：取「窗內第 60 新的那次撿取」（`order by picked_up_at desc offset 59 limit 1`）
  而不是 `count(*)`——它存在 ⇔ 窗內已有 ≥60 次，而它滑出窗的時刻正是可以再撿的時刻，
  閘門與建議秒數是同一次索引掃描的兩個讀法。SQL 回傳的就是 `retryAfterS`。
- `NoteService.ALREADY_MINE`：只在閘門跳起時才跑。閘門被擋時**不進診斷**，
  所以限流下拿不到 `distanceM`。
- `ApiErrors`：`Retry-After` header 直接取 `details.retryAfterS` 的那個值——
  同一個數字兩種表達，沒有第二個來源可以漂移。
- 順手刪掉 `drop()` 裡一個沒有對應具名參數的 `ttlCutoff` 綁定（TTL 條件早已改用
  `now() - make_interval(...)`，這行是票 05 留下的死碼）。

## 複核（`/code-review` 兩軸）與處置

Standards 與 Spec 兩個獨立 subagent，各自只拿標準／spec，不繼承實作假設。**已修**：

1. **90 天那一輪是空的**（Spec，最嚴重）：`created_at = now() - 90 天` 在插入時取值，
   查詢時的 `now()` 已前進 ⇒ 該列嚴格早於 cutoff，`>` 改 `>=` 照樣全綠。
   → 補 ±10 秒兩輪，並把測試 javadoc 的過度宣稱（「用了 `>=` 就會紅」）改成實話。
   補完後「只把探索差 60 秒」這個突變才被殺得掉。
2. **併發斷言近乎空**：`isBetween(limit, limit - 1 + inFlight)` 連 SQL 版的 65 都會過。
   → 改以 Hikari 池大小為上界（見上）。
3. **陳舊註解**：`PICKUP` 常數與 `pickup()` 的 javadoc 還寫著「happy path 一句 SQL／
   一次 round trip」，加了閘門後是兩句。→ 兩處都改寫，並補上「閘門為何不併進 UPDATE 的
   `where`」（併進去會落進診斷，而診斷附距離）。
4. **`ApiErrors` 的 `respond()`**：先前為了加 header 把它拆掉，造成 `contentType` 重複兩處。
   → 改回傳 `BodyBuilder`，兩條路共用。
5. 補搬 `notes.test.sql` 的「400 天旅遊紀錄」情境（原本漏搬也沒寫理由）。

**未修，留給你決定**（都不影響本票驗收）：

- `CLAUDE.md` 架構原則寫著「診斷只在影響 0 列後才跑，**happy path 一句 SQL**」——
  加了頻率閘門後 happy path 是兩句。原子性的敘述仍然成立，但這半句已經不準。
  要改 `CLAUDE.md` 或補 ADR-0011 一行，都超出本票，**沒動**。
  → ✅ 2026-08-26 裁決：改 `CLAUDE.md` 那半句為「閘門一句＋UPDATE 一句，診斷只在 UPDATE 影響 0 列後才跑」。
- `expiresAt` 與三處 TTL 在 Java 端用的是 `Duration.ofDays(90)`＝固定 7776000 秒，
  v3.3 SQL 用的是 `interval '90 days'`（日曆運算）。Java 五處自洽；只有在**非 UTC 的
  資料庫 session** 且跨 DST 時會與 v3.3 差一小時。Fly 上 JVM 是 UTC ⇒ 實務無差異，
  但這是一個未申報的語意變更，記在這裡。
- `pg_stat_statements`（`theHappyPathDoesNotAskWhetherTheNoteIsAlreadyYours` 用它數查詢次數）
  沒有寫進 `supabase/migrations`，靠的是 Supabase 映像預先載入。壞掉時是紅不是假綠。
- 兩個測試軸都指出 `drop()` 裡 `ttlCutoff` 死碼的刪除、與 `ApiErrors.respond()` 的重構，
  嚴格說都超出票面。前者是 TTL 一致性的清理、後者是複核自己要求的，**都留著**，在此申報。
