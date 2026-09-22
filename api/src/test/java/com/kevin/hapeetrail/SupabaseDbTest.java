package com.kevin.hapeetrail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 所有整合測試的資料庫底座：Supabase 官方 Postgres 映像（與 hosted 同 17.x），
 * 套上 {@code supabase/migrations} 的全部 SQL。
 *
 * <p>容器是整個 JVM 共用的單例（1.7GB 映像，每個測試類別各起一顆太貴），
 * 隨 JVM 結束由 Testcontainers 的 ryuk 收掉。
 *
 * <p>服務以最小權限的 {@code hapeetrail_api} 連線（與正式環境同一個角色）——
 * 測試若以超級使用者連，權限與 RLS 的破口在測試裡就是隱形的。測試自己要做的
 * 資料佈置（例如 {@code auth.users} 的列，服務角色刻意沒有權限）走 {@link #admin()}。
 */
abstract class SupabaseDbTest {

	/** 與 hosted 同大版本；升級時同步 supabase CLI 用的 tag。 */
	private static final String IMAGE = "public.ecr.aws/supabase/postgres:17.6.1.143";

	/** migration 只建角色不設密碼（密碼只在部署平台的 secrets）；本機測試自己補一個。 */
	private static final String API_ROLE = "hapeetrail_api";

	private static final String API_PASSWORD = "test-only-not-a-secret";

	private static final PostgreSQLContainer DB = start();

	private static final JdbcClient ADMIN = JdbcClient
		.create(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));

	/** 服務要驗的公鑰：本機自簽那把，取代 hosted 的 JWKS（測試不碰網路）。 */
	private static final Path PUBLIC_KEY = TestJwt.publicKeyFile();

	private static PostgreSQLContainer start() {
		Path migrations = Path.of("..", "supabase", "migrations").toAbsolutePath().normalize();
		if (!Files.isDirectory(migrations)) {
			throw new IllegalStateException("找不到 migrations：" + migrations);
		}
		PostgreSQLContainer db = new PostgreSQLContainer(
				DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
				// 兩個名字都必須跟映像自己的預設一致，否則它的 init 腳本整個不跑：
				// auth／extensions schema 只建在預設的 postgres 資料庫，
				// 而 bootstrap 用的超級使用者是 initdb 建的 supabase_admin。
				.withDatabaseName("postgres")
				.withUsername("supabase_admin")
				// Testcontainers 預設把 CMD 換成 `postgres -c fsync=off`，會丟掉映像的
				// `-D /etc/postgresql`——那份 conf 才有 supabase 的 shared_preload_libraries，
				// 少了它 postgres 這個非超級使用者連 create extension postgis 都會被擋。
				.withCommand("postgres", "-D", "/etc/postgresql", "-c", "fsync=off")
				.withCopyFileToContainer(MountableFile.forHostPath(migrations), "/migrations");
		db.start();
		// auth.sessions 是 GoTrue 自己的 migration 建的——hosted 與 supabase CLI 都在我們的
		// migration 之前跑它，這顆映像沒有 GoTrue ⇒ 補一張等價的：同擁有者、同一條
		// `grant … with grant option`（GoTrue migration 20240612123726），權限形狀才與 hosted 相同。
		// ponytail: 欄位只取 PK 與 FK cascade 兩個；測試用得到其他欄位時再照 GoTrue 補。
		exec(db, "psql -v ON_ERROR_STOP=1 -q -U supabase_admin -d postgres -c \"set role supabase_auth_admin;"
				+ " create table auth.sessions (id uuid primary key,"
				+ " user_id uuid not null references auth.users (id) on delete cascade);"
				+ " grant select on auth.sessions to postgres with grant option\"");
		// ponytail: 用映像內的 psql 套 migration，不用 Java 端的 SQL 切割器——
		// migration 裡滿是 $$ 函式本體，切錯就是難查的假紅。
		exec(db, "set -e; for f in /migrations/*.sql; do "
				+ "psql -v ON_ERROR_STOP=1 -q -U postgres -d postgres -f \"$f\"; done");
		exec(db, "psql -v ON_ERROR_STOP=1 -q -U postgres -d postgres "
				+ "-c \"alter role " + API_ROLE + " password '" + API_PASSWORD + "'\"");
		return db;
	}

	private static void exec(PostgreSQLContainer db, String script) {
		try {
			var result = db.execInContainer("bash", "-c", script);
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("容器內指令失敗：" + result.getStderr());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * 超級使用者連線，只給測試自己用：斷言資料庫的形狀、以及佈置服務角色刻意碰不到的資料。
	 * 產品程式碼永遠不該有這條路。
	 */
	static JdbcClient admin() {
		return ADMIN;
	}

	/** GoTrue 登入時建的兩列：使用者與它的 session。回傳 session id（＝ token 的 {@code session_id}）。 */
	static UUID openSession(UUID user) {
		UUID session = UUID.randomUUID();
		ADMIN.sql("insert into auth.users (id) values (?)").param(user).update();
		ADMIN.sql("insert into auth.sessions (id, user_id) values (?, ?)").params(session, user).update();
		return session;
	}

	/** 一個剛登入的旅人手上的 token：使用者與 session 都真的在。 */
	static String signIn(UUID user) {
		return TestJwt.valid(user, openSession(user));
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", DB::getJdbcUrl);
		registry.add("spring.datasource.username", () -> API_ROLE);
		registry.add("spring.datasource.password", () -> API_PASSWORD);
		registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location", () -> "file:" + PUBLIC_KEY);
		registry.add("hapeetrail.jwt.issuer", () -> TestJwt.ISSUER);
		registry.add("hapeetrail.gotrue.url", FakeGoTrue::url);
		registry.add("hapeetrail.gotrue.secret-key", () -> FakeGoTrue.SECRET_KEY);
	}

}
