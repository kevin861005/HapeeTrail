# Trailstamp 任務清單

> 唯一任務追蹤器。打勾必附證據（commit / file:line / 指令輸出）。

## 進行中

（無）

## 待辦
- [ ] **T13** 私人便條無數量閘門（安全複核 MAJOR）——實測單帳號連建 200 張全成功，
  之後仍可留滿 50 張公開便條。spec 明文接受此天花板，但其論證支持的是「更高的上限」
  而非「沒有上限」；補一個寬鬆的絕對上限即可關掉
- [ ] **T16** `btrim` 只吃 ASCII 空白（正確性複核 MINOR）——`E'\t\n'` 與全角空格 U+3000
  可建立便條，只有半角空白被 `content_empty` 擋。spec 未規定，但 CJK 使用者按到全角空白不罕見
- [ ] ⏸️ **T2** 部署到 hosted Supabase 專案（`supabase link` + `db push`；先確認 PostGIS 在 `extensions` schema）
  ——等什麼：進入大量測試階段（2026-07-12 決定：開發期以本機 supabase 為主）
- [ ] **T3** UGC 檢舉機制（App Store 審查前必須；`report_note` RPC + 隱藏 flag）
- [ ] **T4** 便條 TTL 政策（產品決策；技術上為 pg_cron 一句 delete）

## 已完成

（30 天內；更舊直接刪，git 歷史即檔案）

- [x] **T12** 撿起者 uuid 經直讀路徑外洩給便條作者（安全複核 MAJOR）
  ✅ 2026-07-28：`supabase/migrations/20260728080000_close_direct_read.sql`；
  兩支列表 RPC 改 SECURITY DEFINER（各只動 3 行，機械 diff 確認函式邏輯零改動），
  然後 `revoke all on table public.notes from anon, authenticated`，並收回四支 helper
  對 client 角色的執行權——它們被授權的唯一理由就是「兩支 INVOKER 列表需要」，理由已消失。
  決策與取捨升級為 **ADR-0007**（推翻 spec「兩支列表維持 INVOKER」那一條）。
  效果：`GET /rest/v1/notes` 任何變體 403、helper 以正確簽名呼叫 403、根路徑只剩五支契約 RPC
  且 `notes` 欄位定義清空；**契約零變更**（參數／回傳／錯誤 token 全同，iOS 不必改）。
  獨立安全審查 **PASS**：偽造 10 年後的合法游標餵給無便條的使用者仍回 0 筆、`p_limit` 極端值
  全被夾住、JWT claims 五種變形全 fail-closed、竄改 search_path 無效、service_role/postgres 未受影響。
  `ALL TESTS PASSED`、newman 15/15、redocly lint 通過。
  code-review 抓到三項並已修：缺 ADR、不該跳版號（wire 零變更卻讓 notes.md 跳 v3.1 而 openapi
  停在 3.0.0）、**§10 寫的 404 是錯的事實**（我原本對 helper 一律送 `{}`，那是 PGRST202 找不到
  簽名；用正確簽名重測全部是 403 / 42501）。
  留下的已知缺口寫進 ADR 與 migration：RLS policy 保留但休眠且無測試覆蓋（重開表權限前須補測）；
  Supabase 的 default privileges 會讓日後 drop＋create 的函式靜默重新被授權——本次收回的每一支
  都有 `permission denied` 正面斷言守著，但新增的**表**沒有同等覆蓋
- [x] **T18** 測試座標改成每次隨機（關掉 T17 留下的天花板）
  ✅ 2026-07-28：`supabase/tests/notes.test.sql`；新增 `pg_temp.tlat(位移)`／`pg_temp.tlng()`，
  **所有會建立便條的座標一律經此取得**，各使用者的便條群以整數度緯度隔開
  （0 主群／2 私人 F／4 樣式 E／6 上限 C／8 頻率 D；1 度 ≈ 111km ≫ 100m 探索半徑）
  ——群組互不可見成為不變式而非機率。位移只動緯度，每度公尺數才不隨經度改變。
  red：舊固定點灌 34 張未撿便條 → `FAIL: expected 2 nearby rows, got 1`（獨立複核另做對照組，
  灌 500 張，寫死座標版的失敗訊息與 ticket 預測逐字相同）；
  green：同樣污染下 6 輪全過、隨機緯度 15 輪 0 失敗、強制 lat = -60/-45/0/10/27.5/55 全過。
  緯度帶 -60..55 全掃：30m→29.90-30.12、70m→69.76-70.29（round 恆為 70，斷言容許 60-80）、
  130m→129.56-130.54（距「不可見」門檻餘裕 29.6m）、277m→276.4-278.5，無任何緯度會翻掉斷言。
  **突變測試 6/6 全殺**（audience／author／picked_up_at／半徑／limit／pickable 半徑）。
  code-review 抓到並修掉一個真的會咬人的殘留：E 的樣式便條仍寫死 `(10,10)` 且建立於 nearby
  斷言**之前**，基準點若落在那 100m 內就重現原病（複核者實測 lat=10 確實失敗）——
  C/D 的固定座標則因為建立於最後一次 nearby 之後而無害，但同樣改掉，免得日後有人在後面
  加一條 nearby 斷言就靜默重現。另修：`ratelimit %` 的 id 查詢補作者條件、被拒路徑的座標
  統一改成無意義的 `0,0`、`current_setting` 直接內嵌由 28 處降為 2 處（helper 自身）
