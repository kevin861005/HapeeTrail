# 07 — 交付驗收：對照表文件與契約一致性

**What to build:** 把 v3 契約正式交付給 iOS。補上顏色與樣式的對照表文件、確認三份契約
產出彼此沒有互相矛盾的說法、並取得兩份獨立的驗收結論——不是由實作者自己宣稱完成。

**Blocked by:** 03 — 列表的新格式與不透明游標；06 — 業務錯誤附帶 details

**Status:** done（2026-07-28，證據見文末）

- [x] 新增顏色與樣式對照表文件，放在 API 文件目錄下
- [x] 該文件**開頭**載明本設計唯一會損壞歷史資料的硬性約定：**代號是永久 ID，不是清單位置**；對照表只能追加新項，既有代號的意義永久凍結；顯示順序由 client 決定，必須與代號身分脫鉤
- [x] 該文件載明後果：若在對照表中間插入項目並讓後面順移，所有既有便條會靜默換色，且因對照表在裝置端、兩側都不會產生任何錯誤訊號，等旅人回報時已無原始資料可還原
- [x] 該文件載明此表由 iOS 維護，後端不實作、不驗證、不做同步檢查
- [x] 語意文件新增「未知值政策」一節：未知欄位忽略、未知 enum 值與未知代號一律走預設
- [x] 三份契約產出（OpenAPI 規格、語意文件、Postman collection）彼此一致，無殘留的舊格式說明
- [x] 語意文件的「契約外路徑」段補上 `as_note_wire` computed column（T11-01 引入）：`authenticated`
      可經 PostgREST 以 `notes?select=…,as_note_wire` 取得白名單化的便條形狀。RLS 仍生效（只看得到
      自己寫的或自己撿的列）、anon 不可達，未擴大讀取面；但既然該段的用途就是揭露契約以外讀得到什麼，
      它必須被列出來。若 T11-02～06 又新增類似的 computed column，一併補上
- [x] 同段一併補上 T11-03 引入的 `as_cursor(timestamptz, uuid)` 與 `parse_cursor(text)`：與 `as_wire_ts`
      同性質——因兩支 SECURITY INVOKER 列表 RPC 需要而授權給 `authenticated`，於是也成為
      `POST /rest/v1/rpc/{fn}` 呼叫得到的東西。兩支皆純字串運算、不碰任何資料（`parse_cursor` 只解自己
      手上的游標，`as_cursor` 只編自己給的輸入），未擴大任何讀取面；但同樣不能因為「沒有安全影響」
      就從揭露的縫隙掉出去
- [x] 同段一併補上 T11-02 引入的 `as_wire_ts(timestamptz)`：它不是 computed column，而是一支
      `authenticated` 可直接呼叫的 RPC（`POST /rest/v1/rpc/as_wire_ts`）。純字串格式化、不碰任何
      資料，存在的理由是兩支 SECURITY INVOKER 列表 RPC 需要它；但它同樣是「契約以外呼叫得到的
      東西」，不能因為不是 computed column 就從上一條的縫隙掉出去
- [x] 語意文件維持語言中立，不含任何 client 語言程式碼
- [x] Postman collection 依資料夾順序執行即為完整流程、token 由 script 自動帶入的既有設計維持；newman 全綠
- [x] 契約變更政策段落更新：錯誤 token 字串永久凍結、新增為非破壞性、改名或刪除為破壞性變更並須取得 iOS 簽核
- [x] 把 T11 全系列的設計結論升級成 ADR（`docs/adr/`，編號遞增），並將 `docs/tasks/T11-contract-v3-design.md`
      搬 `docs/tasks/archive/`——該設計檔開頭即註明「動工後結論該升級成 ADR」。至少涵蓋：不透明游標
      （不簽章、版本＋排序鍵雙閘門、拒絕舊游標而非靜默退化，T11-03）、色票與樣式代號放裝置端
      （T11-04）、私人便條的可見性與撿取回應（T11-05）。刻意留到此處一次處理，
      而非每張票各開一支 ADR——它們是同一個設計的不同面向
