# 03 — 骨架落地：`api/`、依賴、Testcontainers 起 Supabase Postgres 並套 migrations

**What to build:** 一個乾淨的 Spring Boot 4.1／Java 21 專案住在 repo 的 `api` 目錄，`mvn test` 在本機一鍵綠：Testcontainers 起 Supabase 官方 Postgres 映像（與 hosted 同大版本 17）、把 `supabase/migrations` 全部套上、`/actuator/health` 回 200。這是後面所有 TDD 的地基。

**Blocked by:** None — can start immediately

**Status:** done（2026-08-25）——一項打半勾：日誌的「正向那半」順延到 04（見下）

- [x] 今天建立的骨架目錄改名為 `api`；Copilot 的 `.github/modernize` 殘骸刪除；`HELP.md`、`.vscode`、`target` 不進 git；repo 根 `.gitignore` 視需要補
- [x] 依賴只加四類：JDBC starter ＋ PostgreSQL driver、OAuth2 resource server、Actuator、Testcontainers（test scope）；不加 JPA、不加其他
- [x] Actuator 只開 health，且不需認證；其餘端點關閉
- [x] 設定走環境變數：資料庫 URL／帳密、JWKS 位址；repo 內只有非機密預設值與 test profile
      （**沒有 test profile 檔**：測試的 datasource 由 `@DynamicPropertySource` 直接餵，
      建一個空的 `application-test.properties` 是預先鋪路；04 要放本機 RSA 公鑰時再建）
- [x] Testcontainers 用 Supabase 官方 Postgres 映像（查證映像自帶哪些：`extensions` schema、`auth` schema、`auth.uid()`、`anon`／`authenticated` 角色）；缺的以**最小 shim** 補在測試資源，預期只有 `auth.users`——shim 內容記進本票
- [x] 測試啟動時依檔名順序套用 `supabase/migrations` 全部 SQL，14 支全數成功（含 SECURITY DEFINER 函式與 `auth.uid()` 引用）
- [x] 一條測試：容器起、migration 套完、`GET /actuator/health` 200
- [x] `mvn test` 在乾淨機器（只有 Docker）上通過；執行時間記進本票
      （**只實測「映像已在本機」的情況**：無外部服務相依、不碰 hosted、不需 supabase CLI；
      真正的空機器沒跑過，時間也不含 1.76 GB 下載）
- [~] 日誌設定：INFO 只記路徑、狀態碼、耗時；request body 永不落日誌（後面每票沿用）
      **只做了否定的那半**（`spring.mvc.log-request-details=false`，body 與 query 不落日誌）。
      「INFO 記路徑／狀態碼／耗時」的那半**沒做**：03 沒有任何業務端點，唯一的路徑是
      `/actuator/health`（平台每隔幾秒打一次，記了只有噪音）。正向那半留到 04 的第一顆子彈，
      屆時有東西可記、也有測試守著。

---

## 結果

### 落檔

- `api/`（原 `hapeetrail/`）；repo 根與 `api/` 的 `.github/modernize/`、`api/HELP.md` 已刪。
  `api/.gitignore`（Initializr 產的）已蓋掉 `target/`、`.vscode/`、`HELP.md`，根 `.gitignore` 不用補。
- `api/src/main/java/com/kevin/hapeetrail/SecurityConfig.java` — `/actuator/health` permitAll，其餘 authenticated。
  **刻意還沒有 `oauth2ResourceServer(jwt)`**：JWT 驗證與它的五種變形是 04 的紅燈，先寫進去就是沒有測試守著的程式碼。
- `api/src/test/java/com/kevin/hapeetrail/SupabaseDbTest.java` — 容器底座（abstract，後面每支整合測試 extends 它）。
- `api/src/test/java/com/kevin/hapeetrail/SmokeTest.java` — 三條測試。
- Initializr 的 `HapeetrailApplicationTests` 刪除。

### 依賴（`api/pom.xml`，parent Boot 4.1.1）

| 用途 | artifact |
|---|---|
| web | `spring-boot-starter-webmvc`（Boot 4 的 `-web` 改名） |
| JDBC | `spring-boot-starter-jdbc` ＋ `org.postgresql:postgresql`（runtime） |
| JWT | `spring-boot-starter-security-oauth2-resource-server` |
| health | `spring-boot-starter-actuator` |
| 測試 | `spring-boot-starter-webmvc-test`、`org.testcontainers:testcontainers-postgresql`（test） |

⚠️ Boot 4.1 的命名陷阱（查證過 BOM，不是憑印象）：
- `spring-boot-starter-oauth2-resource-server` 仍在，但 pom 的 description 自己寫著
  「deprecated in favor of **`spring-boot-starter-security-oauth2-resource-server`**」——用後者。
- Testcontainers 由 Boot BOM 管到 **2.0.5**；2.0 的 module artifactId 是
  `testcontainers-postgresql`（1.x 是 `postgresql`），類別搬到 `org.testcontainers.postgresql.PostgreSQLContainer`
  且不再是泛型的 `<SELF>`。
- 沒有加 `spring-boot-testcontainers`：用 `@DynamicPropertySource` 直接餵三個 datasource 屬性，
  不需要 `@ServiceConnection`，也就不需要 `@Testcontainers`／`@Container` 的生命週期。

### 映像查證：**shim 內容 = 空的，一行都不用補**

映像 `public.ecr.aws/supabase/postgres:17.6.1.143`（＝ supabase CLI 2.105.0 本機用的同一顆，server_version 17.6）。
直接開容器查的結果：

