# 14 — 複核發現修正（切換前小修）

**What to build:** 票 12 兩份獨立複核的處置。經使用者 2026-08-27 裁決，**只修**兩條 MAJOR
＋ JWT 的 `iss`／缺 `exp` 兩條縱深；其餘 MINOR 明確分類為「併入本票」「不修」「留給上線前」。
不做票面以外的任何改動。

**Blocked by:** 12　**Blocks:** 13（切換前必須綠）

**Status:** done（2026-08-27）

## 要修的（本票範圍）

- [x] **M1 錯誤信封 catch-all**（MAJOR，安全）——`ApiErrors` 補 `@ExceptionHandler(Exception.class)`：
      任何未攔下的例外一律回 problem+json 的 500、**沒有 `code`**，不再回 Spring 預設錯誤頁。
      ERROR 級只記例外本身，不記請求內容。
- [x] **M1a 內容含 U+0000 → 400 無 code**（MAJOR 的路徑 (a)，唯一可被外部無限次觸發的那條）——
      Postgres 的 text 存不下 NUL，spec 的 trim 字元集又不含它 ⇒ 屬「格式錯誤」那一桶，
      走既有的 `malformed()`，**不新增契約 token**（spec 第 213 行：不新增 token）。
- [x] **M1b `sub` 非 UUID → 401**（MAJOR 的路徑 (b)）——`SecurityConfig.subjectRequired()`
      直接以 UUID 解析取代 `hasText`，失敗丟 `InvalidBearerTokenException`；
      **例外訊息不得帶 `sub` 原值**（原本會進 ERROR 日誌）。
- [x] **M1c token 的使用者已被刪 → 401**（MAJOR 的路徑 (c)）——`notes` 上只有兩支 FK
      （`author_id`、`picked_up_by`，皆指向 `auth.users`），故 SQLSTATE 23503 的唯一語意就是
      「呼叫者的身分已不存在」⇒ 映射為 `not_authenticated` 401，不是 500。
- [x] **M2 字串欄位型別閘門**（MAJOR，正確性）——`JsonConfig` 對 `LogicalType.Textual`
      把 `Integer`／`Float`／`Boolean` 三種輸入形狀設為 `CoercionAction.Fail`，
      使 `content: 123`／`audience: 5` 回 400 無 `code`，而不是 200／`invalid_audience`。
- [x] **S1 `iss` 驗證**（MINOR，縱深）——`aud=authenticated` 是每個 Supabase 專案的共同值，
      唯一擋跨專案 token 的只有簽章；補第二道。**不可用 Boot 的 `issuer-uri`**：
      `KeyValueCondition` 明文與 `public-key-location` 互斥（測試走那條，會整組沒有 decoder）。
      改宣告 `OAuth2TokenValidator<Jwt>` bean——`JwtDecoderConfiguration` 的 `additionalValidators`
      在三條 decoder 路徑上都會併進去（bytecode 已證）。issuer 值走環境變數，不進 repo。
- [x] **S2 缺 `exp` 的 token → 401**（MINOR，縱深）——同一個 bean 裡加
      `JwtClaimValidator<Instant>(EXP, Objects::nonNull)`；目前沒有 `exp` 的 token 永不過期。

## 明確不修（記帳，不是遺漏）

- **恰 50.000000m 撿不到**（`ST_DWithin` 邊界）——v3.3 RPC 逐字相同、語意凍結；
  且 `pickable` 與 pickup 判定共用同一運算式，恰 50m 兩處都 false，
  沒有「看得到撿不到」的分歧。**不修**。
- **過期 token 60 秒 clock skew**——Spring 的預設值是刻意的（GoTrue 與裝置時鐘會漂）；
  歸零會製造偽 401。**不修**，但記在此。
- **postman local environment 的 publishable key**——設計上就要進 client、只對 127.0.0.1 有效，
  非 DB 密碼／service_role／JWT secret；hosted 那份 apikey 已刻意留空。**不修**。
- **`iss` 之外的 JWT 出貨路徑（jwk-set-uri ＋ ES256）零測試覆蓋**——要在測試裡架 JWKS server，
  超出本票；記進票 13 前的風險清單。

## 留給上線前（不進本票）

- `limit` 接受 `0x10`／`#10`／前導空白（Spring `Long.decode`）、超過 `Long.MAX_VALUE` 回 400 而非夾住、
  `limit=` 與 `cursor=` 對空值立場相反。
- 請求 body 無大小上限（`-Xmx256m` 下 6 條併發 30MB 打出 `OutOfMemoryError`）。
- `openapi.yaml` 的 `servers` 預設仍是 cleartext `http://`（上線換 Fly https 時一併處理）。

## 驗收

- [x] 走 TDD：每條先寫紅的測試，再修到綠
- [x] `cd api && ./mvnw test` 全綠（現況 174 支，本票會增加）
- [x] 派獨立 subagent 複核（只給本票與 spec，不繼承實作假設）
- [x] 結論與證據記進本票文末＋票 12 文末

