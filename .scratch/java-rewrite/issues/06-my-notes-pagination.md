# 06 — 我的便條分頁與游標

**What to build:** 旅人翻閱自己留過的所有便條（公開與旅遊紀錄，含已被撿走與已過期的），每頁最多 100、預設 50，用不透明游標翻頁，翻到底 `nextCursor` 為 null；拿錯游標、竄改、亂字串一律 `invalid_cursor` 大聲失敗。游標機制在此建成，08 的收藏列表直接沿用。

**Blocked by:** 05

**Status:** ready-for-agent

- [ ] `GET /v1/me/notes?limit=&cursor=`：`limit` 省略 50、越界靜默夾到 1–100（0／負數 → 1、101 以上 → 100）
- [ ] 排序 `createdAt` 新→舊，以（`createdAt`, `id`）平手；keyset 多取一筆決定 `nextCursor`
- [ ] 游標：base64 JSON，含版本、所屬列表、排序鍵、id；不簽章不加密；由 Java 編解碼（與 SQL 版不相容，無需相容）
- [ ] 29 張以每頁 10 走完：不重複、不遺漏、(createdAt,id) 嚴格遞減；頁大小恰等於總數時 `nextCursor` 為 null
- [ ] 全部同刻的便條（測試內固定時鐘或直接寫入同一 `created_at`）翻頁無重疊——複合游標的平手邏輯壓力測試
- [ ] 無法解碼、被竄改、版本不符、**屬於其他列表**的游標 → 400 `invalid_cursor`；只斷言外部行為，不斷言內部編碼
- [ ] 跨使用者隔離正面斷言：B 有 29 張、A 一張沒有 → A 的 my_notes 為 `{items:[],nextCursor:null}`
- [ ] 已被撿走的（`pickedUpAt` 非 null）與已過期的都仍在列表裡（過期不消失，ADR-0010）
- [ ] 全程紅→綠
