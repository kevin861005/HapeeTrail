# 05 — 留便條 `POST /v1/notes`

**What to build:** 旅人依當前位置留下便條，拿回 trim 後的正規 Note（9 鍵、六位小數時間戳、`expiresAt` 推導）；所有 v3.3 的驗證規則在 Java 裡逐條成立：Unicode 空白 trim、500 code point、代號範圍粗檢、`audience` 不認得就拒絕、未撿公開便條 50 張與旅遊紀錄 5000 張兩個互不影響的上限。留下的便條立刻出現在 `GET /v1/me/notes`。

**Blocked by:** 04

**Status:** done（2026-08-25，`mvn test` 100/100 綠）

- [x] 請求 body：`content`、`coordinate{latitude,longitude}`、`color?`、`style?`、`audience?`；座標越界 → 400 `invalid_coordinates`
- [x] trim 字元集與契約逐字相同：Unicode `White_Space` ＋ U+001C–U+001F；**不用 `Character.isWhitespace`**（缺 NBSP／U+2007／U+202F）；格式字元（U+200B–200D、U+2060、U+FEFF、U+180E）不剝、不判空——測試涵蓋 `notes.test.sql` 的 35 個碼位代表樣本，`　你好\n　` 存成 `你好`、多行中間縮排保留
- [x] 先 trim 再以 code point 計數：空 → 400 `content_empty`；500 恰好合法；501 → 400 `content_too_long` 附 `details.maxChars: 500`
- [x] `color`／`style` 省略或 null → 1；1–32767 外 → 400 `invalid_style_code`；範圍內任何值原樣存、原樣回（超出對照表照收）；兩者互不干擾
- [x] `audience` 省略或 null → `anyone`；其他非法值 → 400 `invalid_audience`
- [x] 未撿、未過期的公開便條第 51 張 → 422 `active_note_limit` 附 `maxActiveNotes: 50`；旅遊紀錄第 5001 張 → 422 `private_note_limit` 附 `maxPrivateNotes: 5000`；兩閘門互不影響（公開滿了仍可記錄旅程，反之亦然）；計數與 INSERT 同一交易
- [x] 回傳 Note 恰 9 鍵（`id content color style audience coordinate createdAt expiresAt pickedUpAt`），無任何 uuid 身分欄位；`expiresAt` 公開便條＝`createdAt`＋90 天、旅遊紀錄為 null；時間戳固定六位小數＋`Z`（明確格式化，不靠預設序列化）
- [x] 座標存成 WGS-84 geography；回傳座標等於送出座標
- [x] 型別／格式錯誤（非法 JSON、`latitude` 給字串、缺 `content`）→ 400 problem+json **沒有 `code`**
- [x] 留下後 `GET /v1/me/notes` 的 items 含這張且順序新→舊（分頁細節在 06）
- [x] 其餘 token 的錯誤回應**不含 `details`**（每個既有呼叫點順便守住這條）
- [x] 全程紅→綠；測試座標每次隨機、各使用者以整數度隔開

---

## 施工紀錄（2026-08-25）

**產出**：`api/src/main/java/com/kevin/hapeetrail/` 的 `Note.java`（wire 三個 record）、
`NoteService.java`（全部業務規則＋SQL）、`ApiErrors.java`（problem+json 信封）、
`JsonConfig.java`（Jackson 嚴格純量）、`NotesController.java`（＋`POST /v1/notes`）；
測試 `api/src/test/java/com/kevin/hapeetrail/DropNoteTest.java`（89 條）。
全套 `./mvnw test` **100/100 綠**（DropNoteTest 89、SmokeTest 4、AuthTest 7）。

**兩個實作決定（複核提出，逐一裁決）**：

1. **缺必填欄位全部歸「型別／格式錯誤」**（400 無 `code`），包含 `coordinate` 只給一半、
   `latitude: null`、`content: null`。依據是 openapi 的 `required: [content, coordinate]` 與
   `Coordinate.required: [latitude, longitude]`，以及本票第 17 行把「缺 `content`」歸在無 code 那一類。
   `invalid_coordinates` 因此**只**代表「值在、但越界」——與本票第 1 行的「座標越界」逐字一致。
   代價：v3.3 的 `p_lng is null → invalid_coordinates` 這條分支在 v4 打不到（transport 層先擋掉）。
2. **額度閘門的過期界線用 DB 的 `now()`**（`now() - make_interval(secs => 7776000)`），
   不是 Java 算好的時刻。`created_at` 與 `expiresAt` 都出自資料庫時鐘，界線也必須，
   否則 Fly.io 與 Supabase 的時鐘偏移會讓「第 50 張」的邊界飄掉。秒數仍由同一個 Java
   `TTL` 常數推導，沒有第二份。

**複核抓到的真 bug（已修）**：`{"color": 1.5}` 原本被 Jackson 靜默截成 `1` 並回 200，
違反 notes.md §3「非整數（`1.5`）或超出 32 位元整數的值屬**型別**錯誤」。
修法：`JsonConfig` 對 `LogicalType.Integer` 加上 `CoercionInputShape.Float → Fail`。

**突變測試（證明斷言實心）**：
- `\p{IsWhite_Space}` 換成 `\s` → 30 條中 24 條紅（NBSP／U+3000／U+2007／U+202F 等）。
- `codePointCount` 換成 `length()` → 500 個星群平面字元那條紅。
- 拿掉 `CoercionInputShape.Float → Fail` → 「color 給小數」「style 給小數」兩條紅。

**刻意不做（留給後面的票）**：
- `GET /v1/me/notes` 仍整份撈、無 `limit`／游標 → **票 06**。本票只把回傳從 id 字串升成
  9 鍵 Note（為了驗「含這張」，且 wire 形狀本來就是本票的事）。
- 上限的**併發超越量**未量化 → **票 09**（`超越量數字寫進 ticket` 是那張的驗收條件）。
- **未攔截例外沒有 problem+json catch-all**：notes.md §2 說「所有錯誤都是 problem+json」，
  但 openapi 沒有定義任何 500 的形狀，現在補等於自創未文件化的回應。**建議另開 ticket**
  （決定 500 的契約形狀 → 同時補 openapi 與 handler）。