| migration 依賴的東西 | 映像自帶？ |
|---|---|
| `auth` schema ＋ `auth.users`（`id uuid` PK，FK 對得上） | ✅ |
| `auth.uid()`（另有 `auth.email()`、`auth.role()`） | ✅ |
| `extensions` schema | ✅ |
| `anon`／`authenticated`／`service_role` 角色 | ✅ |
| postgis | 未安裝但 available 3.3.7，`create extension ... with schema extensions` 裝得起來 |

→ spec 預期的「只缺 `auth.users`」不成立，**測試資源沒有任何 shim 檔**。

### 起容器的三個坑（都是實測撞出來的，不要「順手簡化」掉）

1. **`withDatabaseName("postgres")`**：映像的 init 腳本只把 `auth`／`extensions` 建在預設的
   `postgres` 資料庫。換成 Testcontainers 預設的 `test`，或自己 `create database`，
   拿到的是沒有 `auth`／`extensions` 的空殼 → 第一支 migration 就 `schema "extensions" does not exist`。
2. **`withUsername("supabase_admin")`**：映像的 `POSTGRES_USER` 預設就是 `supabase_admin`，
   它的 `/docker-entrypoint-initdb.d/migrate.sh` 寫死 `psql -U supabase_admin` 來 bootstrap。
   覆寫成 `test`（TC 預設）或 `postgres` → 容器 exit 2、`FATAL: role "supabase_admin" does not exist`。
3. **`withCommand("postgres", "-D", "/etc/postgresql", "-c", "fsync=off")`**：
   Testcontainers 預設把 CMD 換成 `postgres -c fsync=off`，丟掉映像的 `-D /etc/postgresql`。
   那份 conf 才有 supabase 的 `shared_preload_libraries`（含 supautils／pg_tle 那套），
   少了它，非超級使用者的 `postgres` 角色連 `create extension postgis` 都被擋
   → `permission denied to create extension "postgis"`。

migration 用**容器內的 psql** 逐檔套（`psql -v ON_ERROR_STOP=1 -U postgres -f`，檔名順序），
不用 Java 端的 SQL 切割器——14 支裡滿是 `$$ ... $$` 函式本體，切錯會變成很難查的假紅。
以 `postgres` 身分套，與 supabase CLI 一致，SECURITY DEFINER 的擁有者才對得上。

### 測試（三條，`SmokeTest`）

- `migrationsAreApplied`：`public.notes` 在、`audience`／`color`／`style` 三個欄位在、
  索引 `notes_author_private_ix` 在（分別來自第 7、6、12 支 migration）。
  **刻意不拿那五支 RPC 當錨**——切換那天（票 13）它們會被 drop，拿它們斷言會變成假紅；
  表與索引則會活下來。套用時的 `ON_ERROR_STOP=1` ＋ `set -e` 保證 14 支任一支失敗就整個炸。
- `migrationPrerequisitesArePresent`：`auth.users`／`auth.uid()`／`anon`＋`authenticated`／
  postgis 裝在 `extensions`。少任何一項就在這裡直說，不要拖到後面變成看不懂的 FK 或權限錯誤。
- `healthNeedsNoAuth`：真 servlet 容器（RANDOM_PORT）、用 JDK 內建的 `HttpClient` 打
  `GET /actuator/health` → 200 ＋ `"status":"UP"`。

**突變測試**：把套 migration 的指令換成 `true`（容器照起、什麼都不套），
`migrationsAreApplied` 與 `migrationPrerequisitesArePresent`（postgis 那條）雙雙轉紅，
`healthNeedsNoAuth` 照樣綠——斷言是實心的，且三條各管各的。
（此輪突變是在錨還掛在五支 RPC 上時跑的，訊息是 `expected 5 but was 0`；換錨後同樣紅。）

### 執行時間

`./mvnw clean test`（映像已在本機、JDK 21）：**約 6 秒**，其中容器起到 ready 約 1.5 秒
（`fsync=off` 幫了大忙）。乾淨機器要另外算映像下載 1.76 GB。
JDK 21 與 JDK 26 兩種 `JAVA_HOME` 都跑過，皆綠。

### 設定

`api/src/main/resources/application.properties` 只有非機密預設值：Hikari 池 5、
actuator 只曝 health 且 `show-details=never`、`spring.mvc.log-request-details=false`（隱私）。
資料庫 URL／帳密與 JWKS 位址**完全不寫在 repo**，靠 Spring Boot 內建的 relaxed binding 從環境變數吃：
`SPRING_DATASOURCE_URL`／`_USERNAME`／`_PASSWORD`、`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`。
缺了就啟動失敗，是刻意的。

### 複核（`/code-review` 兩軸，2026-08-25）

Standards 與 Spec 兩個獨立 subagent 各自抓到同一顆真的：pom 多了
`spring-boot-starter-security-oauth2-resource-server-test`（我以為刪掉了，實際上那次編輯
因為 `cd` 失敗整段沒跑）——已刪。另外依複核改的：`migrationsAreApplied` 換錨（見上）、
`one()` 更名 `queryBoolean()`、上面三個打勾補上誠實的但書。

未採納並記帳的：測試以 `supabase_admin` 超級使用者連線，違反 CLAUDE.md「最小權限角色
`hapeetrail_api`」——**04 之前所有權限與 RLS 破口在測試裡是隱形的**，這是 04 的第一件事。

### 留給 04 的事

- `SecurityConfig` 補 `oauth2ResourceServer(jwt)`、stateless、關 csrf（bearer token API），
  測試 profile 換成本機 RSA 公鑰，五種 JWT 變形各一條 401。
- 準備 migration（`hapeetrail_api` 角色、權限、RLS policy）進 `supabase/migrations`，
  之後 `SupabaseDbTest` 會自動套到，datasource 改用該角色連線。
