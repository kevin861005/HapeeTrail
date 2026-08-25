package com.kevin.hapeetrail;

import java.nio.file.Files;
import java.nio.file.Path;

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
 */
abstract class SupabaseDbTest {

	/** 與 hosted 同大版本；升級時同步 supabase CLI 用的 tag。 */
	private static final String IMAGE = "public.ecr.aws/supabase/postgres:17.6.1.143";

	private static final PostgreSQLContainer DB = start();

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
		applyMigrations(db);
		return db;
	}

	// ponytail: 用映像內的 psql 套 migration，不用 Java 端的 SQL 切割器——
	// migration 裡滿是 $$ 函式本體，切錯就是難查的假紅。
	private static void applyMigrations(PostgreSQLContainer db) {
		try {
			var result = db.execInContainer("bash", "-c",
					"set -e; for f in /migrations/*.sql; do "
							+ "psql -v ON_ERROR_STOP=1 -q -U postgres -d postgres -f \"$f\"; done");
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("migration 失敗：" + result.getStderr());
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

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", DB::getJdbcUrl);
		registry.add("spring.datasource.username", DB::getUsername);
		registry.add("spring.datasource.password", DB::getPassword);
	}

}
