# T11：API 契約 v3 設計（草案，未動工）

狀態：⏸️ 等 iOS 討論色票與 `style` 值域
日期：2026-07-27

前提：iOS 尚未串接，**刻意不受 v2.1 破壞性變更限制**，優先考慮未來可擴充性。
本檔是施工前的設計紀錄；動工後結論該升級成 ADR，本檔完結搬 `archive/`。

---

## 1. 目標形狀

**Note**（`drop_note` / `pickup_note` 回傳，也是列表 item）：

```json
{
  "id": "5f8f1c1e-…",
  "content": "今天的風很舒服",
  "color": 1,
  "style": 1,
  "audience": "anyone",
  "coordinate": { "latitude": 25.065472, "longitude": 121.533397 },
  "createdAt": "2026-07-23T10:00:00.123456Z",
  "pickedUpAt": null
}
```

**NearbyHint**（`color`/`style` 進來供 pin 渲染；`content` 依然不進來）：

```json
{
  "id": "5f8f1c1e-…",
  "color": 1,
  "style": 1,
  "coordinate": { "latitude": 25.06597, "longitude": 121.5334 },
  "distanceM": 30,
  "pickable": true,
  "createdAt": "2026-07-23T10:00:00.123456Z"
}
```

**列表 / nearby envelope**：`{ "items": [...], "nextCursor": "eyJ…" }`
（`nearby_notes` 無分頁，只有 `items`；裸陣列日後加欄位是破壞性的，故一律包起來）

**錯誤**：`{"code":"P0001","message":"too_far","details":"{\"distanceM\": 87}","hint":null}`

⚠️ `details` 是**內容為 JSON 的字串**，不是巢狀物件——2026-07-28 對本機 PostgREST
實測確認（`RAISE ... USING DETAIL` 被原樣當字串塞入）。client 需二次解析，失敗則忽略。

**Requests**（PostgREST 規定 body 鍵名＝參數名，故維持 `p_` snake_case）：

```json
// drop_note（p_color / p_style / p_audience 可省略，伺服器補預設）
{ "p_content": "…", "p_lat": 25.065472, "p_lng": 121.533397,
  "p_color": 1, "p_style": 1, "p_audience": "anyone" }

// my_notes / my_collection（p_cursor 省略＝第一頁）
{ "p_limit": 50, "p_cursor": "eyJ2IjoxLCJ0IjoiMjAyNi0…" }
```

---

## 2. 已定的原則（之後改很貴，所以現在定）

1. **JSON 白名單**。改掉 `to_jsonb(v_row) - 'location' - 'author_id' - …` 這個黑名單寫法，
   改用 `jsonb_build_object` 明列。黑名單讓「往後每加一個表欄位都預設上 wire」，
   註解防不住忘記；白名單讓新欄位預設隱形。順帶讓命名風格變成免費選擇。
2. **鍵名 camelCase；縮寫當普通單字**（`photoUrl`、`avatarUrl`、`distanceM`，不是 `photoURL`）。
   **enum 值一律小寫 snake_case**（與已凍結的錯誤 token 同一套，日後 `close_friends` 也一致）。
3. **回應端 `coordinate` 巢狀，請求端 `p_lat`/`p_lng` 扁平**。回應是會長大的資料物件
   （日後 `pickedUpCoordinate`、accuracy、altitude 都塞得進去，扁平會變前綴地獄）；
   請求端是函式參數，扁平才拿得到 Postgres 型別檢查。兩者不同是刻意的。
4. **不透明游標**。`p_cursor` / `nextCursor` 單一字串取代 `(p_before_*, p_before_id)` 兩欄位。
   收穫：排序鍵日後可換（距離／熱度／推薦）而不動 API 形狀；消滅 notes.md §7
   「timestamp 必須 byte-for-byte 原樣回傳」那類 client bug（沒人會想把 base64 轉 Date）；
   「兩欄位必須成對」規則連同它的 `invalid_cursor` 情境一起消失。
   `nextCursor: null` ＝ 沒有更多（不必再多打一次空頁確認）。
