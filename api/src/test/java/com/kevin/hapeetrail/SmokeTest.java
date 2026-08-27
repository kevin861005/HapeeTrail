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

	/** 服務自己的連線——以 {@code hapeetrail_api} 這個最小權限角色連上。 */
	@Autowired
	JdbcClient jdbc;

	/**
	 * 斷言挑的是**表**的形狀，不是那五支 RPC——切換（票 13）已把 RPC 全數 drop，
	 * 拿它們當錨會變成假紅。`audience`／`color`／`style` 與 `notes_author_private_ix`
	 * 分別來自第 7、6、12 支 migration，加上套用時的 `ON_ERROR_STOP`，16 支都套到了。
	 */
	@Test
	void migrationsAreApplied() {
		assertThat(adminQueryBoolean("select to_regclass('public.notes') is not null")).isTrue();
		assertThat(adminQueryBoolean("select count(*) = 3 from information_schema.columns"
				+ " where table_schema = 'public' and table_name = 'notes'"
				+ " and column_name in ('audience', 'color', 'style')")).isTrue();
		assertThat(adminQueryBoolean("select to_regclass('public.notes_author_private_ix') is not null")).isTrue();
	}

	/**
	 * migration 依賴、但不由 migration 建立的東西：auth schema／auth.uid()／client 角色
	 * 全部由映像自帶（所以不需要任何 shim），postgis 則要能裝進 extensions schema。
	 * 少了任何一項就在這裡直說，不要變成後面某支測試看不懂的 FK 或權限錯誤。
	 */
	@Test
	void migrationPrerequisitesArePresent() {
		assertThat(adminQueryBoolean("select to_regclass('auth.users') is not null")).isTrue();
		assertThat(adminQueryBoolean("select count(*) = 0 from auth.users")).isTrue();
		assertThat(adminQueryBoolean("select to_regprocedure('auth.uid()') is not null")).isTrue();
		assertThat(adminQueryBoolean("select count(*) = 2 from pg_roles"
				+ " where rolname in ('anon', 'authenticated')")).isTrue();
		assertThat(adminQueryBoolean("select exists (select from pg_extension e"
				+ " join pg_namespace n on n.oid = e.extnamespace"
				+ " where e.extname = 'postgis' and n.nspname = 'extensions')")).isTrue();
	}

	/**
	 * 服務被攻破時的攻擊面。用**服務自己的連線**問，所以它同時證明了「服務真的是以這個
	 * 角色連上的」——換回超級使用者，最後兩條會立刻紅。
	 */
	@Test
	void serviceConnectsAsTheLeastPrivilegedRole() {
		assertThat(queryBoolean("select current_user = 'hapeetrail_api'")).isTrue();
		assertThat(queryBoolean("select not rolsuper and not rolbypassrls"
				+ " from pg_roles where rolname = current_user")).isTrue();

		assertThat(queryBoolean("select has_table_privilege('public.notes', 'select')")).isTrue();
		assertThat(queryBoolean("select has_table_privilege('public.notes', 'insert')")).isTrue();
		assertThat(queryBoolean("select has_table_privilege('public.notes', 'update')")).isTrue();
		// 契約沒有刪除路徑；有 DELETE 就是白給的攻擊面。
		assertThat(queryBoolean("select has_table_privilege('public.notes', 'delete')")).isFalse();
		// auth.users 只被 FK 用到，FK 檢查以表擁有者身分跑——服務不需要看得到使用者表。
		assertThat(queryBoolean("select has_schema_privilege('auth', 'usage')")).isFalse();
		// RLS 不關（notes_select_own 保留休眠），全列放行只給這個角色。
		assertThat(adminQueryBoolean("select relrowsecurity from pg_class where oid = 'public.notes'::regclass")).isTrue();
	}

	/**
	 * 切換（票 13）之後 ADR-0007 的保證只剩一根柱子：**client 角色在 public 什麼都沒有**。
	 * 這裡逐物件問 pg 目錄，而不是點名幾支已知的函式——會出事的正是「沒人想到的那個新物件」：
	 * Supabase 在 public schema 設了 default privileges（新表 grant ALL、新函式 grant EXECUTE
	 * 給 anon／authenticated），所以任何一支忘了顯式 revoke 的 migration 都會靜默開一個洞。
	 * 煙霧測試的根路徑清單那條擋不住這個——hosted 的 PostgREST 對 client 根本不給清單。
	 */
	@Test
	void clientRolesOwnNothingInPublic() {
		// 表／view／sequence：任何一種權限都不該有
		assertThat(adminQueryBoolean("select not exists (select from information_schema.role_table_grants"
				+ " where table_schema = 'public' and grantee in ('anon', 'authenticated'))")).isTrue();
		// 函式：切換後 public 應該一支都不剩，遑論授權給 client
		assertThat(adminQueryBoolean("select count(*) = 0 from pg_proc p"
				+ " join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public'")).isTrue();
		// schema 本身的 CREATE——拿得到就能自己造一個帶 default privileges 的物件
		assertThat(adminQueryBoolean("select bool_and(not has_schema_privilege(r, 'public', 'create'))"
				+ " from unnest(array['anon', 'authenticated']) r")).isTrue();
	}

	private Boolean queryBoolean(String sql) {
		return this.jdbc.sql(sql).query(Boolean.class).single();
	}

	private static Boolean adminQueryBoolean(String sql) {
		return admin().sql(sql).query(Boolean.class).single();
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
