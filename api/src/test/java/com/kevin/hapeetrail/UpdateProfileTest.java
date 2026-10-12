package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T34 更新暱稱 {@code PATCH /v1/me}：自訂暱稱存在 {@code hapeetrail_private.profiles}，
 * 優先於登入提供者給的名字；{@code null} 清除後回到提供者的名字（訪客回 null）。
 * trim 字元集與便條內容相同、先 trim 再數 code point；上限 20。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpdateProfileTest extends SupabaseDbTest {

	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","status":401,"title":"not_authenticated","code":"not_authenticated"}""";

	private static final String NICKNAME_EMPTY = """
			{"type":"about:blank","status":400,"title":"nickname_empty","code":"nickname_empty"}""";

	private static final String NICKNAME_TOO_LONG = """
			{"type":"about:blank","status":400,"title":"nickname_too_long","code":"nickname_too_long",\
			"details":{"maxChars":20}}""";

	@LocalServerPort
	int port;

	/** 回傳的是 trim 後的版本、五鍵同 GET；再 GET 一次逐字相同（兩條路同一句 SQL）。 */
	@Test
	void setsTheTrimmedNicknameAndGetAgrees() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);

		var response = patch(token, "{\"nickname\":\"  Kevin  \"}");

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(("{\"id\":\"%s\",\"nickname\":\"Kevin\","
				+ "\"loginMethod\":\"anonymous\",\"lastLoginAt\":null,\"memberLevel\":\"free\"}").formatted(me));
		assertThat(get(token).body()).isEqualTo(response.body());
	}

	/**
	 * 字元集是契約 §4 那一組（White_Space ＋ U+001C–U+001F），不是 {@code String.strip}：
	 * 全形空白 U+3000 與 U+001F 都剝；中間的空白是暱稱的一部分。
	 */
	@Test
	void trimsTheSameCharactersAsNoteContent() throws Exception {
		String token = signIn(UUID.randomUUID());

		var response = patch(token, "{\"nickname\":\"\\u3000\\u00a0Kevin Chen\\u001f\\n\"}");

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).contains("\"nickname\":\"Kevin Chen\",");
	}

	/** 自訂暱稱優先於 Google identity 的 full_name；清除後回到 full_name。 */
	@Test
	void theOwnNicknameOutranksTheProviderAndNullClearsIt() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		identity(me, "google", "{\"full_name\":\"Kevin Chen\"}");

		assertThat(patch(token, "{\"nickname\":\"小凱\"}").body()).contains("\"nickname\":\"小凱\",");
		assertThat(get(token).body()).contains("\"nickname\":\"小凱\",\"loginMethod\":\"google\"");

		var cleared = patch(token, "{\"nickname\":null}");

		assertThat(cleared.statusCode()).describedAs(cleared.body()).isEqualTo(200);
		assertThat(cleared.body()).contains("\"nickname\":\"Kevin Chen\",");
		assertThat(get(token).body()).isEqualTo(cleared.body());
		assertThat(profileRows(me)).isZero();
	}

	/**
	 * 三個來源同時都在時釘住 coalesce 的完整順序：自訂暱稱 > user_metadata > identity。
	 * 清除後回到的是 user_metadata，不是跳過它直接落到 identity。
	 */
	@Test
	void theOwnNicknameOutranksUserMetadataAndClearingFallsBackToIt() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		identity(me, "google", "{\"full_name\":\"Google Says\"}");
		admin().sql("update auth.users set raw_user_meta_data = '{\"full_name\":\"Metadata Says\"}'::jsonb where id = ?")
			.param(me)
			.update();
		assertThat(get(token).body()).describedAs("前提：沒自訂時 metadata 勝過 identity")
			.contains("\"nickname\":\"Metadata Says\",");

		assertThat(patch(token, "{\"nickname\":\"I Say\"}").body()).contains("\"nickname\":\"I Say\",");
		assertThat(get(token).body()).contains("\"nickname\":\"I Say\",");

		var cleared = patch(token, "{\"nickname\":null}");

		assertThat(cleared.body()).contains("\"nickname\":\"Metadata Says\",");
		assertThat(get(token).body()).isEqualTo(cleared.body());
	}

	/** 訪客清除後回 null；沒設過也照樣 200（清除是冪等的）。 */
	@Test
	void aGuestClearsBackToNull() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);

		assertThat(patch(token, "{\"nickname\":null}").body()).contains("\"nickname\":null,");
		patch(token, "{\"nickname\":\"Kevin\"}");

		var cleared = patch(token, "{\"nickname\":null}");

		assertThat(cleared.statusCode()).isEqualTo(200);
		assertThat(cleared.body()).isEqualTo(("{\"id\":\"%s\",\"nickname\":null,"
				+ "\"loginMethod\":\"anonymous\",\"lastLoginAt\":null,\"memberLevel\":\"free\"}").formatted(me));
	}

	/** 第二次更新覆蓋第一次，表裡始終一列。 */
	@Test
	void updatingTwiceOverwrites() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);

		patch(token, "{\"nickname\":\"First\"}");
		var response = patch(token, "{\"nickname\":\"Second\"}");

		assertThat(response.body()).contains("\"nickname\":\"Second\",");
		assertThat(get(token).body()).contains("\"nickname\":\"Second\",");
		assertThat(profileRows(me)).isEqualTo(1);
	}

	/** trim 後為空（含只有 NBSP）＝ nickname_empty，有 code。 */
	@ParameterizedTest
	@ValueSource(strings = { "\"\"", "\"   \"", "\"\\u00a0\"", "\"\\n\\t\\u3000\"" })
	void blankIsNicknameEmpty(String value) throws Exception {
		var response = patch(signIn(UUID.randomUUID()), "{\"nickname\":" + value + "}");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).isEqualTo(NICKNAME_EMPTY);
	}

	/** 21 個 code point（emoji 算 1，UTF-16 是 2）＝太長；恰 20 ＝可以；頭尾空白不算。 */
	@Test
	void twentyCodePointsIsTheLimit() throws Exception {
		String token = signIn(UUID.randomUUID());
		String twenty = "😀".repeat(2) + "a".repeat(18);

		var tooLong = patch(token, "{\"nickname\":\"" + twenty + "b\"}");
		assertThat(tooLong.statusCode()).isEqualTo(400);
		assertThat(tooLong.body()).isEqualTo(NICKNAME_TOO_LONG);

		var exactly = patch(token, "{\"nickname\":\"  " + twenty + "  \"}");
		assertThat(exactly.statusCode()).describedAs(exactly.body()).isEqualTo(200);
		assertThat(exactly.body()).contains("\"nickname\":\"" + twenty + "\",");
	}

	/** 「有 code 才是業務錯誤」：缺鍵、型別不對、格式錯誤都是 400 沒有 code，而且什麼都不寫。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void malformedBodiesAre400WithoutACode(String variant, String body) throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);

		var response = patch(token, body);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"status\":400").doesNotContain("\"code\"");
		assertThat(profileRows(me)).isZero();
	}

	static Stream<Arguments> malformedBodiesAre400WithoutACode() {
		return Stream.of(Arguments.of("沒有 body", null), Arguments.of("空 body", ""),
				Arguments.of("非法 JSON", "{"), Arguments.of("缺 nickname 鍵", "{}"),
				Arguments.of("body 是 null", "null"), Arguments.of("body 是陣列", "[]"),
				Arguments.of("body 是字串", "\"Kevin\""),
				Arguments.of("nickname 給數字", "{\"nickname\":123}"),
				Arguments.of("nickname 給小數", "{\"nickname\":1.5}"),
				Arguments.of("nickname 給布林", "{\"nickname\":true}"),
				Arguments.of("nickname 給物件", "{\"nickname\":{}}"),
				Arguments.of("nickname 給陣列", "{\"nickname\":[\"a\"]}"),
				// T35：原始字串（trim 前）超過 65,536 字元在解析層就擋下，不是 nickname_too_long。
				Arguments.of("nickname 原始長度超過 65,536 字元",
						"{\"nickname\":\"" + "a".repeat(70_000) + "\"}"),
				// 與便條 content 同一桶（NoteService 的理由）：Postgres 的 text 存不下 U+0000，
				// 孤立代理對轉 UTF-8 會被靜默換掉——格式錯誤，不新增契約 token。
				Arguments.of("nickname 含 U+0000", "{\"nickname\":\"a\\u0000b\"}"),
				Arguments.of("nickname 含孤立高代理", "{\"nickname\":\"a\\ud800b\"}"),
				Arguments.of("nickname 含孤立低代理", "{\"nickname\":\"a\\udc00b\"}"));
	}

	@Test
	void needsAToken() throws Exception {
		assertThat(patch(null, "{\"nickname\":\"Kevin\"}").body()).isEqualTo(NOT_AUTHENTICATED);
	}

	private static long profileRows(UUID user) {
		return admin().sql("select count(*) from hapeetrail_private.profiles where user_id = ?")
			.param(user)
			.query(Long.class)
			.single();
	}

	private static void identity(UUID user, String provider, String data) {
		admin().sql("insert into auth.identities (user_id, provider, identity_data, created_at) values (?, ?, ?::jsonb, ?)")
			.params(user, provider, data, OffsetDateTime.parse("2026-09-30T00:00:00Z"))
			.update();
	}

	private HttpResponse<String> patch(String token, String body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/v1/me"))
			.header("Content-Type", "application/json")
			.method("PATCH", (body != null) ? BodyPublishers.ofString(body, StandardCharsets.UTF_8)
					: BodyPublishers.noBody());
		return send(request, token);
	}

	private HttpResponse<String> get(String token) throws Exception {
		return send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/v1/me")).GET(), token);
	}

	private static HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

}