---

## 證據（2026-08-27）

七條全部走 red→green，每條先看到紅再修。**主程式只動 5 個檔、+96 行。**

| 條 | 改在哪 | 紅的樣子 → 綠的樣子 |
|---|---|---|
| M1 | `ApiErrors.java:69` `@ExceptionHandler(Exception.class)` | `/v1/boom` 回 Spring 預設頁 `{"timestamp":…,"error":…,"path":…}` → `500 {"type":"about:blank","status":500,"title":"Internal Server Error"}`、`application/problem+json`、無 `code` |
| M1a | `NoteService.java:263` `content.indexOf(0) >= 0` → `malformed()` | `PSQLException: invalid byte sequence for encoding "UTF8": 0x00` ⇒ 500 → 400 無 `code`；5 種 NUL 變形全綠 |
| M1b | `SecurityConfig.java:95-104` UUID 來回比對取代 `hasText` | `sub=not-a-uuid` ⇒ 500 ＋ 日誌 `Invalid UUID string: not-a-uuid` → 401，例外訊息不帶 sub 原值 |
| M1c | `ApiErrors.java:56` SQLSTATE `23503` → 401 | 使用者已刪的 token 打 `POST /v1/notes` ⇒ 500 → `401 not_authenticated` |
| M2 | `JsonConfig.java:35` `LogicalType.Textual` 三種輸入形狀 `Fail` | `content:123` ⇒ 200 存成 `"123"`、`audience:5` ⇒ `invalid_audience`（有 code）→ 兩者皆 400 無 `code` |
| S1 | `SecurityConfig.java:48` `JwtIssuerValidator` | `iss=https://evil.example/auth/v1` 與缺 `iss` ⇒ 200 → 401 |
| S2 | 同一個 bean，`JwtClaimValidator(EXP, Objects::nonNull)` | 無 `exp` 的 token ⇒ 200（永不過期）→ 401 |

### S1 的查證推翻了原計畫（記給下一個人）