- [x] 派**獨立** subagent 做安全審查：RLS 是否可繞過、新增欄位是否經由契約外的直讀路徑外洩、參數驗證、濫用防護
- [x] 派**獨立** subagent 做正確性驗證：邊界條件、游標平手情境、兩人同時撿同一張便條的併發情境
- [x] 兩位複核者**只拿 spec、不繼承實作過程的假設**；兩份結論回報後這張票才算完成，發現的問題先回報再修

---

## 交付證據（2026-07-28）

### 產出

- 新增 `docs/api/style-codes.md` — 開頭即「代號是永久 ID，不是清單位置」硬性約定
  ＋ 靜默換色的後果 ＋ 「由 iOS 維護，後端不實作／不驗證／不同步檢查」。
- `docs/api/notes.md` — 新增 §9 未知值政策（三條規則；請求端 `p_audience` 為唯一例外，
  不認得即拒絕，判準寫成「純呈現走預設，影響可見性的拒絕」）；§10 契約外路徑擴寫；
  變更政策段落；Changelog v3.0。
- 新增 ADR-0004（不透明游標）／0005（色票與樣式代號放裝置端）／0006（私人便條）。
- `docs/tasks/T11-contract-v3-design.md` → `docs/tasks/archive/`，表頭改寫指向三支 ADR。

### 測試（乾淨資料庫，`supabase db reset` 後）

- `psql -f supabase/tests/notes.test.sql` → `ALL TESTS PASSED`
- `newman run` → 12 requests / 12 assertions / 0 failed
- `redocly lint docs/api/openapi.yaml` → 通過

### 兩份獨立複核（只拿 spec.md，未讀本票與設計草案）

**安全審查：PASS。** 六個審查軸（RLS 繞過、三個新欄位的外洩路徑與私人便條存在性、
參數驗證／`search_path`／DEFINER 越權面、防濫用閘門、不透明游標、§10 揭露完整性）
無一被攻破。以 `pg_proc` ＋ `has_function_privilege` 列舉確認 `authenticated` 可執行 9 支
＝ 5 支契約 RPC ＋ §10 列出的 4 支 helper，無漏無多；`distance_m` 正確收回（auth 403／anon 401）。
兩個 MAJOR 皆非 v3 缺陷，已回報 Kevin 待裁決（見下）。

**正確性驗證：PASS，實作層 8/8 軸全綠。** wire 形狀逐字相符（便條 8 鍵、探索提示 7 鍵，
是「只有」不是「有」）；時間戳 2001 個值零格式偏差、不受 session TimeZone／DateStyle 影響；
143 張同刻便條 × 11 種頁大小翻頁不掉列不重複、末頁 `nextCursor` 為 null；游標往返 2000 次
零失真、14 種竄改全數 `invalid_cursor`、跨列表雙向互餵被擋；併發實測（真開多連線）
10 條同搶一張 → 1 成功 / 9 `note_taken`，冪等重試在 row lock 阻塞 4 秒後仍回成功且
`pickedUpAt` 未被覆寫；13 個 token 逐一觸發，恰 4 個帶 `details` 且數字為伺服器實算
（`retryAfterS` 實測 1801 而非常數 3600）；`nearby_notes` 走 `notes_active_location_gix`。
1 MAJOR ＋ 7 MINOR 全部落在測試與文件產出物，無一為 RPC 行為錯誤。

### 兩複核發現的問題與處置

**已修（落在本票驗收範圍內）**

- §10 漏列 `POST /rest/v1/rpc/as_note_wire`（它同時是 computed column 與可直接呼叫的 RPC）。
- §10 漏列 `GET /rest/v1/` 根路徑：以 `authenticated` 呼叫會列出全部路徑與 `notes` 的 12 個
  欄位定義（實測 `anon` 拿到的是空清單，安全複核宣稱「anon 也列得出來」有誤，文件按實測寫）。
