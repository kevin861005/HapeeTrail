---
name: App 版本
about: 每出一版開一張（TestFlight 或送審時建），這張卡就是這版的紀錄：更新資訊、最低支援版、發版 checklist
title: "v"
labels: 版本
projects: kevin861005/1
---

<!-- 側欄請填：日期（發布日）、最低支援版、Milestone；Status：In Progress＝開發中／審核中，Done＝已上架 -->

## 更新資訊
<!-- 寫給使用者與審核看得懂的話，會貼到 App Store「此版本的新功能」 -->
-

## 這版的內容
<!-- 連結看板卡片 #N -->
-

## 發版 checklist
- [ ] WeiWei：build 的 `CFBundleShortVersionString` ＝ 本卡版本號（純數字點分隔），送 TestFlight／審核
- [ ] 兩人：側欄填「日期」「最低支援版」；要強制舊版更新時把最低支援版提高（只能往上、不能超過本版）
- [ ] Kevin：Supabase SQL editor 同步門檻——`update hapeetrail_private.app_versions set latest_version = '<本版>', minimum_version = '<最低支援版>' where platform = 'ios';`
- [ ] Kevin：驗更新檢查（不需 token）——`curl "https://hapeetrail-api-134868178961.asia-northeast1.run.app/v1/app/update?version=<上一版>"` 應回 `updateAvailable: true`
- [ ] 上架後把 Status 改 Done