- [x] **T17** `notes.test.sql` 對外來資料硬化（nearby 斷言不再綁死東京固定點）
  ✅ 2026-07-28：`supabase/tests/notes.test.sql`；新增 `pg_temp.ours()` 把探索結果濾成
  本測試建立的便條（作者屬 `pg_temp.fixture_users()`，該清單同時餵給 `insert into auth.users`，
  只有一處），三處 nearby 斷言改成先濾再斷言；另兩處（上限 20 筆、撿走的便條消失）本來就
  對外來資料免疫，刻意不濾。順帶修掉兩個抓 id 的純量子查詢沒有作者條件的問題——外來使用者
  留下同內容便條會炸成 `more than one row returned by a subquery`，同一類誤導性失敗（已實測重現）。
  red：照 notes.md §4 的 curl 敲一次 → `FAIL: private note leaked into nearby`（私人便條根本沒漏）；
  green：5 張外來便條（含兩張與測試同內容、一張同座標私人）下仍 `ALL TESTS PASSED`，乾淨庫亦通過。
  **突變測試證明沒變成空測**：拿掉 `audience` 過濾／`author_id` 過濾／`picked_up_at is null`、
  半徑放大 1000 倍，四個 mutant 在有污染的資料庫上全部被抓到（獨立複核另下了 limit 放大的第五個，
  同樣被抓）。code-review 抓到並修掉一個實質缺陷：我新寫的 `jsonb_typeof(...) <> 'array'` 是**死的**
  ——鍵不存在時回 SQL NULL，`if` 不觸發，整段會靜默全綠（已實測確認，補 `is null` 那半）。
  留下一個已量化的天花板，登記為 T18
- [x] **T15** 撿取頻率閘門蓋掉冪等重試（正確性複核 MINOR）
  ✅ 2026-07-28：`supabase/migrations/20260728070000_idempotent_retry_under_rate_limit.sql`；
  閘門跳起來時先問一句「這張是不是已經是你的」，是就照常回成功——冪等重試不新增任何撿取，
  本來就不該計入防濫用額度。函式本體對前一版只多 **4 行**（機械 diff 確認其餘 63 行 byte-for-byte
  相同），新查詢只在閘門真的跳起時才跑、走 PK，happy path 零成本。
  red：`pickup_rate_limited`；green：回傳原便條且 `pickedUpAt` 未被改寫。
  測試把第一次撿取回撥 17 分鐘才驗得出「沒被改寫」（單一交易內 now() 恆定，不回撥兩者長得一樣），
  順帶讓 `retryAfterS` 從恆定的 3600 變成算出來的 2580；**突變測試證明斷言是實心的**
  （換成會改寫的版本 → `FAIL: 冪等重試改寫了 pickedUpAt: was 11:26:04, got 11:43:04`）。
  斷言完會還原時間戳，否則下方「D 的收藏 60 筆全部同刻」的前提會被悄悄弄壞。
  `ALL TESTS PASSED`、newman 15/15、redocly lint 通過。契約文件不動——notes.md §6 本來就是
  無條件承諾，是實作沒跟上文件。code-review 兩軸 0 項硬性違規；Standards 軸另否決了一個
  看似更乾淨的修法（讓閘門 fall through 到診斷段共用冪等分支會使 `too_far` 在限流下照樣回傳，
  等於把距離探測 oracle 開在閘門後面，拆掉 ADR-0003 要擋的座標掃描）