- §10 漏列 STABLE 函式另有 GET 形式：實測三支列表 RPC 與四支 helper 的 GET 皆可達
  （`drop_note`／`pickup_note` 為 VOLATILE，得 405）。
- §10 直讀欄位由「`location` 與 `author_id`/`picked_up_by` 等」改為逐字列出 12 個
  ——「等」字在揭露段落沒有意義。
- `openapi.yaml` 殘留 `supabase-swift 的 signInAnonymously()`，違反 CLAUDE.md「不放任何
  client 語言」；`notes.md` 早已中立化，openapi 漏改。
- `notes.md` 內契約產出數量出現 2／3／4 三種說法（本票驗收條要求「彼此沒有互相矛盾的說法」）。
  依 spec 統一為三份（notes.md／openapi.yaml／postman），`style-codes.md` 明列為 iOS 維護、
  不在後端同步義務內。
- `style-codes.md` 原稿替 iOS 做了實作決定（規定不得用 `palette[i]`、規定選色器行為），
  違反 CLAUDE.md；已刪除，只留契約層 invariant（代號不得回收、順序與身分脫鉤）。
- `p_limit` 越界是靜默夾擠（`least(greatest(coalesce(p_limit,50),1),100)`），但 notes.md §7
  與 openapi 都宣告 min/max 1–100、未說越界不報錯。已於兩處補上。
  **註：此項不在本票 checklist 上**，是正確性複核發現的文件與行為不符；判斷屬「交付給 iOS 的
  契約必須誠實」而修，已向 Kevin 標明可回退。

**未修，待 Kevin 裁決（新開 ticket）**

- **[MAJOR] 撿起者 uuid 經直讀路徑外洩給便條作者。** RLS 為
  `author_id = uid OR picked_up_by = uid` ＋ 表有 SELECT grant，故作者直讀自己的便條即可取得
  撿走它的人的 `auth.users.id`。2026-07-12 的結構性取捨、§10 有揭露，非 v3 引入；但 T7 移除
  uuid 的理由（帳號綁定後 uuid 連結真人、發出去收不回）對這條路徑同樣成立。
- **[MAJOR] 私人便條完全無數量閘門。** 實測單帳號連建 200 張全成功，之後仍可留滿 50 張公開
  便條。spec 明文接受此天花板，但其論證支持的是「更高的上限」而非「沒有上限」。
- **[MAJOR] Postman collection 是時間炸彈。** 每輪留兩張 committed 便條、從不清理，而
  `nearby_notes` 只回最近 20 筆；實測第 11 輪起殘留便條會把本輪目標擠出前 20 名，
  `nearby` 斷言開始失敗且看起來像後端 bug。同一根因也讓 `notes.test.sql` 在未 reset 的
  資料庫上失敗（本次交付前已 `supabase db reset` 才取得全綠證據）。
- **[MINOR] 撿取頻率閘門蓋掉冪等重試。** 閘門排在冪等診斷之前，撿滿 60 次後對自己已撿到的
  那張重試會得 `pickup_rate_limited`，與 notes.md §6「timeout 後可安心重試同一筆」不符。
- **[MINOR] Postman collection 對私人便條零覆蓋**（無 `self`、無他人撿私人便條、
  無 `invalid_audience`），而 notes.md 對 iOS 宣稱「依資料夾順序執行即為完整流程」。
- **[MINOR] `btrim` 只吃 ASCII 空白**：`E'\t\n'` 與全角空格 U+3000 可建立便條。
- **[記錄型天花板，不需動作]** `notes_author_ix` 不含 `id`，同作者大量同刻便條時 tiebreaker
  退化成 Filter；`parse_cursor` 的 `(v->>'v')::int` 會接受字串版本號 `"1"`（游標不簽章，無影響）。