5. **錯誤可帶結構化資料**。`RAISE EXCEPTION 'too_far' USING DETAIL = '{"distanceM":87}'`
   → PostgREST 放進 `details`。不改 transport 就能讓 `too_far` 附距離、`content_too_long` 附上限。
   `message` 仍是唯一判斷依據；`details` 選配、可能 null，client 不得依賴其存在。
6. **任何上 wire 的使用者識別字都不是 `auth.users.id`**，一律是 profile 上另一支 public id。
   不論 creator 功能做不做，此條先成立——發出去的識別字收不回來。
7. **未知欄位忽略、未知 enum 值走 default 分支**（寫進契約）。
   這條是「之後再加就好」整套推論的前提。
8. **便條狀態一律伺服器 derive，絕不在表上落地成 `status` 欄**（ADR-0001 立場的契約層明文化）。
9. **`color`/`style` 存 `smallint`（裝置端對照表的數字代號）；`audience` 存 `text` + CHECK**。
   2026-07-28 與 iOS 達成的共識：色票與卡片樣式的對照表放裝置端，後端只存數字代號、
   不理解其語意。好處是真實的——新增顏色／樣式完全不需要後端 migration、改 openapi 或等設計。
   `audience` 不走這條路：它是**後端要理解並據以過濾**的業務語意（決定進不進 nearby），
   不是純呈現，所以維持字串 + CHECK。
   （註：`text` 型別選擇仍成立——不用 Postgres enum type，因為 enum 加值有交易限制、
   刪值幾乎不可能，而 `audience` 的賣點就是之後會加值。）
10. **數字是永久 ID，不是清單位置**——本設計唯一的硬條件。
    對照表只能追加新項，既有數字的意義永久凍結；顯示順序由 client 自行決定，
    與數字身分脫鉤，不得靠陣列位置決定意義。
    違反的後果：設計在調色盤中間插一個顏色或重排順序，**所有既有便條靜默換色**；
    而對照表在裝置端、後端看不到它變了，兩側都不會有錯誤訊號，等使用者回報時
    已無原始資料可還原。這是本設計唯一會損壞歷史資料的失效模式，其餘皆可補救。
    後端無法對此把關，只能靠紀律——故明文寫進契約文件。
11. **後端不驗證 `color`/`style` 的語意合法性**，只做型別與範圍粗檢（`smallint`、`>= 0`）。
    後端維護一份合法值清單＝養第二份對照表，一定會跟裝置端漂移，
    那會把這個設計的好處賠光。連帶接受兩件事：
    (a) client 必須對**未知數字渲染 default 樣式**（「未知值走 default」規則的數字版）；
    (b) 壞資料進得來——iOS 若有 bug 送了 `9999`，後端照收，那張便條在所有裝置上
    永遠是 default 樣式，且不會有任何錯誤訊號。可接受，但要知道。
12. **起算 1；伺服器預設值必須指向對照表裡一個具體項目**（例如 `1` ＝ 現行黃色），
    不得保留一個「代表預設」的抽象槽——否則設計改預設色時，等於第 10 條的靜默換色再犯一次。
13. **對照表必須有一份文件版**放 `docs/api/`（純文件，後端不實作、不驗證、不同步檢查）。
    沒有它則後端 debug、客服回報、任何資料分析（哪個顏色最受歡迎、哪個樣式沒人用）
    全都無從查起，也無法重建歷史對照表版本。iOS 每次追加項目時同步更新此檔。
14. **時間戳固定 `YYYY-MM-DDTHH:MM:SS.ffffffZ`**（永遠 6 位小數、永遠 `Z`）。
    手工組 JSON 後順便消掉「小數秒位數可變，解析器必須容忍」這條警告。
15. **不做 API 版本化機制**（路徑／header 版本）。有了第 7 條，可預見的未來用不到；
    真的非破壞不可時開一支新函式名即可。

---

## 3. 隨形狀而來的產品規則

