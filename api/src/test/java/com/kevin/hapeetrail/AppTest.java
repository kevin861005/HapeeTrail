package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T31 更新檢查 {@code GET /v1/app/update}、T32 功能開關 {@code POST /v1/app/flags}。
 * 設定都在 {@code hapeetrail_private} 的兩張表，測試以 {@link #admin()} 佈置。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppTest extends SupabaseDbTest {

	@LocalServerPort
	int port;

	private String token;

	@BeforeEach
	void aTravelerAndTheDefaultGates() {
		this.token = signIn(UUID.randomUUID());
		versions("1.2.0", "1.4.0");
	}

	// ── T31 ────────────────────────────────────────────────────────────────────

	/**
	 * 伺服器只交出兩個門檻、原值來自表；「低於 minimum 強制、低於 latest 提示」由 App 自己比
	 * （2026-10-11 會議）。不收參數，所以這支沒有 400。
	 */
	@Test
	void returnsExactlyTheTwoGatesFromTheTable() throws Exception {
		var response = get("/v1/app/update", this.token);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"minimumVersion\":\"1.2.0\",\"latestVersion\":\"1.4.0\"}");
	}

	// ── T32 ────────────────────────────────────────────────────────────────────

	/** migration 一上線就種了 {@code paywall = true}（2026-10-11 會議）：不佈置任何資料直接問。 */
	@Test
	void paywallIsSeededOn() throws Exception {
		var response = post("/v1/app/flags", "{\"keys\":[\"paywall\"]}", null);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"flags\":{\"paywall\":true}}");
	}

	/** 每個 key 一個布林；表裡沒有的 key 是 false，不是錯誤、也不是缺鍵。重複 key 合併。 */
	@Test
	void everyRequestedKeyGetsABoolean() throws Exception {
		flag("map.heatmap", true);
		flag("notes.drop", false);

		var response = post("/v1/app/flags", "{\"keys\":[\"map.heatmap\",\"notes.drop\",\"nobody.set.this\",\"map.heatmap\"]}",
				this.token);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body())
			.isEqualTo("{\"flags\":{\"map.heatmap\":true,\"notes.drop\":false,\"nobody.set.this\":false}}");
	}

	@Test
	void noKeysIsAnEmptyMapNotAnError() throws Exception {
		var response = post("/v1/app/flags", "{\"keys\":[]}", this.token);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"flags\":{}}");
	}

	/** 形狀不對（缺 keys、不是陣列、元素不是字串、key 字元集或數量越界）＝400 沒有 {@code code}。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void malformedFlagsRequestIs400WithoutACode(String label, String body) throws Exception {
		var response = post("/v1/app/flags", body, this.token);

		assertProblemWithoutCode(response, 400);
	}

	static Stream<Arguments> malformedFlagsRequestIs400WithoutACode() {
		String fiftyOne = IntStream.range(0, 51).mapToObj((i) -> "\"k" + i + "\"").collect(Collectors.joining(","));
		return Stream.of(Arguments.of("沒有 body", null), Arguments.of("空物件", "{}"),
				Arguments.of("keys 為 null", "{\"keys\":null}"), Arguments.of("keys 不是陣列", "{\"keys\":\"a\"}"),
				Arguments.of("元素不是字串", "{\"keys\":[1]}"), Arguments.of("元素為 null", "{\"keys\":[null]}"),
				Arguments.of("空字串 key", "{\"keys\":[\"\"]}"), Arguments.of("key 含空白", "{\"keys\":[\"a b\"]}"),
				Arguments.of("key 65 字元", "{\"keys\":[\"" + "k".repeat(65) + "\"]}"),
				Arguments.of("51 個 key", "{\"keys\":[" + fiftyOne + "]}"), Arguments.of("非法 JSON", "{"));
	}

	// ── 共同 ───────────────────────────────────────────────────────────────────

	/**
	 * App 層設定不需要登入：App 一啟動（還沒匿名登入、或手上的 token 已過期）就要能問。
	 * 壞 token 也一樣——permitAll 擋不住 resource server 對壞 token 的 401，所以是整個忽略 header。
	 */
	@Test
	void bothEndpointsWorkWithoutOrWithAStaleToken() throws Exception {
		assertThat(get("/v1/app/update", null).statusCode()).isEqualTo(200);
		assertThat(post("/v1/app/flags", "{\"keys\":[]}", null).statusCode()).isEqualTo(200);
		assertThat(get("/v1/app/update", "not.a.token").statusCode()).isEqualTo(200);
		assertThat(post("/v1/app/flags", "{\"keys\":[]}", "not.a.token").statusCode()).isEqualTo(200);
		// 業務端點不受影響：忽略 header 只限 /v1/app/**
		assertThat(get("/v1/me", "not.a.token").statusCode()).isEqualTo(401);
	}

	private static void versions(String minimum, String latest) {
		admin().sql("update hapeetrail_private.app_versions set minimum_version = ?, latest_version = ?"
				+ " where platform = 'ios'").params(minimum, latest).update();
	}

	private static void flag(String key, boolean enabled) {
		admin().sql("insert into hapeetrail_private.feature_flags (key, enabled) values (?, ?)"
				+ " on conflict (key) do update set enabled = excluded.enabled").params(key, enabled).update();
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).GET(), token);
	}

	private HttpResponse<String> post(String path, String body, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Content-Type", "application/json")
			.POST((body != null) ? BodyPublishers.ofString(body) : BodyPublishers.noBody());
		return send(request, token);
	}

	private static HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString());
	}

	private static void assertProblemWithoutCode(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"status\":" + status).doesNotContain("\"code\"");
	}

}