**不能用 Boot 的 `spring.security.oauth2.resourceserver.jwt.issuer-uri`。**
反編譯 `spring-boot-security-oauth2-resource-server-4.1.1.jar` 的 `KeyValueCondition`：
它明文要求 `public-key-location` 有值**且 `jwk-set-uri` 與 `issuer-uri` 都沒值`**——
設了 `issuer-uri`，測試走的那條 decoder 路徑會整組不存在。

改用 `OAuth2TokenValidator<Jwt>` bean。同一支 jar 的 `JwtDecoderConfiguration` bytecode 證實：
建構子把 `ObjectProvider<OAuth2TokenValidator<Jwt>>.orderedStream().toList()` 存進
`additionalValidators`，`getValidator()` 在 issuer／audience validator 之後 `addAll` 它，
而三個 decoder bean（`jwtDecoderByPublicKeyValue`／`jwtDecoderByJwkKeySetUri`／
`jwtDecoderByIssuerUri`）**都**呼叫 `getValidator()` ⇒ 測試與正式路徑同時生效。

issuer 值走新的必填環境變數 `HAPEETRAIL_JWT_ISSUER`（不是機密），已同步進
`api/fly.toml` 的 `[env]`、`api/README.md` 兩處、`application.properties` 的註解。

### 票面外、但屬 M1c 必要後果的一處改動

401 body 的鍵序統一為 `type,status,title,code`。`Problem` record 在 HEAD 就是這個序，
不一致的是 `SecurityConfig` 那份**手寫字串**（`type,title,status,code`）。M1c 讓
`ApiErrors.business` 成為第二個 401 產出者，不對齊就會有兩種 401 形狀。
全 repo grep 舊鍵序只剩票 10 的施工紀錄（無斷言效力）；`docs/api/postman/` 沒有任何
401 body 斷言；newman／smoke 全部解析 JSON，不依賴鍵序。

### 契約文件同步（CLAUDE.md：介面變更必須同步更新）

- `notes.md` §11 新增 **v4.0.1** 條目：M2 造成 `content: 123` 與 `audience: 5`
  從「200／業務錯誤」**改判為型別錯誤**（有無 `code` 的分流會走到不同分支——
  contract pack 已於 `9e40191` 交付夥伴，這是他 error switch 會踩到的變更）；
  U+0000 從 500 改為 400 無 `code`；401 範圍收緊的四種情況。
- `notes.md` §4、`openapi.yaml` 的 `content` 描述：補「不得含 U+0000」。
- `notes.md` §2 註 3、`openapi.yaml` 的 `Unauthorized`：401 成因從五種補到八種。
- `openapi.yaml` `info.version` 4.0.0 → **4.0.1**。
- `check-contract.py` exit 0、`redocly lint` valid（1 個既有 warning）。

### 測試

`cd api && ./mvnw test` → **189 支全綠**（基準 174 ＋ 15）。
新增：`ErrorEnvelopeTest`（2）、`AuthTest.badTokensAre401` +6 列、
`AuthTest.tokenForADeletedUserIs401`、`DropNoteTest.malformedRequestsGet400WithoutACode` +6 列。

**突變驗證**（由驗收者做，非自證）：把四支 main 檔還原到 HEAD、只保留新的 401 鍵序後，
恰好 12 支失敗（DropNoteTest 6 ＋ AuthTest 5 ＋ ErrorEnvelopeTest 1）——
新測試全部實心，沒有一支是同義反覆。

## 獨立驗收（只拿本票與 spec，不繼承實作假設）

**判定：有條件通過（9 條，全 MINOR，零迴歸）。**

驗收者另外打了 spec 錯誤對照表的全部 14 個 token，確認 **400／401／403／404／409／422／429
七種狀態碼沒有任何一個被 catch-all 吃成 500**；框架層 404／405／406／415／400 亦正確；
`/actuator/health` 在 DB 停掉時仍回 `503 DOWN`（Fly health check 未受影響）。
S1／S2 是**在 `jwk-set-uri` 那條正式 decoder 路徑上**實測的（本機起 JWKS server），
不是只驗測試路徑。M1c 未過寬：臨時 check constraint 觸發 `23514` → 仍是 500 無 `code`。
M2 未誤傷：`color`／`style` 整數、`audience` 字串、`content:"123"`、cursor round-trip 全部照常 200。

### 已收的 6 條

1. **`UUID.fromString` 寬鬆解析**——`1-1-1-1-1` 與 `+1-1-1-1-1` 原本會通過並別名成
   `00000001-0001-0001-0001-000000000001`。改成來回比對
   （`UUID.fromString(sub).toString().equalsIgnoreCase(sub)`），補 2 支測試。
   不可利用（GoTrue 只發標準 UUID），但票面 M1b 說的就是「非 UUID 混不進來」。
2. **非 23503 的完整性錯誤仍是 500 沒有測試守著**——補
   `ErrorEnvelopeTest.integrityErrorsThatAreNotForeignKeysStay500`（23514 → 500 無 `code`）。
   這條守的是「身分問題」與「伺服器故障」不合流。
3. **契約 Changelog 未記 M2 的類別轉移**——已補 v4.0.1（見上）。
4. **`openapi.yaml` 的 `content` 缺 U+0000 規則**（`notes.md` 有）——已補。
   openapi 是 wire format 的權威，夥伴做 client 端預檢會照它寫。
5. **三處「五種變形」註解已與實作分歧**（`SecurityConfig.java`、`AuthTest.java` ×2）——已改。
6. **README 序數失準**（加了第五個環境變數後「前四個」「第五個」都指錯）——已改；
   `fly.toml` 現在有兩個 `[env]` 條目，指涉也一併寫清楚。

### 記帳、不修的 3 條

- **`HAPEETRAIL_JWT_ISSUER` 設成空字串時不會啟動失敗**（完全缺 key 才會失敗，
  `PlaceholderResolutionException`）。空值下每個真 token 都 401 ⇒ **fail-closed，不是繞過**，
  症狀等同 README 已警告的 `JWS_ALGORITHMS` 情境。`application.properties` 的註解說得太滿，
  但行為安全，不值得為它加驗證碼。
- **孤立代理對被靜默改寫成 `?` 並存入資料庫**（`"a\ud800b"` → `"a?b"`，200）。
  **既有行為、非本票造成**，且是 U+0000 的鄰居問題。要處理需要先決定
  「拒絕還是原樣保存」——那是契約決策，另開 ticket。
  對照組已確認：無效 UTF-8 原始位元組 → 400 無 code；合法代理對（😀）→ 200 原樣保存；
  U+0001–U+001B、U+007F 在中間 → 200 未誤擋。
- 票 10 的施工紀錄 `10-first-deploy-fly.md:37` 仍記著舊的 401 鍵序。施工紀錄無斷言效力，留。

### 驗收者明列的未驗證項目

- **真 GoTrue 的 ES256 token 端到端**：全程用自鑄 RS256 ＋ 本機 JWKS server。
  `JWS_ALGORITHMS=RS256,ES256` 只驗到「帶著它能啟動且 validator 仍生效」，
  沒有真的用 ES256 簽一顆打過（hosted DNS 在該環境不可達）。
- **newman／hosted 煙霧測試未跑**（只跑了 `check-contract.py`）。
  ⚠️ **票 13 切換前必須補這格**：本票改了 401 的範圍與 body 鍵序、改了兩種請求的錯誤類別。
- **client abort 的日誌噴發**：5 次 raw socket 中斷未觀察到 ERROR 行，
  但結論是「未觀察到迴歸」，不是「已排除」。
