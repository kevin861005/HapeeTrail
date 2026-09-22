# 04 — `DELETE /v1/me` 註銷帳號＋契約 v4.1.0

**What to build:** 旅人呼叫 `DELETE /v1/me` 註銷帳號：服務經 GoTrue Admin API 硬刪使用者
（`should_soft_delete=false`），FK cascade 帶走 sessions／identities／該旅人**寫下的全部便條**
（含已被他人撿進收藏的）；他撿過的別人便條不動。成功 204 無 body；註銷後舊 token 立即 401
（session 已亡，票 03 的檢查生效）；重試天然 401。匿名與已綁定帳號同樣適用。
契約三檔同步 bump v4.1.0。

**Blocked by:** 03（session 存活——「註銷後 401」與端點自身的認證都靠它）.

**Status:** ready-for-agent

- [ ] Admin base URL 為設定值（本功能唯一新 seam）；金鑰種類依票 02 findings，走環境變數，
      不進 repo；部署環境變數清單與服務 README 同步
- [ ] GoTrue 呼叫用 Spring 內建 `RestClient`，零新依賴
- [ ] 測試設施 fake GoTrue（JDK 內建 http server）：收到 admin delete 即對測試 DB 真刪該使用者，
      cascade 真的跑；同時斷言送出的請求形狀（user id、認證 header、soft_delete=false）
- [ ] tracer bullet 全程斷言：`DELETE /v1/me` → 204 → 舊 token 打業務 API 401 →
      作者便條從 nearby 與撿藏者收藏消失 → 該旅人撿過的別人便條原封不動（留在原作者紀錄、不回地圖）
- [ ] 冪等窗口：GoTrue 回「使用者不存在」→ 服務回 204；GoTrue 其他錯誤 → 500 problem+json（既有 catch-all，不發明新 token）
- [ ] 隱私：註銷路徑日誌只有路徑／狀態碼／耗時；admin 金鑰不可能出現在任何日誌層級
- [ ] 契約 v4.1.0 三檔同步：`DELETE /v1/me` 一節附 curl、401 語意補「session 已終止」、
      「登出走 Supabase、本服務無登出端點」澄清句；語言中立；contract check 與 openapi lint 通過
- [ ] TDD red→green；`./mvnw test` 全綠
