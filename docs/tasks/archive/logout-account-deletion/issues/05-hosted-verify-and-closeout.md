# 05 — Hosted 實證＋獨立複核收尾

**What to build:** 對真 hosted 環境證明整條功能在真 GoTrue 上成立，並依專案
「驗證不自驗」守則完成兩視角獨立複核，T28 收尾。

**Blocked by:** 04.

**Status:** done ✅ 2026-09-23

- [x] hosted-smoke 延伸：真 hosted 全流程——匿名註冊 → 留便條 → 註銷 → 舊 token 401
      ＋便條從探索與收藏消失（⑦）→ `signOut()` 後舊 token 立即 401（⑧）
      ✅ `supabase/tests/hosted-smoke.sh`（commit `f124c51`）；hosted 對 Cloud Run
      **47/47 全綠**（revision 00003；Kevin 先跑一輪 47/47，腳本修完我再跑一輪 47/47）。
      **「重登入」拿掉**（Kevin 2026-09-22 裁決）：匿名帳號沒有憑證、refresh token 隨 session
      cascade，登出後同一個帳號回不來；可逆的登出要等 T25 綁定帳號。
      **實跑抓到兩個腳本缺陷，一併修掉**：①服務位址改必填（預設值停在 T26 換址前的 fly.dev，
      漏帶參數整片 000）②401 基準值加驗證（服務連不上時基準與實測同為「000＋空 body」⇒ 假綠，
      第一輪真的發生過）。突變驗證：錯的 secret key ⇒ ⑦ 恰 5 條轉紅；服務連不上 ⇒ 兩條 401 轉紅
- [x] newman 對容器（連 hosted Supabase）迴歸：既有斷言 0 失敗
      ✅ 3 輪（`npx newman run … -n 3`，匿名註冊 9 次）：**57 斷言、0 失敗**，平均 101ms。
      ⚠️ 票 04 起 collection 每輪匿名註冊 **3 次**（新增旅人 C 註銷示範，Kevin 2026-09-22 裁決保留）。
      與 hosted-smoke 同一小時跑時，newman 輪數 ≤ (30 − smoke 的註冊次數) ÷ 3。
      跑完 environment 的 `access_token` 是 C 那張已失效的 token
- [x] 兩個獨立 subagent 複核，**只給 spec 與 ADR，禁讀施工票與實作過程假設**：
      ①安全——session 檢查可否繞過（偽 claim 形狀）、`DELETE /v1/me` 可否刪到別人、
      admin 金鑰是否可能進日誌／回應；②正確性——邊界、併發（兩請求同時註銷同帳號）、
      cascade 完整性。發現問題**先回報，經同意再修**
      ✅ 兩份都「可上線」。**安全**：session 檢查繞不過（各種畸形 claim 逐一推過）、`DELETE /v1/me`
      刪不到別人（id 只來自 sub，且在 controller 前就驗過 UUID 形式）、金鑰不進日誌／回應／actuator，
      也不隨 3xx 轉址外送。**正確性**：210 測試綠且綠得有理由；併發以鎖釘住實測——同時兩個註銷
      皆 204、註銷中的寫入與撿取只有 200/401 且回滾、撿藏者收藏不留空殼；cascade 後 SET NULL
      的便條不會重新露出。
      **發現四項，Kevin 2026-09-23 裁決全修**（commit `f95b7f2`）：M1 service_role 對 notes 的
      權限（ADR-0014＋migration，hosted 已收回）、L1 GoTrue 逾時＋契約 500 措辭（v4.1.1）、
      L2 測試底座補 RLS、L3／L4 補測試（多裝置登出、重登入、真實時序 FK→401）。
      複核當時未見票 05 的煙霧測試延伸（他們拿到的是 `ac00d33`），其點名的 hosted 實證即 ⑦⑧。
      US9（identities 一併刪除）在 hosted 用匿名帳號驗不到——匿名帳號本來就沒有 identities 列，
      留給 T25 綁定帳號後驗證
- [x] 部署環境新增的環境變數已實際設定並驗證（服務重啟後註銷在真環境可用）
      ✅ Dashboard secret key `hapeetrail_api_admin` → Secret Manager `gotrue-secret-key`
      （含 compute SA 的 secretAccessor binding）→ Cloud Run revision 00003 流量 100%，
      hosted-smoke ⑦ 的 204 就是「gateway 認這把 apikey」的實證（Kevin 2026-09-22 執行）。
      ⚠️ 含 L1 逾時的 revision **尚未部署**（部署指令被權限分類器擋下，待 Kevin 執行；
      migration 已 push，與程式先後無關）
- [x] TASKS 收尾三動作：T28 打勾附證據、新發現任務登記 T 號、spec 與 issues 搬 `docs/tasks/archive/`
