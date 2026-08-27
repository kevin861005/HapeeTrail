# 07 — 探索 `POST /v1/notes/nearby`

**What to build:** 旅人送出當前位置，拿到 100m 內、非自己的、未撿走、未過期、`audience` 為 `anyone` 的便條 pin（最多 20、最近優先），每個 pin 帶伺服器算的整數距離與 `pickable`（50m 內）快照，刻意不含內容與作者。距離與半徑全部在 SQL 用 PostGIS 算，Java 不算。

**Blocked by:** 05

**Status:** done

- [x] 請求 body `coordinate`；越界 → 400 `invalid_coordinates`；回 `{items:[...]}`（無分頁，空為空陣列）
- [x] NearbyHint 恰 7 鍵（`id color style coordinate distanceM pickable createdAt`），無 content、無任何身分欄位
- [x] 30m 的便條：出現、`distanceM≈30`、`pickable=true`；70m：出現、`pickable=false`；130m：不出現——緯度帶 -60..55 隨機基準點皆成立
- [x] 排除呼叫者自己的便條、任何人的旅遊紀錄（同點一公開一私人 → 只看到公開那張，不用「斷言為空」的寫法）、已撿走的、已過期的（90 天邊界在 09 總驗，此處至少一條 91 天不出現）
- [x] 同點 25 張 → 恰 20 筆、最近優先
- [x] 代號非預設值一路走到 wire（例如 color 7／style 3 原樣出現）
- [x] 距離判定與 `distanceM` 只來自 SQL 的 geography 運算；Java 程式碼中沒有任何距離公式
- [x] EXPLAIN checklist：探索語句的 WHERE 與 partial index 述詞逐字一致、計畫走 `notes_active_location_gix`；只記計畫形狀，不寫毫秒數
- [x] 全程紅→綠

---

## 施工紀錄（2026-08-25，全部完成）

實作：`NoteService.nearby` ＋ `NEARBY` 一句 SQL、`NearbyRequest`／`NearbyHint`／`NearbyResult`
（`Note.java`）、`POST /v1/notes/nearby`（`NotesController`）。座標驗證抽成 `located()`，
留便條與探索共用同一個閘門（越界 → `invalid_coordinates`；缺欄位 → 400 無 `code`）。

測試：`api/src/test/java/com/kevin/hapeetrail/NearbyTest.java`，14 支全綠
（先紅：13 支中 12 支 404，只有「要 token」那支綠）。全套 128 支綠。

### EXPLAIN checklist（一次性，2026-08-25；10 萬張便條散在全球＋探測點附近 40 張，ANALYZE 後）

查詢的 `where` 前兩條與 `notes_active_location_gix` 的述詞逐字一致
（索引：`where picked_up_at is null and audience = 'anyone'`）：

```
Limit
  ->  Result
        ->  Sort
              Sort Key: ((round(st_distance(location, '…'::geography, true)))::integer)
              ->  Index Scan using notes_active_location_gix on notes n
                    Index Cond: (location && _st_expand('…'::geography, '100'::double precision))
                    Filter: ((author_id <> '…'::uuid)
                             AND (created_at > (now() - '2160:00:00'::interval))
                             AND st_dwithin(location, '…'::geography, '100'::double precision, true))
```

計畫形狀（**刻意不記毫秒數**，理由同 `20260729010000_note_ttl.sql` 檔頭）：

- 走 `notes_active_location_gix`，不是 Seq Scan。
- `picked_up_at is null` 與 `audience = 'anyone'` **不出現在 Filter**——它們被索引述詞吸收了，
  這就是「逐字一致」的證據；改動任一邊而不改另一邊，它們會掉回 Filter，計畫形狀立刻不同。
- TTL 的 `created_at` 條件落在 Filter（`now()` 不是 immutable，進不了索引述詞），與 RPC 版相同。

### 過程中的發現（未修，僅記錄）

Supabase 的 Postgres 映像設了 `extra_float_digits = 0`：float8 以**文字**回傳時截到 15 位
有效數字（DBL_DIG），約 1e-13 度 ≈ 0.01nm，遠在任何地理意義之下。pgjdbc 的連線在語句被
重複執行、升級成 binary 傳輸後不受影響，所以同一個值可能因執行次數而在第 16 位不同。
本票的座標斷言因此用 `within(1e-9)` 而非完全相等；`DropNoteTest` 既有的完全相等斷言目前
仍綠（走 binary 那條路），列為已知的潛在 flake，不在本票範圍內。

### code-review 兩軸（2026-08-25）

**已修（都在本票自己的程式碼裡）**

1. **`atMostTwentyHintsNearestFirst` 原本是機率性測試**（Spec 軸）：只放一張 90m 的便條時，
   「漏掉 `order by`」的實作有約兩成機率仍然綠。改成兩群各 25 張（0m／90m）、斷言回來的
   20 筆 `distanceM` 全為 0。**突變驗證**：把 `NEARBY` 的 `order by distance_m` 註解掉，
   這條測試立刻紅（`[回來的全是 0m 那群]`），還原後全套 128 支綠。
2. `NoteService.HERE` → `CALLER_POINT`（旁邊的 `INSERT`／`PAGE`／`NEARBY` 都是語句名）。
3. `NearbyTest.seed` 9 個參數：抽出 `seed(author, site, north, count)` 與 `seedStyled(...)`，
   五個呼叫點的 `1, 1` 尾巴消失；排除那三行加上「旅遊紀錄／已被撿走／已過期」行內註解。
4. `site()` 補上 `% 30` 護欄（與 `DropNoteTest` 一致，緯度不隨測試數單調累加）。
5. 「完全沒有 body」加進 400-無-code 的參數化測試。

**已回報、未修（不在本票範圍，等指示）**

- `NEARBY` 與 `INSERT_PUBLIC` 各寫一次「未撿＋公開＋未過期」三述詞。票 08 撿取會是第三份
  ——**建議在票 08 一併抽成共用片段**（rule of three），而不是現在動票 05 的程式碼。
- TTL 用 `make_interval(secs => 7776000)`，RPC 版是 `interval '90 days'`：在有 DST 的 session
  時區下，90 天邊界會差一小時。Java 這邊三個呼叫點會用同一個表示式，彼此一致；UTC 部署
  無影響。**票 09（89／90／91 三處一致）要留意這一點**。
- `NoteService.drop` 仍綁著 `ttlCutoff` 參數，但 `INSERT_PUBLIC` 早已改用 `now()` ——死參數
  （票 05 遺留），可刪三行。
- 三個測試類別的腳手架（`post`／`assertProblem`／`fieldNames`／`traveler()`／隨機基準點）
  已逐字重複三份，可上提到 `SupabaseDbTest`。

**本票主動做、但清單沒列的一項**：座標驗證抽成 `located()`，`drop` 改呼叫它（行為等價，
兩支 endpoint 從此共用同一個「缺欄位 vs 越界」的分界）。不抽的話這段判斷就是第二份。
