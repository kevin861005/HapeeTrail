# 07 — 探索 `POST /v1/notes/nearby`

**What to build:** 旅人送出當前位置，拿到 100m 內、非自己的、未撿走、未過期、`audience` 為 `anyone` 的便條 pin（最多 20、最近優先），每個 pin 帶伺服器算的整數距離與 `pickable`（50m 內）快照，刻意不含內容與作者。距離與半徑全部在 SQL 用 PostGIS 算，Java 不算。

**Blocked by:** 05

**Status:** ready-for-agent

- [ ] 請求 body `coordinate`；越界 → 400 `invalid_coordinates`；回 `{items:[...]}`（無分頁，空為空陣列）
- [ ] NearbyHint 恰 7 鍵（`id color style coordinate distanceM pickable createdAt`），無 content、無任何身分欄位
- [ ] 30m 的便條：出現、`distanceM≈30`、`pickable=true`；70m：出現、`pickable=false`；130m：不出現——緯度帶 -60..55 隨機基準點皆成立
- [ ] 排除呼叫者自己的便條、任何人的旅遊紀錄（同點一公開一私人 → 只看到公開那張，不用「斷言為空」的寫法）、已撿走的、已過期的（90 天邊界在 09 總驗，此處至少一條 91 天不出現）
- [ ] 同點 25 張 → 恰 20 筆、最近優先
- [ ] 代號非預設值一路走到 wire（例如 color 7／style 3 原樣出現）
- [ ] 距離判定與 `distanceM` 只來自 SQL 的 geography 運算；Java 程式碼中沒有任何距離公式
- [ ] EXPLAIN checklist：探索語句的 WHERE 與 partial index 述詞逐字一致、計畫走 `notes_active_location_gix`；只記計畫形狀，不寫毫秒數
- [ ] 全程紅→綠