- [x] **T14** Postman collection 累積 committed 資料導致假性失敗（正確性複核 MAJOR）
  ✅ 2026-07-28：`docs/api/postman/`——每輪由「匿名登入（A）」的 Pre-request script 隨機挑地點，
  位移一律只動緯度（每度公尺數不隨經度改變）；API 沒有刪除便條的路徑，所以只能靠換地點。
  併修的私人便條覆蓋：`self` 建立、nearby 不出現、他人撿回 `note_not_found`、`invalid_audience`
  ——斷言 12 → 15。
  red：舊版乾淨資料庫連跑，**第 20 輪**斷在「A 的便條應出現在 nearby: expected undefined to exist」。
  green：新版連跑 30 輪 0 失敗，資料庫累積 90 筆殘留後不 reset 直跑 SQL 套件仍 `ALL TESTS PASSED`。
  **原描述兩處算錯已更正**：不是「每輪留兩張、第 11 輪起」——note A 每輪都被撿走、退出 partial
  index，真正累積的是每輪 **1 張**未撿便條，距探索點 21m（非 19m），滿 20 張才把目標擠出前 20 名。
  code-review 兩軸的 3 項已修（`details` 斷言凍結了非破壞性變更、環境變數缺 description、
  空字串內插產生非法 JSON）；獨立複核另抓到一項不在本票範圍的缺陷，登記為 T17
- [x] **T11** API 契約 v3（`color`/`style`/`audience`、不透明游標、錯誤 details、JSON 白名單）
  ✅ 2026-07-28：8 張 ticket 全數完成（`.scratch/api-contract-v3/`，spec ＋ issues 01→08 各附證據）；
  6 支 migration（`20260728000000`～`20260728060000`）；契約三份產出同步改寫
  ＋ 新增 `docs/api/style-codes.md`；設計結論升級為 ADR-0004／0005／0006，
  設計草案搬 `docs/tasks/archive/T11-contract-v3-design.md`。
  交付驗收（07）：乾淨資料庫下 `ALL TESTS PASSED`、newman 12/12、redocly lint 通過；
  兩份獨立複核**皆 PASS**——安全審查六軸無一被攻破（含以 `pg_proc` ＋ 權限查詢確認
  `authenticated` 可執行的 9 支函式與 §10 揭露逐一相符），正確性驗證實作層 8/8 全綠
  （含 10 連線同搶一張 → 1 成功 / 9 `note_taken`、143 張同刻便條 × 11 種頁大小翻頁不掉列）。
  兩複核 ＋ code-review 兩軸共修掉 8 項文件缺陷，未修者已登記為 T12–T16（證據見 ticket 07 文末）
  - 01 白名單建構 ✅ 2026-07-28：`supabase/migrations/20260728000000_whitelist_json.sql`；
    既有測試套件未改仍 `ALL TESTS PASSED`、newman 9/9、暫時欄位探針證明不外洩（證據見 ticket 01）
  - 02 wire 格式 v3 ✅ 2026-07-28：`supabase/migrations/20260728010000_wire_format_v3.sql`；
    camelCase＋巢狀 coordinate＋固定六位小數時間戳＋nearby envelope；`ALL TESTS PASSED`、
    newman 9/9、redocly lint 通過、EXPLAIN 索引未退化（證據見 ticket 02）
  - 03 不透明游標 ✅ 2026-07-28：`supabase/migrations/20260728020000_opaque_cursor.sql`；
    兩支列表改 `{items, nextCursor}` envelope、單一 `p_cursor` 取代 `p_before_*`＋`p_before_id`；
    `ALL TESTS PASSED`、newman 11/11（含游標 HTTP 往返）、redocly lint 通過、
    EXPLAIN 掃描節點與變更前逐字相同。code-review 抓到並修掉一個實質缺陷：
    游標未帶排序鍵身分 ⇒ 跨列表混用會靜默回錯頁（證據與修正見 ticket 03）
  - 04 樣式代號 ✅ 2026-07-28：`supabase/migrations/20260728030000_style_codes.sql`；
    `color`/`style` 兩個獨立 smallint（預設 1、可省略、超出對照表照收）、新增
    `invalid_style_code` token（實作前取得同意）；`ALL TESTS PASSED`、newman 11/11、
    redocly lint 通過、EXPLAIN 仍走 `notes_active_location_gix`；獨立 subagent 安全與
    正確性複核無 CRITICAL/MAJOR（含併發實測），code-review 的 4 項已修（證據見 ticket 04）
  - 05 私人便條 ✅ 2026-07-28：`supabase/migrations/20260728040000_private_notes.sql`；
    `audience`（`anyone`／`self`，text＋CHECK）——不進任何人的探索、他人撿取回 `note_not_found`、
    不佔也不受未撿便條上限；新增 `invalid_audience` token（實作前取得同意——此欄位後端必須
    理解，靜默走預設等於把私密便條變公開）；`ALL TESTS PASSED`、newman 11/11、redocly lint 通過、
    EXPLAIN 同資料前後對比計畫形狀不變且候選列 143→120。code-review 的 4 項已修
    （constraint 命名、OpenAPI default 位置、Postman 參數、註解裸引用）（證據見 ticket 05）
  - 06 錯誤 details ✅ 2026-07-28：`supabase/migrations/20260728050000_error_details.sql`；
    四種業務錯誤附帶伺服器當下算出的真實數字（`too_far` 附實際距離、`content_too_long` 附
    `maxChars`、`active_note_limit` 附 `maxActiveNotes`、`pickup_rate_limited` 附 `retryAfterS`），
    其餘為 null——測試 helper 的預設值讓 20+ 個既有呼叫點順便守住這條；token 與 transport 未動。
    `ALL TESTS PASSED`、newman 12/12（含 `details` 為字串而非物件的 wire 斷言）、redocly lint 通過；
    頻率閘門改寫後 EXPLAIN 由 Bitmap Heap Scan 3599 列降為 Index Only Scan 60 列。
    code-review 的 4 項已修（證據見 ticket 06）
  - 08 距離算法單一真相 ✅ 2026-07-28：`supabase/migrations/20260728060000_distance_helper.sql`；
    06 回報的重複經同意後收斂為 `public.distance_m()`，**不授權給任何 client 角色**
    （HTTP 實測 authenticated 403／anon 401 ⇒ 不必進 07 的契約外路徑揭露）；純重構、
    既有斷言未改仍 `ALL TESTS PASSED`、newman 12/12、EXPLAIN 索引與變更前逐字相同。
    留下一個已量化的天花板：空 `search_path` 使 planner 無法 inline，每候選列多約 1.3–1.7µs
    （MVP 規模約 0.1ms）——升級路徑寫在 migration 的 `ponytail:` 註解（證據見 ticket 08）
  - 07 交付驗收 ✅ 2026-07-28：`docs/api/style-codes.md`（新）、`notes.md` §9 未知值政策
    ＋ §10 契約外路徑完整揭露、ADR-0004～0006、設計檔歸檔（證據見 ticket 07 文末）

