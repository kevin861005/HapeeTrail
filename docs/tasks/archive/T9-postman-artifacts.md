# T9：Postman Collection ＋ Environment

> 2026-07-12 Kevin 要求：可直接匯入 Postman 的兩個檔案。

## Checklist

- [x] 1. `docs/api/postman/trailstamp.postman_collection.json`（Collection v2.1）
      ✅ 6 endpoint、雙旅人完整流程（A 留 → B 發現並撿起 → 收藏）、
      invalid_cursor＋冪等重試示範、token 由 script 自動寫回 environment
- [x] 2. `docs/api/postman/local.postman_environment.json`
      ✅ base_url＋本地 apikey＋runtime 變數（access_token/note_id）
- [x] 3. newman 驗證 ✅ 9 requests / 9 assertions / 0 failed
- [x] 4. notes.md 補匯入指引；TASKS/HANDOFF 更新、commit、push ✅ 見本檔所在 commit
