# 02 — Research 釘死三事實

**What to build:** 依專案「主動查證」守則，動手前以原始碼／官方文件釘死三件事，
findings 落檔 `docs/research/`（比照 T25 研究檔模式，附來源與版本）：

1. **`signOut()` 與 session**：supabase-swift `signOut()` 預設 scope（global／local／others）；
   GoTrue 端 logout 是否確實**刪除** `auth.sessions` 對應列（或另有標記機制）；
   access token 內 `session_id` claim 的確切名稱與格式。
2. **GoTrue Admin 刪除**：端點形狀（路徑、方法）、認證 header 要求、
   `should_soft_delete` 參數的實際行為、使用者不存在時的回應碼。
3. **金鑰制度**：service_role JWT 與新制 `sb_secret_*` secret key 何者當前正確、
   對 Admin API 的相容性、Supabase 的棄用時程。

**Blocked by:** None — can start immediately（與票 01 平行）.

**Status:** done ✅ 2026-09-22（結論兩處推翻／修正 spec，**待 Kevin 裁決**，見下）

- [x] 三題各有明確結論＋原始碼或官方文件出處（不憑記憶）
      ✅ `docs/research/2026-09-22-supabase-logout-session-admin-delete.md`：GoTrue `v2.194.0`（＝hosted）、supabase-swift `v2.55.2`、官方文件 mdx 原始檔；
      另以本機 GoTrue＋postgres 容器探針實測（未打 hosted）。另加 Q4「`auth.sessions` 讀取可行性」
      （本票第三條驗收點名的「session 檢查不可行」風險）。
      主責 agent 之外抽驗 5 處引文逐字相符：`sessions.go` L359/364/369 三支 DELETE、
      `admin.go` loadUser 非 UUID→404 `validation_failed`／成功 `sendJSON(200, {})`、
      GoTrue migration `grant select on sessions to postgres with grant option`、
      `demote-postgres.sql` `GRANT ALL ON SCHEMA auth TO postgres`（無 grant option）、
      swift `signOut(scope: SignOutScope = .global)`
- [x] findings 檔落 `docs/research/`，日期命名比照既有慣例
      ✅ `docs/research/2026-09-22-supabase-logout-session-admin-delete.md`
- [x] 結論若推翻 spec 任何決策，**先回報討論，不逕行改方向**
      ✅ spec 與票 03／04 **未改**；已回報 Kevin，裁決項如下
- [x] 票 03／04 所需的具體值在 findings 中可直接引用
      ✅ findings 末「票 03／04 可直接引用的具體值」表

## 待 Kevin 裁決（研究檔「對本專案 T28 的影響」一節）

1. **❌ 推翻**：單一 `GRANT SELECT ON auth.sessions` 不可行——`postgres` 給不出 `USAGE ON SCHEMA auth`，
   且只印 WARNING、migration 照綠，執行期才 `permission denied`。改用私有 schema 的
   **(A) view** 或 **(B) SECURITY DEFINER 函式**（兩者本機探針皆通過）。另：Testcontainers 映像沒有
   `auth.sessions`（GoTrue migration 才建），票 03 測試底座要補。
2. **⚠️ 修正**：冪等只認 404＋`user_not_found`；非 UUID 與 base URL 錯誤也是 404，只看狀態碼會把設定錯誤
   靜默當成功。
3. **⚠️ 修正**：Admin 硬刪成功是 **200 `{}`** 不是 204（票 04 的 fake GoTrue 照此）。
4. **⚠️ 產品面**：匿名旅人登出＝永久回不到帳號（官方文件明文）；User Story 2、3 只對已綁定帳號成立。
5. **定奪**：金鑰用 `sb_secret_*`（只放 `apikey` header）；spec「service_role key」「fly secrets」字樣待改。
