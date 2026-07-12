# T8：契約文件語言中立化

> 2026-07-12 Kevin 裁決：後端契約文件不放 client 語言程式碼——後端無法編譯驗證 Swift、
> 也不該替 iOS 做實作決定。完結後本檔搬 archive。

## Checklist

- [x] 1. 改寫 `docs/api/notes.md` ✅ v2.2：wire 層語言＋curl，Swift 全數移除
- [x] 2. `CLAUDE.md` 協作分工改「附 curl 範例、語言中立」✅
- [x] 3. 驗證 ✅ grep 乾淨；獨立 subagent 逐條核對 26 項契約規則零遺失、與 openapi/SQL
      一致，verdict PASS；兩個編輯疵已補（distance_m 勿自行重算回補、openapi 升版 2.1.0）
- [x] 4. TASKS/changelog 更新、commit、push ✅ 見本檔所在 commit
