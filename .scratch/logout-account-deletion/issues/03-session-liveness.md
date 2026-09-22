# 03 — Session 存活驗證（登出立即失效）

**What to build:** 旅人按下登出（iOS 直打 Supabase `signOut()`，session 列消失）的瞬間，
該帳號所有已簽發 access token 打任何業務 API 一律 401——與現行 `not_authenticated`
**逐字相同**的凍結形狀。機制：JWT 簽章與 claims 驗過後，以 `session_id` claim 對
`auth.sessions` 做 PK lookup，查無此列即 401。適用所有需認證端點。

**Blocked by:** 01（分包）、02（research——claim 格式與 signOut 行為以 findings 為準）.

**Status:** ready-for-agent

- [ ] migration：`hapeetrail_api` 取得 `auth.sessions` 的最小必要讀取權（僅此，不多）
- [ ] 正面組：user＋session 齊備的 token → 200
- [ ] `badTokensAre401` 表新增列：①簽章有效但無對應 session 列 ②session 列被刪（模擬登出）——401 逐字斷言
- [ ] 缺 `session_id` claim 或格式不符 → 401（fail-closed，進表）
- [ ] 既有測試遷移：「建 user＋session」fixture helper 一處收掉；「不存在的使用者拿有效 token 得 200」的舊案例翻成 401 表列；`./mvnw test` 全綠且綠的理由正確
- [ ] 隱私：新增查詢與 401 路徑不落任何 token 內容或 claim 值進日誌
- [ ] **ADR-0013 落檔**：「驗 JWT」擴充為「驗 JWT＋session 存活」——立即失效 vs 無狀態的取捨、每請求一次 PK lookup 的代價、刻意不做快取的理由
- [ ] TDD red→green：每條新斷言先紅後綠
