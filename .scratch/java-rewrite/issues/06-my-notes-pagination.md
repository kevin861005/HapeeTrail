# 06 — 我的便條分頁與游標

**What to build:** 旅人翻閱自己留過的所有便條（公開與旅遊紀錄，含已被撿走與已過期的），每頁最多 100、預設 50，用不透明游標翻頁，翻到底 `nextCursor` 為 null；拿錯游標、竄改、亂字串一律 `invalid_cursor` 大聲失敗。游標機制在此建成，08 的收藏列表直接沿用。

**Blocked by:** 05

**Status:** done（2026-08-25，`MyNotesTest` 14 支綠；全套 114 支綠）

- [x] `GET /v1/me/notes?limit=&cursor=`：`limit` 省略 50、越界靜默夾到 1–100（0／負數 → 1、101 以上 → 100）
- [x] 排序 `createdAt` 新→舊，以（`createdAt`, `id`）平手；keyset 多取一筆決定 `nextCursor`
- [x] 游標：base64 JSON，含版本、所屬列表、排序鍵、id；不簽章不加密；由 Java 編解碼（與 SQL 版不相容，無需相容）
- [x] 29 張以每頁 10 走完：不重複、不遺漏、(createdAt,id) 嚴格遞減；頁大小恰等於總數時 `nextCursor` 為 null
- [x] 全部同刻的便條（測試內固定時鐘或直接寫入同一 `created_at`）翻頁無重疊——複合游標的平手邏輯壓力測試
- [x] 無法解碼、被竄改、版本不符、**屬於其他列表**的游標 → 400 `invalid_cursor`；只斷言外部行為，不斷言內部編碼
- [x] 跨使用者隔離正面斷言：B 有 29 張、A 一張沒有 → A 的 my_notes 為 `{items:[],nextCursor:null}`
- [x] 已被撿走的（`pickedUpAt` 非 null）與已過期的都仍在列表裡（過期不消失，ADR-0010）
- [x] 全程紅→綠

## 結果（2026-08-25）

- `Cursor`（新檔）＝兩支列表共用的唯一編解碼處：base64**url** 的 JSON
  `{v, l, t, i}`（版本／所屬列表／排序鍵／id），不簽章不加密。base64url 不是隨手選的
  ——標準 base64 的 `+` 在 query 會被解成空白；`walk()` 刻意不 URL-encode 游標，
  哪天編碼漏出 `+` 或 `/` 這條測試就紅。
- `NoteService.myNotes(author, limit, cursor)`：`Math.clamp(limit, 1, 100)`、
  keyset `(created_at, id) < (:key, :id)`、多取一筆決定 `nextCursor`。
  兩句 SQL（首頁／後續）而不是一句帶 null 參數——後者的 null 型別推導在 row comparison 裡很脆。
- `NotePage` 從 `NotesController` 搬到 `Note.java`（wire 形狀那一檔）：
  現在 envelope 由 service 組出來，controller 只是轉交。
- 「屬於其他列表的游標」這一條**直接對 `Cursor.decode` 施測**：收藏端點還不存在（票 08），
  外部無路可走。斷言的仍是行為（被拒），不是內部編碼。
- 票 08 的收藏列表換一個列表名與排序欄位即可沿用；`PAGE` 樣板的 where 片段是可換的那一塊。

## `/code-review` 兩軸的處置（2026-08-25）

改掉的三處：

- **`limit` 收 `Long` 不收 `Integer`**（spec 軸）：`?limit=99999999999` 原本在 Spring 轉型階段
  就 400，而契約說越界要**靜默夾到 100**。ceiling 搬到 2^63 並已加測試。
- **旅遊紀錄沒進過測試資料**（standards 軸）：`seed` 原本每一列都是預設的 `anyone`，
  於是「公開與旅遊紀錄都在」從沒被驗過。改成四類各一張一起斷言。
- **「其他列表的游標」改走 HTTP**（spec 軸）：原本只斷言 `Cursor.decode` 丟的 exception，
  現在斷言 400 ＋ problem+json 的 `invalid_cursor`。游標仍由 `Cursor` 自己鑄（不是手寫編碼），
  改編碼時它跟著改。

沒改，理由記在這裡：

- **「版本不符沒測」查證後不成立**：`{"hello":"world"}` 的 `v` 讀出來是 `0`（Jackson 3 的
  `path("v").asInt()` 對缺鍵回 0，已實測），走的就是版本比較那一行，與未來 v=2 的游標同一條路。
  要分辨「缺鍵」與「v=2」只能手寫編碼，那正是票上「不斷言內部編碼」禁止的事。測試註解已改寫清楚。
- **`nonIntegerLimitIs400WithoutACode` 不算範圍發散**：`limit` 這個參數是本票引進的，
  它的型別錯誤行為是契約凍結的（notes.md §7、openapi 的 `ListBadRequest`），一起鎖住。
- **`NotePage` 搬檔是必要的不是順手做**：envelope 現在由 service 組出來，
  留在 controller 裡會變成 service 依賴 controller。

⚠️ **兩件超出本票、留給你裁決的事（沒有動）**：

1. **`notes_author_ix (author_id, created_at desc)` 沒有 `id`**，而 keyset 的平手鍵是 id
   ⇒ 平手處要多一次排序。要不要加一支 migration 把索引改成 `(author_id, created_at desc, id desc)`，
   是超出本票的 schema 變更，等你決定（票 08 的 `notes_picker_ix` 有同樣的形狀）。
2. **測試 helper 三份**：`Traveler`／`traveler()`／`get()`／`assertProblem()`／`WIRE`
   在 `DropNoteTest` 與 `MyNotesTest` 各一份，票 08 會出現第三份。
   那時再一次搬進 `SupabaseDbTest` 最省事（現在搬要動票 05 的檔）。
