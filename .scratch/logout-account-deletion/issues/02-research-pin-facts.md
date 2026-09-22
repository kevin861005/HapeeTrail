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

**Status:** ready-for-agent

- [ ] 三題各有明確結論＋原始碼或官方文件出處（不憑記憶）
- [ ] findings 檔落 `docs/research/`，日期命名比照既有慣例
- [ ] 結論若推翻 spec 任何決策（例如 signOut 不刪列、session 檢查不可行），**先回報討論，不逕行改方向**
- [ ] 票 03／04 所需的具體值（claim 名稱、端點路徑、header 名）在 findings 中可直接引用
