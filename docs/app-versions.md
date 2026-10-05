# HapeeTrail App 版本

App 版本的唯一對照表：目前線上是哪一版、最低支援到哪一版、每一版改了什麼。
伺服器的更新檢查（`GET /v1/app/update`）讀的是資料庫裡的兩個門檻，**發版時要把這張表的數字同步過去**（見最下方流程）。
看板上的版本規劃（Milestone）管「還差什麼」；這裡管「已經出了什麼」。

## 目前狀態

| 項目 | 值 | 說明 |
|---|---|---|
| 最新版（latestVersion） | 1.0.0 | 開發中，尚未送審 |
| 最低支援版（minimumVersion） | 1.0.0 | 低於這個版本 App 會被要求強制更新 |
| App Store 狀態 | 未上架 | 送審前清單見看板 #21 |
| 門檻最後同步日 | 2026-10-05 | migration 初始值，尚未依真實版本調整 |

伺服器端的真實值以資料庫為準，查法（Supabase SQL editor）：

```sql
select platform, minimum_version, latest_version from hapeetrail_private.app_versions;
```

## 版本歷史

新的在上面。「更新資訊」寫給使用者與審核看得懂的話；技術細節放對應的看板卡片。

| 版本 | 日期 | 最低支援 | 狀態 | 更新資訊 |
|---|---|---|---|---|
| 1.0.0 | — | 1.0.0 | 開發中 | 首版：依位置留下便條、發現附近 100m 的便條、走近 50m 撿起；訪客模式與 Google／Apple 綁定；註銷帳號 |

## 發版流程（每一版都走一次）

1. **WeiWei**：打 build，`CFBundleShortVersionString` 填版本號（純數字、點分隔，例如 `1.1.0`），送 TestFlight／審核。
2. **兩人**：在上面的「版本歷史」加一列，更新「目前狀態」的最新版；需要強制舊版更新時一併提高最低支援版。
3. **Kevin**：Supabase SQL editor 同步門檻（這一步生效後 App 才會收到 `updateAvailable`／`forceUpdate`）：

   ```sql
   update hapeetrail_private.app_versions
      set latest_version = '1.1.0', minimum_version = '1.0.0'
    where platform = 'ios';
   ```

4. **Kevin**：驗一次（不需要 token）：

   ```bash
   curl "https://hapeetrail-api-134868178961.asia-northeast1.run.app/v1/app/update?version=1.0.0"
   # → {"forceUpdate":false,"updateAvailable":true,"minimumVersion":"1.0.0","latestVersion":"1.1.0"}
   ```

規則提醒：最低支援版只能往上調、不能超過最新版；版本號 1–4 段純數字，`v1.1`、`1.1-beta` 存不進資料庫（有 CHECK）。
契約細節見 [`api/notes.md` §7b](api/notes.md)。
