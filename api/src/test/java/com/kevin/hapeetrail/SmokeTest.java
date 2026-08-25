package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeTest extends SupabaseDbTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	/**
	 * 斷言挑的是**表**的形狀，不是那五支 RPC——切換那天（票 13）RPC 會被 drop，
	 * 拿它們當錨會變成假紅。`audience`／`color`／`style` 與 `notes_author_private_ix`
	 * 分別來自第 7、6、12 支 migration，加上套用時的 `ON_ERROR_STOP`，14 支都套到了。
	 */
	@Test
	void migrationsAreApplied() {
		assertThat(queryBoolean("select to_regclass('public.notes') is not null")).isTrue();
		assertThat(queryBoolean("select count(*) = 3 from information_schema.columns"
				+ " where table_schema = 'public' and table_name = 'notes'"
				+ " and column_name in ('audience', 'color', 'style')")).isTrue();
		assertThat(queryBoolean("select to_regclass('public.notes_author_private_ix') is not null")).isTrue();
	}

	/**
	 * migration 依賴、但不由 migration 建立的東西：auth schema／auth.uid()／client 角色
	 * 全部由映像自帶（所以不需要任何 shim），postgis 則要能裝進 extensions schema。
	 * 少了任何一項就在這裡直說，不要變成後面某支測試看不懂的 FK 或權限錯誤。
	 */
	@Test
	void migrationPrerequisitesArePresent() {
		assertThat(queryBoolean("select to_regclass('auth.users') is not null")).isTrue();
		assertThat(queryBoolean("select count(*) = 0 from auth.users")).isTrue();
		assertThat(queryBoolean("select to_regprocedure('auth.uid()') is not null")).isTrue();
		assertThat(queryBoolean("select count(*) = 2 from pg_roles"
				+ " where rolname in ('anon', 'authenticated')")).isTrue();
		assertThat(queryBoolean("select exists (select from pg_extension e"
				+ " join pg_namespace n on n.oid = e.extnamespace"
				+ " where e.extname = 'postgis' and n.nspname = 'extensions')")).isTrue();
	}

	private Boolean queryBoolean(String sql) {
		return this.jdbc.sql(sql).query(Boolean.class).single();
	}

	@Test
	void healthNeedsNoAuth() throws Exception {
		var response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/actuator/health")).build(),
				BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

}
