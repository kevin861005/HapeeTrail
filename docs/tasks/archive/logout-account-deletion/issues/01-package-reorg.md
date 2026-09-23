# 01 — 分包整理（prefactor）

**What to build:** Java 服務由單一扁平 package 改為 package by feature：root 只留啟動類，
設定與錯誤處理歸 `config`，便條功能歸 `notes`。純機械搬移（改 package 宣告與 import），
行為零改變——讓票 03/04 的新程式碼（`auth`、`account`）直接長在對的位置。

**Blocked by:** None — can start immediately.

**Status:** done ✅ 2026-09-22

- [x] root package 只剩啟動類；既有 main 類別依 spec 的分包歸位（config／notes）
      ✅ `api/src/main/java/com/kevin/hapeetrail/` 只剩 `HapeetrailApplication.java`；
      `config/`＝SecurityConfig、JsonConfig、ApiErrors（＋ApiException，見下）、
      `notes/`＝Note、NoteService、NotesController、Cursor
- [x] 測試檔暫不分包（grilling 裁決），僅修 import
      ✅ 三支測試留在 root package。`MyNotesTest`／`PickupTest` 各加 import、
      兩處 `{@link}` 降為 `{@code}`（指向跨 package 看不見的型別）。
      ⚠️ **一處超出「僅修 import」**：`PickupTest.pickupReallyRunsInATransaction`
      原以 `getDeclaredMethod("pickup", …, PickupRequest.class)` 取方法，而 `PickupRequest`
      是 notes 包內型別、root package 看不見。改為按方法名過濾 `getDeclaredMethods()`。
      代價：失去簽章釘選（`pickup` 日後多載會靜默取到任一支）。見下方「待 Kevin 裁決」
- [x] `./mvnw test` 全綠，且無任何行為性 diff（純搬移可由 diff 檢視佐證）
      ✅ 搬移前 `Tests run: 193, Failures: 0, Errors: 0`／BUILD SUCCESS；
      搬移後同樣 `Tests run: 193, Failures: 0, Errors: 0`／BUILD SUCCESS（同一組測試、同一個數字）。
      ✅ 正規化比對（全部 main 原始碼去掉 `package`／`import`／`public` 後排序 diff）逐行相同
- [x] 不回改歷史施工紀錄（archive 內文件提到的舊路徑保持原樣）
      ✅ `git status docs/tasks/` 乾淨，archive 未被碰

## 施工結果與待 Kevin 裁決（獨立複核兩份，2026-09-22）

**Java 語法強迫的一處偏離**：`ApiException` 原本是 `ApiErrors.java` 裡的第二個 top-level class。
它被 `notes` 的 `NoteService`／`Cursor` 丟出，跨 package 就必須 `public`，而 Java 要求 public
top-level class 自成同名檔——於是 `config/` 多一支 `ApiException.java`（spec 列了三支）。
`status()`／`details()` 維持 package-private，只有 class 與建構子放寬。

**可見度放寬清單**（全部由「測試留在 root package」這個裁決逼出來，非設計需要）：

| 型別／成員 | 原 | 新 | 逼它的是 |
|---|---|---|---|
| `ApiException` class＋ctor | package-private | public | `notes` 要丟它（真跨 package 需求） |
| `NoteService` class | package-private | public | `PickupTest` 反射 |
| `Cursor` record | package-private | public | `MyNotesTest` 鑄外來游標 |
| `Cursor.encode()` | package-private | public | 同上 |

`Note`／`Coordinate`／`DropRequest`／`NotePage`／`NearbyHint`／`NearbyResult`／`PickupRequest`、
`Cursor.decode()`、`ApiErrors`、`JsonConfig`、`SecurityConfig`、`NotesController` 全部維持
package-private。

**兩份複核都點名、留給 Kevin 決定（本票不自行擴張範圍）**：

1. **測試分包與否**。不分包 ⇒ `NoteService`／`Cursor` 只為測試而 public，且 `NoteService`
   是「名義上 public」——它的參數與回傳型別（`Note`、`PickupRequest`、`NotePage`）仍是
   package-private，外部根本用不了。真要收乾淨，就是把測試檔一併移進 `notes`／`config`
   （grilling 當時裁決「暫不」）。
2. **`ApiException` 的歸屬**。它是**業務**錯誤契約，不是設定；放 `config/` 是搬移的機械結果。
   若要正名，`error/`（或留 root）更貼切——但那是設計動作，不在本票。

兩項都不在本票驗收條件內，現狀不影響行為，也不影響票 03／04 開工。
