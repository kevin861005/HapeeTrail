# T34 Spec — `PATCH /v1/me` 更新暱稱

來源：Kevin 2026-10-11 裁決（上限 20 code point、只做暱稱、不擋重複）。契約 **v4.3.0 → v4.4.0**（新端點＋兩個新 token，非破壞性）。

## 契約
- `PATCH /v1/me`，需要 Bearer，body `{"nickname": "Kevin"}`；成功 **200** 回 Profile（五鍵，與 `GET /v1/me` 同形）。
- `nickname`：字串或 `null`。
  - 伺服器**先 trim 再計數**，空白字元集與便條內容相同（`NoteService.EDGE_WHITESPACE`：Unicode White_Space ＋ U+001C–U+001F）；
    回傳的 `nickname` 是 trim 後的版本。
  - trim 後為空 → 400 `nickname_empty`（有 code）。
  - 超過 **20 個 Unicode code point** → 400 `nickname_too_long`，`details: {"maxChars": 20}`。
  - `null` ＝ 清除自訂暱稱，之後 `GET /v1/me` 回到提供者給的名字（訪客為 null）。
  - 缺 `nickname` 鍵、型別不是字串（數字、布林、物件）、非法 JSON、含 U+0000 或孤立代理對 → 400 **沒有 code**（契約 §2 既有規則；型別由 JsonConfig 的嚴格 coercion 擋）。
- 不做唯一性、不做敏感詞。
- 使用者列不在（session 檢查後被註銷）→ 401 `not_authenticated`（同 `GET /v1/me`）。

## 儲存
- 新 migration `supabase/migrations/20261011010000_profiles.sql`：
  ```sql
  create table hapeetrail_private.profiles (
    user_id uuid primary key references auth.users (id) on delete cascade,
    nickname text not null check (nickname = btrim(nickname) and length(nickname) between 1 and 20),
    updated_at timestamptz not null default now()
  );
  grant select, insert, update, delete on hapeetrail_private.profiles to hapeetrail_api;
  ```
  （CHECK 只是最後防線；業務規則在 Java。`delete` 是為了 `null` 清除；`truncate` 不給。）
  ⚠️ FK 指向 `auth.users`，服務角色沒有 auth schema 權限，但 FK 檢查以表擁有者身分跑，與 `notes` 相同；
  23503 會被 `ApiErrors.identityGone` 轉成 401，正是「被註銷」那條路。
- 寫入：`insert … on conflict (user_id) do update set nickname = excluded.nickname, updated_at = now()`；
  `null` → `delete from profiles where user_id = ?`。
- 讀取：`GET /v1/me` 的 SQL 改成 `coalesce(p.nickname, meta_full_name, meta_name, identity_full_name, identity_name)`，
  左接 `hapeetrail_private.profiles p on p.user_id = v.id`（在 Java 那句 SQL 裡 join，view 不動）。
- PATCH 成功後回傳的 Profile 直接重查一次同一句 SQL（最簡單、與 GET 逐字一致）。

## 程式
- `AccountController`：加 `@PatchMapping("/v1/me")`，body record `UpdateProfileRequest(Optional/String nickname)`——
  要分得出「缺鍵」與「給 null」：用 Jackson 的 `JsonNullable` 不在依賴裡，改用 `@RequestBody JsonNode`？不要。
  ponytail 做法：record `UpdateProfileRequest(String nickname)` ＋ 一個 `@JsonProperty(access=…)`？也不要。
  **最簡單可行**：`@RequestBody(required = false) Map<String, Object> body`，`body == null || !body.containsKey("nickname")` → 400 無 code；
  值為 null → 清除；值不是 `String` → 400 無 code（Jackson 對 Map 不會嚴格 coercion，所以這裡自己 `instanceof String`）。
  — 若實作者找到更乾淨且仍能區分三態（缺鍵／null／字串）的方式，可以採用，但三態語意不可變。
- trim 規則共用：把 `NoteService.EDGE_WHITESPACE` 搬到 `config/Wire` 之類的共用處（或 `NoteService` 開 package-private static 方法給 account 用——跨 package 不行，所以搬到 `config/Wire.java` 當 `public static String trim(String)`），NoteService 改用它，行為不變（既有便條測試會證明）。
- U+0000／孤立代理對：便條那邊是 DB 的 text 型別存不下 → 400 無 code（`DataIntegrityViolation`？實際上是 `BadSqlGrammar`／`PSQLException` 走 catch-all？）——**請查 `NoteService`／`ApiErrors` 既有做法照搬**，讓 nickname 與 content 行為逐字相同。

## 測試（TDD：先紅後綠）
`ProfileTest` 或新 `UpdateProfileTest`：
1. 設暱稱 → 200 回五鍵、nickname 為 trim 後值；再 `GET /v1/me` 一致。
2. 自訂暱稱優先於 Google identity 的 full_name；`null` 清除後回到 full_name；訪客清除後回 null。
3. 空字串／只有空白／NBSP → 400 `nickname_empty`；21 個 code point（含 emoji 算 1）→ 400 `nickname_too_long` 且 `details.maxChars=20`；恰 20 → 200。
4. 缺鍵 `{}`、`{"nickname":123}`、`{"nickname":true}`、`{"nickname":{}}`、非法 JSON、無 body → 400 無 code。
5. 無 token → 401。
6. 兩次更新 upsert（第二次覆蓋）；`SmokeTest` 補 `profiles` 的權限斷言（select/insert/update/delete 有、truncate 無；client 角色與 service_role 無）。

## 契約三檔（v4.4.0）
- openapi：`/v1/me` 加 `patch`（requestBody schema `UpdateProfile`：`nickname` string nullable、maxLength 20；200 Profile；400 列兩個 token；401）；
  `Problem.code` enum 加 `nickname_empty`、`nickname_too_long`；description 寫明 trim 規則與 null 語意。
- notes.md：§1 加「更新暱稱」小節（curl 範例、規則）；§8 錯誤碼表加兩列（400／更新暱稱）＋ `details` 表加 `nickname_too_long` `{"maxChars": 20}`；
  §11 加 v4.4.0 條目。`check-contract.py` 會比對 token 集合與狀態碼，三份要一致。
- Postman：旅人 A 資料夾加「更新暱稱 PATCH /v1/me」（斷言 200、五鍵、nickname 等於送出的 trim 值）與「錯誤示範：暱稱太長 → 400 nickname_too_long」（斷言 code 與 details.maxChars）。手排版 JSON，文字層級插入。
- CONTEXT.md：「基本資料」加一句「暱稱可由本人設定（PATCH /v1/me），優先於登入提供者給的名字」。

## 驗收
`cd api && ./mvnw test` 全綠；`python3 docs/api/check-contract.py` exit 0；redocly lint valid（2 warnings 既有）。
不 commit、不 push、不 db push、不部署（主 session 處理）。
