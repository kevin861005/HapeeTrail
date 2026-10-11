# T33 Spec — 2026-10-11 與 WeiWei 會議的三個契約決策

來源：Kevin 2026-10-11 轉述會議決策。契約 **v4.2.0 → v4.3.0**。刪鍵在契約政策上是破壞性變更，
但 WeiWei 尚未串接且會議當場同意（§11 要明寫「經 2026-10-11 會議同意」）。

## 決策 1：更新檢查只回兩個門檻，判定交給 App
- `GET /v1/app/update`：**不再收 `version` 參數**（沒有東西要比了），回應只剩
  `{ "minimumVersion": "1.0.0", "latestVersion": "1.0.0" }`。仍不需認證、仍忽略 Authorization。
- 刪掉 Java 的 `VERSION` regex、`compare()`、`parts()`、`forceUpdate`／`updateAvailable`；這支不再有 400。
  （`App 端`自行比較：低於 minimum 強制、低於 latest 提示——寫進契約文件當建議，不是伺服器規則。）
- DB 表 `app_versions` 與 CHECK 不變。
- `AppTest`：刪 `verdictFollowsTheTwoGates`、`versionsCompareNumericallyNotLexically`、
  `forcedImpliesAvailableEvenWhenGatesAreMisordered`、`malformedVersionIs400WithoutACode`；
  改成一支「回應恰好兩鍵、值來自表」＋ 免登入那支保留（path 不帶 query）。

## 決策 2：功能開關預設一筆 `paywall = true`
- 新 migration `supabase/migrations/20261011000000_meeting_1011.sql`：
  `insert into hapeetrail_private.feature_flags (key, enabled) values ('paywall', true);`
- `AppTest` 加一條：不佈置任何資料、問 `paywall` → true（證明 migration 種了）。
- 契約文件：§7b 寫「目前已定義的 key：`paywall`（付費牆開關）」——key 清單原本說不進契約，改為
  列「已定義的 key」一節（非破壞性，之後新增 key 只加一行）。Postman 的開關 request 改問 `paywall` 並斷言 true。

## 決策 3：基本資料先不要頭像
- `GET /v1/me` 回 **5 鍵**：`id`、`nickname`、`loginMethod`、`lastLoginAt`、`memberLevel`（鍵序固定）。
- 同一支 migration：`drop view hapeetrail_private.auth_users; create view …`（不含 avatar 四欄，其餘不變）
  ＋ `grant select … to hapeetrail_api` 重新授權（drop 會把 grant 一起丟掉）＋ comment。
- `AccountController`：`PROFILE` SQL 去掉 avatar 的 coalesce；`Profile` record 去掉 `avatarUrl`。
- `ProfileTest`：五個測試改期望值（去 `avatarUrl`）；`userMetadataOutranksTheIdentity` 只驗 nickname。
- `SmokeTest` 不用改（物件名不變）。

## 契約三檔（v4.3.0）
- `openapi.yaml`：`info.version` 4.3.0；`/v1/app/update` 刪 `parameters` 與 `"400"`，description 改寫（App 端比較的建議）；
  `UpdateCheck` schema 只剩兩個 string、required 同步；`Profile` 刪 `avatarUrl`（required 同步）；
  `/v1/me` description 去掉頭像句；`/v1/app/flags` description 加「已定義 key：paywall」。
- `notes.md`：共同約定那句 query 參數改回只有 `limit`／`cursor`；§1 Profile 範例與條列去頭像（5 鍵）；
  §7b 更新檢查改寫（沒有參數、兩鍵、App 端比較建議：低於 minimum 擋、低於 latest 提示）、
  功能開關加「已定義的 key」小節；§11 新增 v4.3.0 條目（明寫三項、破壞性、經 2026-10-11 會議同意）。
- Postman：「基本資料」斷言 5 鍵；「更新檢查」request 不帶 query、斷言兩鍵皆為版本字串；
  **刪掉**「version 格式不合 → 400」request；「功能開關」request 問 `["paywall","nobody.set.this"]`，
  斷言 `paywall === true`、未知 false。
- `check-contract.py` exit 0（Profile／UpdateCheck 鍵名由它比對）；redocly lint valid（原本就有的 1 個 warning 可留）。
- `CONTEXT.md`：「基本資料」去掉頭像；「更新檢查」改為「伺服器只提供最低支援版與最新版，比較在 App」。

## 不在範圍
- 看板卡片、會議／決策 issue、流程圖 artifact：由主 session 處理。
- hosted `db push` 與 Cloud Run 部署：主 session／Kevin。

## 驗收
`cd api && ./mvnw test` 全綠（要 Docker）；`python3 docs/api/check-contract.py` exit 0；
`cd docs/api && npx --yes @redocly/cli@latest lint openapi.yaml` valid。
