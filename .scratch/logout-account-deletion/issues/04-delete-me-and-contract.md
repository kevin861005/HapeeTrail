# 04 — `DELETE /v1/me` 註銷帳號＋契約 v4.1.0

**What to build:** 旅人呼叫 `DELETE /v1/me` 註銷帳號：服務經 GoTrue Admin API 硬刪使用者
（`should_soft_delete=false`），FK cascade 帶走 sessions／identities／該旅人**寫下的全部便條**
（含已被他人撿進收藏的）；他撿過的別人便條不動。成功 204 無 body；註銷後舊 token 立即 401
（session 已亡，票 03 的檢查生效）；重試天然 401。匿名與已綁定帳號同樣適用。
契約三檔同步 bump v4.1.0。

**Blocked by:** 03（session 存活——「註銷後 401」與端點自身的認證都靠它）.

**Status:** done ✅ 2026-09-22（hosted 實證在票 05）

- [x] Admin base URL 為設定值（本功能唯一新 seam）；金鑰種類依票 02 findings，走環境變數，
      不進 repo；部署環境變數清單與服務 README 同步
      ✅ `hapeetrail.gotrue.url` 預設 `${hapeetrail.jwt.issuer}`（hosted 上兩者同值，零新部署變數；
      覆寫用 `HAPEETRAIL_GOTRUE_URL`）；金鑰 `HAPEETRAIL_GOTRUE_SECRET_KEY`＝`sb_secret_…`，無預設 ⇒ 缺了啟動失敗。
      同步：`application.properties` 清單、`api/README.md` 表格／docker run／fly secrets、
      `.scratch/t26-cloudrun/runbook.md`（Secret Manager `gotrue-secret-key`＋`--set-secrets`）
- [x] GoTrue 呼叫用 Spring 內建 `RestClient`，零新依賴
      ✅ `api/…/account/AccountController.java`；`pom.xml` 未動
- [x] 測試設施 fake GoTrue（JDK 內建 http server）：收到 admin delete 即對測試 DB 真刪該使用者，
      cascade 真的跑；同時斷言送出的請求形狀（user id、認證 header、soft_delete=false）
      ✅ `FakeGoTrue.java`（成功 200 `{}`、查無此人 404 `user_not_found`，照研究 Q2.4；可換回應／掛斷）；
      `theAdminCallHardDeletesTheCallerWithTheSecretKeyAlone`：路徑＝`/auth/v1/admin/users/<sub>`、
      `apikey`＝secret key、**無 Authorization**（不轉送旅人 Bearer）、body `should_soft_delete` 是 boolean false
- [x] tracer bullet 全程斷言：`DELETE /v1/me` → 204 → 舊 token 打業務 API 401 →
      作者便條從 nearby 與撿藏者收藏消失 → 該旅人撿過的別人便條原封不動（留在原作者紀錄、不回地圖）
      ✅ `deletingMyAccountTakesEverythingIWroteAndNothingElse`（另斷言：重試 401 逐字、且不再打 GoTrue；
      旅遊紀錄也 cascade）。實作前紅：`expected: 204 but was: 404`
- [x] 冪等窗口：GoTrue 回「使用者不存在」→ 服務回 204；GoTrue 其他錯誤 → 500 problem+json（既有 catch-all，不發明新 token）
      ✅ 只認 404 **且** JSON body `error_code=user_not_found`（研究修正 #4；看 body 不看 header）。
      `anyOtherGoTrueAnswerIs500AndTheAccountSurvives` 5 列（404 validation_failed、404 text/plain、401、403、500）
      皆 500 逐字＋帳號仍可用。**突變驗證**：放寬成只看 404 → 恰 2 紅（兩種 404）
- [x] 隱私：註銷路徑日誌只有路徑／狀態碼／耗時；admin 金鑰不可能出現在任何日誌層級
      ✅ `theSecretKeyReachesNoLogAtAnyLevel`：`org.springframework.web`／`.http` 開 TRACE，成功＋失敗兩路
      output 無金鑰（探針：Spring client 在 DEBUG 印 body、不印 header）。**突變驗證**：金鑰塞進例外訊息 → 紅。
      複核後補：`anUnreachableGoTrueIs500WithoutTheUserIdInTheLogs`——連不上 GoTrue 時 `ResourceAccessException`
      的訊息帶完整 admin URL（含 user id），改為換掉外層只留 I/O 根因；實作前紅（output 含 UUID）
- [x] 契約 v4.1.0 三檔同步：`DELETE /v1/me` 一節附 curl、401 語意補「session 已終止」、
      「登出走 Supabase、本服務無登出端點」澄清句；語言中立；contract check 與 openapi lint 通過
      ✅ openapi `4.1.0`（`/v1/me` delete、tag `account`、`Unauthorized` 補 session 已終止、info 澄清＋邊界）；
      notes.md §1 改「Session 與帳號」加登出（curl＋scope＋邊界）與註銷兩節、§2、§10、§11 changelog；
      postman 新資料夾「2 旅人 C（註銷）」（C 登入即註銷＋重試 401，A／B 不動）。
      `check-contract.py` ✅ 三份一致；`redocly lint` valid（唯一警告為既有的 health 缺 4xx）
- [x] TDD red→green；`./mvnw test` 全綠
      ✅ 每張新斷言先紅後綠（見上）；`./mvnw test` → **Tests run: 210, Failures: 0, Errors: 0／BUILD SUCCESS**（票 03 後為 200）

## 施工結果（兩軸 `/code-review` 後，2026-09-22）

**Kevin 裁決修的兩組**（其餘判斷題未修）：
1. **契約文字**：openapi info 與 changelog 的「登出後立即 401」補上邊界（研究影響 #7）；
   「重試得到 204 或 401 都是已註銷」**是錯的**（第一次沒送到＋token 剛好過期時 401 只是過期）——
   改為「401 照 §2 走刷新，刷新也失敗才代表已註銷」；README 補 `HAPEETRAIL_GOTRUE_URL` 覆寫。
2. **日誌不帶 user id**（見隱私那格）。

**Postman 旅人 C**：Kevin 裁決保留。每輪匿名註冊 2→3 次；票 05 已註記輪數預算。

**未修的判斷題**（留紀錄）：GoTrue 呼叫沒有 read timeout（`ponytail:` 註解以 Cloud Run 300s 封頂，
**上 Fly（T23）前要重看**）；測試座標用 `double[]` 而非 `record Site`；失敗訊息讀 `X-Sb-Error-Code`
header（hosted 上可能是 null，只影響日誌可讀性）；`SmokeTest.java:73`「契約沒有刪除路徑」字面過時
（原意＝服務不能 DELETE notes，仍成立）；測試 helper 各類別重複（既有慣例）。

⚠️ **部署順序不變**：票 03 的 migration 先 `db push`，再部署含票 03＋04 的服務；部署前 Dashboard 建
secret key 並設好 `HAPEETRAIL_GOTRUE_SECRET_KEY`，**否則服務起不來**。
