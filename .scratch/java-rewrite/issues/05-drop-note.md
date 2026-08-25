# 05 — 留便條 `POST /v1/notes`

**What to build:** 旅人依當前位置留下便條，拿回 trim 後的正規 Note（9 鍵、六位小數時間戳、`expiresAt` 推導）；所有 v3.3 的驗證規則在 Java 裡逐條成立：Unicode 空白 trim、500 code point、代號範圍粗檢、`audience` 不認得就拒絕、未撿公開便條 50 張與旅遊紀錄 5000 張兩個互不影響的上限。留下的便條立刻出現在 `GET /v1/me/notes`。

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] 請求 body：`content`、`coordinate{latitude,longitude}`、`color?`、`style?`、`audience?`；座標越界 → 400 `invalid_coordinates`
- [ ] trim 字元集與契約逐字相同：Unicode `White_Space` ＋ U+001C–U+001F；**不用 `Character.isWhitespace`**（缺 NBSP／U+2007／U+202F）；格式字元（U+200B–200D、U+2060、U+FEFF、U+180E）不剝、不判空——測試涵蓋 `notes.test.sql` 的 35 個碼位代表樣本，`　你好\n　` 存成 `你好`、多行中間縮排保留
- [ ] 先 trim 再以 code point 計數：空 → 400 `content_empty`；500 恰好合法；501 → 400 `content_too_long` 附 `details.maxChars: 500`
- [ ] `color`／`style` 省略或 null → 1；1–32767 外 → 400 `invalid_style_code`；範圍內任何值原樣存、原樣回（超出對照表照收）；兩者互不干擾
- [ ] `audience` 省略或 null → `anyone`；其他非法值 → 400 `invalid_audience`
- [ ] 未撿、未過期的公開便條第 51 張 → 422 `active_note_limit` 附 `maxActiveNotes: 50`；旅遊紀錄第 5001 張 → 422 `private_note_limit` 附 `maxPrivateNotes: 5000`；兩閘門互不影響（公開滿了仍可記錄旅程，反之亦然）；計數與 INSERT 同一交易
- [ ] 回傳 Note 恰 9 鍵（`id content color style audience coordinate createdAt expiresAt pickedUpAt`），無任何 uuid 身分欄位；`expiresAt` 公開便條＝`createdAt`＋90 天、旅遊紀錄為 null；時間戳固定六位小數＋`Z`（明確格式化，不靠預設序列化）
- [ ] 座標存成 WGS-84 geography；回傳座標等於送出座標
- [ ] 型別／格式錯誤（非法 JSON、`latitude` 給字串、缺 `content`）→ 400 problem+json **沒有 `code`**
- [ ] 留下後 `GET /v1/me/notes` 的 items 含這張且順序新→舊（分頁細節在 06）
- [ ] 其餘 token 的錯誤回應**不含 `details`**（每個既有呼叫點順便守住這條）
- [ ] 全程紅→綠；測試座標每次隨機、各使用者以整數度隔開