- [x] **T10** openapi servers 補 Tailscale 位址（夥伴 Swagger UI「Try it out」用）
  ✅ 2026-07-12：mac-mini 遠端實測 Swagger UI 200＋CORS `*` 確認；lint 通過
- [x] **T9** Postman Collection＋Environment（docs/api/postman/）
  ✅ 2026-07-12：newman 實跑 9 requests / 9 assertions / 0 failed；
  紀錄見 `docs/tasks/archive/T9-postman-artifacts.md`
- [x] **T8** 契約文件語言中立化（notes.md 去 Swift、CLAUDE.md 規則同步改）
  ✅ 2026-07-12：notes.md v2.2；subagent 核對 26 條契約規則零遺失 PASS；
  紀錄見 `docs/tasks/archive/T8-language-neutral-contract.md`
- [x] **T6** API 契約 v2：OpenAPI 化＋cursor 分頁＋EXPLAIN 索引驗證
  ✅ 2026-07-12：`docs/api/openapi.yaml`（lint 通過）；`my_notes`/`my_collection` keyset RPC；
  EXPLAIN 證據與 4 個 MINOR 發現處置見 `docs/tasks/archive/T6-api-contract-v2.md`；測試全綠
- [x] **T7** Note payload 去 uuid（author_id/picked_up_by 不上 wire）
  ✅ 2026-07-12：panel 4-0 裁定提前執行（帳號綁定後 uuid 回溯連結真人、發出的資料收不回）；
  wire 實測 6 鍵；詳見 T6 checklist F4 段
- [x] **T5** 推上 GitHub（kevin861005/trailstamp）＋ CLAUDE.md 新增「開發守則」章節
  ✅ 2026-07-12：CLAUDE.md §開發守則；remote origin 設定與 push（證據見 git log）
- [x] **T1** 第一階段便條後端端到端：schema + RPC/RLS + docs/api/notes.md
  ✅ 2026-07-12：migration `supabase/migrations/20260712000000_notes.sql`（`supabase db reset` 套用成功）；
  `supabase/tests/notes.test.sql` 全綠（輸出 `ALL TESTS PASSED`，重跑 grep ERROR|FAIL = 0）；
  契約 `docs/api/notes.md`；ADR-0001~0003；經獨立 subagent 複核
  （初審 FAIL 抓到測試腳本 3 個 RLS 空測 bug，修正後全綠）
