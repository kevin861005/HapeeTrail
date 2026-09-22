# 05 — Hosted 實證＋獨立複核收尾

**What to build:** 對真 hosted 環境證明整條功能在真 GoTrue 上成立，並依專案
「驗證不自驗」守則完成兩視角獨立複核，T28 收尾。

**Blocked by:** 04.

**Status:** ready-for-agent

- [ ] hosted-smoke 延伸：真 hosted 全流程——匿名註冊 → 留便條 → `signOut()` 後舊 token 401
      （登出立即失效實證）→ 重登入 → 註銷 → 舊 token 401 ＋ 便條從探索消失。
      注意匿名註冊 30 次/時/IP 額度預算（與 newman 同小時共用）
- [ ] newman 對容器（連 hosted Supabase）迴歸：既有斷言 0 失敗
- [ ] 兩個獨立 subagent 複核，**只給 spec 與 ADR，禁讀施工票與實作過程假設**：
      ①安全——session 檢查可否繞過（偽 claim 形狀）、`DELETE /v1/me` 可否刪到別人、
      admin 金鑰是否可能進日誌／回應；②正確性——邊界、併發（兩請求同時註銷同帳號）、
      cascade 完整性。發現問題**先回報，經同意再修**
- [ ] 部署環境新增的環境變數已實際設定並驗證（服務重啟後註銷在真環境可用）
- [ ] TASKS 收尾三動作：T28 打勾附證據、新發現任務登記 T 號、spec 與 issues 搬 `docs/tasks/archive/`
