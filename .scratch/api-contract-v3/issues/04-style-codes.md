# 04 — 便條樣式代號（color / style）

**What to build:** 旅人留下便條時可以指定顏色與便條紙樣式，讓便條能表達心情、在地圖上
有辨識度；撿到便條的人看到的是原作者選的樣式。代號隨便條物件與地圖 pin 一起回傳。

**後端只儲存代號、不理解其語意**——實際的顏色與樣式對照表放在裝置端，因此新增顏色或
樣式完全不需要後端 migration 或發版。代價是後端無法驗證代號是否有意義，這是刻意的取捨。

**Blocked by:** 02 — 便條與探索提示的新 wire 格式

**Status:** done — `supabase/migrations/20260728030000_style_codes.sql`（2026-07-28）

- [x] 便條資料模型新增 `color` 與 `style` 兩個**獨立**的小整數欄位，既有列以預設值回填
      ✅ `alter table public.notes add column color smallint not null default 1, add column style smallint not null default 1`；
      既有列由 column default 回填為 1/1
- [x] 兩個欄位各自獨立，**不得打包成單一數字**
      ✅ 兩個欄位、兩個 constraint、兩個參數，全程未合併；測試以 `(7, 3)` 這種不對稱值施測，
      互換或打包都會紅
- [x] 代號從 1 起算；伺服器預設值指向對照表中一個具體項目（現行黃色 ＝ 1）
      ✅ `check (color >= 1)` / `check (style >= 1)`；預設值 1 ＝ 黃色本身，非抽象的「預設槽」
- [x] 留下便條時兩個參數皆可省略，省略時由伺服器補預設
      ✅ `p_color integer default null, p_style integer default null` ＋ `coalesce(…, 1)`；
      HTTP 實測：兩個都不給 → `color 1 / style 1`；只給 `p_style:4` → `color 1 / style 4`；
      顯式 null 亦走預設
- [x] 兩個代號出現在便條物件與探索提示中（地圖 pin 需要它們才能渲染成對應樣式）
      ✅ `as_note_wire`（唯一建構處，五支 RPC 共用）與 `nearby_notes` 的提示建構處各加兩個鍵；
      精確鍵集斷言：Note 7 鍵、Hint 7 鍵
- [x] **只做型別與範圍粗檢，不驗證語意**：超出裝置端對照表範圍的代號被接受、原樣儲存、原樣回傳
      ✅ `(999, 32767)` 原樣往返，無任何錯誤訊號；後端未維護任何合法值清單。
      **範圍**粗檢由 `drop_note` 擋下（`not between 1 and 32767` → `invalid_style_code`），
      **型別**錯誤（`1.5`、`3000000000`）沿用既有模式落在 PostgREST 型別閘門（`22P02`/`22003`），
      與 `p_lat` 給字串同一路徑——此分界已寫進 notes.md §3
- [x] SQL seam 斷言涵蓋：預設值正確、參數可省略、超範圍代號被接受、兩個欄位互不干擾
      ✅ 新增使用者 E（便條刻意遠離東京基準點，不干擾既有 nearby 計數）：
      `(7,3)` 往返、只給 `color=9` 另一欄補 1、`(999,32767)` 照收、`0`／`-1`／`32768` 三例
      皆 `invalid_style_code`；另在 nearby（預設 1/1 與非預設 7/3 各一張）與 my_notes 斷言
      代號走完整條路徑。`ALL TESTS PASSED`
- [x] OpenAPI 規格與語意文件同步更新，並明載「client 遇到未知代號一律渲染預設樣式」
      ✅ `openapi.yaml`：新增 `StyleCode` schema（該句寫在其 description）、Note／NearbyHint
      各加兩欄並列入 required、drop_note 加兩個可省略參數、`ApiError` enum 加
      `invalid_style_code`；redocly lint 通過。`notes.md`：§3 兩個形狀更新 ＋ 代號規則段、
      §4 參數說明、§8 錯誤表加一列、§10 changelog

**新增的錯誤 token（實作前取得同意）：** `invalid_style_code`。票上只寫「只做型別與範圍
粗檢」而未指定粗檢失敗回什麼；若只靠 table CHECK，client 會收到 `23514`，依契約 §2
被導向「session 刷新／通用重試」——對一個永久性的 client bug 是錯的指引，且會洩漏約束名。
新增 token 依契約政策為非破壞性變更。

**額外驗證（獨立 subagent，只拿票與 spec、不繼承實作假設）：**

- 安全審查：**無 CRITICAL／MAJOR**。函式 ACL 僅 `postgres`／`authenticated`（anon 呼叫 → `42501`）、
  `drop_note` 仍 `prosecdef = t` ＋ 空 `search_path`；`authenticated` 對 notes 仍只有 SELECT
  （直接 `update … set color` → `permission denied`）；`GET /rest/v1/notes` 多出的兩欄本來就上 wire，
  computed column `as_note_wire` 回傳的仍是白名單 7 鍵；`"1; drop table notes"` 被型別閘門擋下；
  兩個新參數不觸及 50 張／60 次的計數 SQL，無濫用放大面
- 正確性驗證：邊界 `1`/`32767` 接受、`32768`/`0`/`-1` → `invalid_style_code`、
  `1.5`/`true`/`[1]`/`"abc"` → `22P02`、`2147483648` → `22003`，**無任何 500**；
  併發實測（兩個 psql session 同撿一張）仍恰好一位勝出、輸家 `note_taken`，且撿到的是
  作者選的代號；既有行為（獨佔、冪等重試、游標分頁、上限）未破壞
- EXPLAIN（5.02 萬列 ＋ ANALYZE，以函式 owner 身分——`nearby_notes` 是 SECURITY DEFINER）：
  `Index Scan using notes_active_location_gix`，`Index Cond: location && _st_expand(…, '100')`，
  未因多帶兩欄而退化
- newman 11/11；seam B 只留 wire 層才看得到的斷言（鍵集、代號在 wire 上是數字而非字串），
  值的往返不重複 seam A

**code-review 後的修正：** ①`nearby_notes` 內層沿用自 T11-02 的註解「形狀與舊版逐字相同
⇒ 查詢計畫不變」已失真（多了兩欄、`order by 4` 的位置引用會變成依 color 排序，故改為
引用別名 `order by distance_m`）——註解改寫並記下 EXPLAIN 已複驗。②`StyleCode` 的
`default: 1` 移到請求參數上：回應欄位已在 required，default 會讓 codegen 誤解為可省略。
③notes.md 原本寫「代號是永久 ID、不是清單位置」整段——那是 **ticket 07** 指派給對照表
文件開頭的內容，提早複製會多養一份要同步的副本，已移除留給 07。
④錯誤表原本承諾「須為 1–32767 的整數」但型別錯誤其實不走此 token，已改寫為兩層分界。

**未處理（判斷為不值得）：** 合法範圍出現在四處（table CHECK、smallint 上界、RPC 粗檢、
OpenAPI），要收斂成單一真相需引入 domain 型別，但 RPC 那道仍得自己擋才拋得出 token，
省不掉；`p_color` 不改成 `numeric` 以便把 `1.5` 也納入 token——那會讓這兩個參數變成
全 API 唯一不吃 PostgREST 型別閘門的特例。