- `audience`：`anyone` / `self`（`self` ＝ 只給自己的旅遊紀錄）。預設 `anyone`。
- `audience: "self"` 的便條不進**任何人**的 `nearby_notes`，只出現在 `my_notes`。
- 別人嘗試撿一張 `self` 便條 → 回 `note_not_found`，**不新增 token**
  （新 token 等於跟外人確認「這裡有一張你看不到的便條」）。
- **私人便條不計入 50 張未撿額度**（`active_note_limit`）。它們永遠不會被撿走，
  計入的話認真寫旅遊紀錄的人很快就撞牆，而那個限制本來是為了防濫用。
- `content` 維持原名（iOS 提案的 `message` 不採用，2026-07-26 拍板）。
- `color` 與 `style` 是**兩個獨立整數欄位**，不得打包成單一數字（如 `12` ＝ 色1形2）。
  打包會讓「加第三軸」與「某軸超過 9 項」雙雙變成破壞性變更。
  放 `style` 的理由不是 wire 上那個 key，而是讓 iOS 從第一天就把卡片 renderer
  寫成 (color, style) 查表，不要寫成「單一紙張外型換底色」——紙張類型是**已知會來**。

---

## 4. 暫不納入（之後加是純加欄位，非破壞）

| 欄位 | 不納入的理由 |
|---|---|
| `creator{id,displayName,avatarUrl}` | 推翻 T7。穩定 creator id ＋ 精確座標 ＋ 時間戳 ＝ 可重建個人旅遊軌跡；質變不是量變。要做需先做產品決策（匿名 vs 具名）＋ 新 ADR supersede T7，且 id 必須走第 2 節第 6 條 |
| `holder` | 在 `my_collection` 裡永遠是自己（廢話欄位）；在 `my_notes` 裡揭露會傷撿起者隱私（他只是撿了張便條，沒選擇露臉）。目前沒有讀者 |
| `photoUrl` | 是子系統不是欄位：Storage 上傳、**EXIF GPS 剝除**、公開 bucket vs 簽名 URL、UGC 圖片審查（會讓 T3 從「上架前要有」變成「有照片就必須有」）、CDN 成本。且**絕不可進 NearbyHint**（100m 外就送出 payload，50m 撿起機制失效） |
| `status` | 今天只有兩個可達值，都能從 `pickedUpAt` 推出 → 雙重真相來源。等「限時可編輯」「TTL 過期」「檢舉隱藏」第一個落地再加，且必須 derive（第 2 節第 8 條） |
| `updatedAt` | 便條目前不可變，會恆等於 `createdAt`，是死欄位 |

原則：**最好的預留就是不預留**。今天佔位只會得到死欄位還鎖死型別
（例：今天放 `photoUrl` 純量，日後要多張就是破壞性變更；今天不放，日後直接加 `media: []`）。

---

## 5. 未決

**已解除的 blocker**：色票清單與 `style` 值域原本卡在設計，2026-07-28 改為裝置端對照表
＋後端存數字代號後，後端不需要知道任何內容，**T11 不再等待任何人**。

尚未決定、但都不阻塞動工（之後加是非破壞）：

- **命名 `style` vs `paper`**：`paper` 較準（顏色已獨立成欄），且把 `style` 一詞留給日後
  「策展好的預設組合」概念。待 Kevin 拍板，目前暫用 `style`。
- **creator 匿名 vs 具名**：影響 schema，早定省事（見第 4 節）。
- **對照表文件的落點與格式**（第 2 節第 13 條）：由 iOS 決定怎麼寫最省事。

---

## 6. 動工時的影響範圍

- migration：`notes` 加 `color` / `style`（`smallint`，僅範圍粗檢）與 `audience`（`text` + CHECK）
- 5 支 RPC：改白名單組 JSON、分頁換不透明游標、錯誤加 `details`、時間戳固定格式
- `docs/api/openapi.yaml`、`docs/api/notes.md`、`docs/api/postman/` 三份同步改寫
- `supabase/tests/notes.test.sql` 跟著改（含「Note 不含 uuid 身分欄位」既有斷言）
