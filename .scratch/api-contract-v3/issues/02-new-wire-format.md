# 02 — 便條與探索提示的新 wire 格式

**What to build:** 留下便條、撿起便條、探索附近三支 RPC 開始回傳 v3 形狀。iOS 從這張票
開始看到的就是最終的欄位命名與結構：貼合 client 的 camelCase 鍵名、巢狀的座標物件、
格式固定的時間戳；探索結果不再是裸陣列，而是包成物件。

形狀本身即為契約（以下形狀比散文更精確地編碼了決定）：

```json
// 便條
{ "id": "…", "content": "今天的風很舒服",
  "coordinate": { "latitude": 25.065472, "longitude": 121.533397 },
  "createdAt": "2026-07-23T10:00:00.123456Z", "pickedUpAt": null }

// 探索提示（刻意不含內容與作者）
{ "id": "…", "coordinate": { "latitude": 25.06597, "longitude": 121.5334 },
  "distanceM": 30, "pickable": true, "createdAt": "…" }

// 探索結果
{ "items": [ … ] }
```

**Blocked by:** 01 — 回傳形狀改為白名單建構

**Status:** done — `supabase/migrations/20260728010000_wire_format_v3.sql`（2026-07-28）

- [x] 鍵名一律 camelCase，**縮寫視為普通單字**——此後新增欄位一律比照（例如日後的 `photoUrl`，不是 `photoURL`）
      ✅ 便條 `{id,content,coordinate,createdAt,pickedUpAt}`、提示 `{id,coordinate,distanceM,pickable,createdAt}`；
      「縮寫視為普通單字」的比照規則寫進 `docs/api/notes.md` §3 與 `openapi.yaml` 的 Note description
      （完整的「未知值政策」一節屬 ticket 07，此處不做）
- [x] 座標為巢狀物件，欄位名為 `latitude` 與 `longitude`
      ✅ HTTP 實測：`"coordinate": {"latitude": 35.6595, "longitude": 139.7005}`（drop/my_notes/nearby 三處一致）
- [x] 時間戳固定為六位小數並以 `Z` 結尾，不因秒數恰為整數而變動位數
      ✅ 唯一格式化處 `public.as_wire_ts`（`to_char(... 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')`）；
      SQL 與 newman 皆以 `^\d{4}-..T..:..:..\.\d{6}Z$` 斷言。另實測整秒值 → `...00:00.000000Z`
- [x] 探索結果為含 `items` 的物件而非裸陣列；即使結果為空也回傳空陣列而非 null
      ✅ `coalesce(jsonb_agg(...), '[]'::jsonb)`；遠處查詢實測回 `{"items": []}`。
      零結果路徑有針對性斷言（`items = '[]'::jsonb` 而非只看 length），my_notes 空列表同樣斷言
- [x] 探索提示仍不含內容與任何作者資訊
      ✅ 測試斷言提示的**精確鍵集**（不是「有哪些」而是「只有哪些」），content/author_id 出現即 FAIL
- [x] 請求 body 的鍵名維持既有的 `p_` 開頭底線命名，座標參數維持兩個獨立浮點數而非巢狀物件
      ✅ 五支函式簽名未動（newman 既有請求 body 一字未改仍全綠）
- [x] SQL seam 的形狀斷言更新為新格式並全綠；便條物件不含任何身分欄位或內部欄位的既有斷言保留
      ✅ `psql -f supabase/tests/notes.test.sql` → `ALL TESTS PASSED`、`grep -cE 'ERROR|FAIL'` = 0；
      身分欄位斷言升級為精確鍵集比對（含舊 snake_case 鍵不得殘留）
- [x] newman 加入斷言：鍵名確實以 camelCase 抵達、座標確實是巢狀物件
      ✅ 9 requests / 9 assertions / 0 failed（drop 的 5 鍵＋coordinate 子鍵＋時間戳 regex、
      nearby 的 envelope＋提示鍵集、pickup 的 `pickedUpAt`）
- [x] OpenAPI 規格與語意文件的對應段落同步更新；語意文件刪除「時間戳小數秒位數可變，解析器必須容忍」的警告
      ✅ `openapi.yaml` v3.0.0（新增 `Coordinate`/`NearbyResult` schema，`Timestamp` 帶 pattern）
      redocly lint 通過；`notes.md` v3.0 changelog、§3 形狀、§5 envelope、§7 游標範例同步

**額外驗證：**

- EXPLAIN：`nearby_notes` 內層仍走 `notes_active_location_gix`（Index Scan + `_st_expand` Index Cond）；
  `my_notes` 內層仍走 `notes_author_ix`（Bitmap Index Scan）——與 T11-01 同一組計畫
- 權限分工不變：`pg_proc` 查驗 `drop_note`/`nearby_notes`/`pickup_note` = DEFINER、
  `my_notes`/`my_collection` = INVOKER，全部 `search_path=""`，ACL 皆 `postgres,authenticated,service_role`（anon 無）
- 新增的 `as_wire_ts` 同樣收回 public/anon 只授 authenticated（兩支 INVOKER 列表需要）

**設計註記：** 形狀承載體由 T11-01 的 composite type 改為 jsonb——巢狀物件與「時間戳是格式固定的
字串」不是關聯型別擅長表達的東西。白名單性質不變：欄位仍逐一明列於單一建構處
（便條 = `as_note_wire`、提示 = `nearby_notes` 內唯一的 `jsonb_build_object`）。
兩支列表暫時回裸陣列，envelope 與不透明游標一起在 03 落地。

**注意：** 探索 RPC 的回傳型別由資料表改為單一 JSON 物件，**必須先移除舊函式再建立**，
不能就地取代；移除後需重新套用權限的收回與授予。函式增刪後 PostgREST 的 schema cache
不會自動更新，跨 HTTP 驗證前必須先觸發重載。
