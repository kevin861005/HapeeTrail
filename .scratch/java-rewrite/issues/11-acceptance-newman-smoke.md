# 11 — 驗收：newman 對 Fly 全綠 ＋ 煙霧測試改打服務

**What to build:** 交付給 iOS 的 Postman collection 對著測試環境完整跑通整條流程（旅人 A 留便條與旅遊紀錄、翻頁、竄改游標；旅人 B 探索、撿別人的旅遊紀錄失敗、距離不足附距離、撿起、冪等重試、收藏）全綠；煙霧測試一分鐘內驗完「換一台機器才會壞」的事。這是 ADR-0011 定義的驗收：同一份契約語意在真機全綠。

**Blocked by:** 02, 09, 10

**Status:** ready-for-agent

- [ ] newman 對 Fly 上的服務：15+ 斷言全綠、連跑 30 輪 0 失敗（每輪隨機地點，T14 手法）
- [ ] collection 斷言看 `status` 與 `code`、`details` 為物件；`details` 斷言不凍結非破壞性變更
- [ ] hosted 煙霧測試改寫：①匿名登入（仍打 Supabase）②五支端點可達且形狀正確（Note 9 鍵、trim、代號原樣、`expiresAt`）③資料庫 locale 支援 Unicode 空白（全形空白 → `content_empty`）④anon／無 token 對服務全部 401 ⑤`/actuator/health` 200；用 client 憑證跑，不用任何管理金鑰
- [ ] 煙霧測試暫時保留 RPC 版的斷言（切換前 RPC 仍在）；切換後的斷言在 13 改
- [ ] 語意文件 §10「契約外路徑」與實測一致
- [ ] 三份契約產出交叉比對：token 清單、狀態碼、鍵名三方逐字一致
- [ ] 通知夥伴測試環境可用（HANDOFF 記錄）
