package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T30 基本資料 {@code GET /v1/me}：六個鍵、鍵序固定、缺值是 null 不是缺鍵。
 * 來源是 {@code hapeetrail_private.auth_users}（auth.users ＋ 最早綁定的 identity）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileTest extends SupabaseDbTest {

	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","status":401,"title":"not_authenticated","code":"not_authenticated"}""";

	private static final String SIGNED_IN = "2026-10-01T02:03:04.123456+00:00";

	@LocalServerPort
	int port;

	/** 訪客：沒有綁定任何身分。GoTrue 仍會寫 last_sign_in_at，但契約上「沒登入過」就是 null。 */
	@Test
	void aGuestHasNothingButAnIdAndALevel() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		lastSignedIn(me);

		var response = get("/v1/me", token);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(("{\"id\":\"%s\",\"nickname\":null,\"avatarUrl\":null,"
				+ "\"loginMethod\":\"anonymous\",\"lastLoginAt\":null,\"memberLevel\":\"free\"}").formatted(me));
	}

	/** 綁了 Google：暱稱與頭像來自 identity_data（GoTrue 不回寫 raw_user_meta_data），時間戳六位小數。 */
	@Test
	void aGoogleTravelerShowsTheIdentitysNameAndPicture() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		lastSignedIn(me);
		identity(me, "google", "{\"full_name\":\"Kevin Chen\",\"name\":\"Kevin\","
				+ "\"avatar_url\":\"https://lh3.example/a.jpg\",\"picture\":\"https://lh3.example/p.jpg\","
				+ "\"email\":\"k@example.com\"}", "2026-09-30T00:00:00Z");

		var response = get("/v1/me", token);

		assertThat(response.body()).isEqualTo(("{\"id\":\"%s\",\"nickname\":\"Kevin Chen\","
				+ "\"avatarUrl\":\"https://lh3.example/a.jpg\",\"loginMethod\":\"google\","
				+ "\"lastLoginAt\":\"2026-10-01T02:03:04.123456Z\",\"memberLevel\":\"free\"}").formatted(me));
	}

	/** Apple 的 id_token 不帶姓名也沒有頭像：綁了但兩者都是 null，登入方式照樣是 apple。 */
	@Test
	void anAppleTravelerMayHaveNoNameAtAll() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		lastSignedIn(me);
		identity(me, "apple", "{\"email\":\"relay@privaterelay.appleid.com\",\"sub\":\"001234.abc\"}",
				"2026-09-30T00:00:00Z");

		var response = get("/v1/me", token);

		assertThat(response.body()).contains("\"nickname\":null,\"avatarUrl\":null,\"loginMethod\":\"apple\"");
	}

	/** 使用者自己在 GoTrue 設的 user_metadata 優先於 provider 給的；只有 name 沒有 full_name 也認。 */
	@Test
	void userMetadataOutranksTheIdentity() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		lastSignedIn(me);
		identity(me, "google", "{\"full_name\":\"Google Says\",\"picture\":\"https://g/p.jpg\"}", "2026-09-30T00:00:00Z");
		admin().sql("update auth.users set raw_user_meta_data = '{\"name\":\"I Say\"}'::jsonb where id = ?")
			.param(me).update();

		var response = get("/v1/me", token);

		assertThat(response.body()).contains("\"nickname\":\"I Say\",\"avatarUrl\":\"https://g/p.jpg\"");
	}

	/** 綁了兩個：取最早綁的那個（與 GoTrue 算 app_metadata.provider 的規則相同）。 */
	@Test
	void theFirstLinkedIdentityWins() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);
		lastSignedIn(me);
		identity(me, "apple", "{}", "2026-09-30T00:00:00Z");
		identity(me, "google", "{\"full_name\":\"Later\"}", "2026-09-29T00:00:00Z");

		var response = get("/v1/me", token);

		assertThat(response.body()).contains("\"nickname\":\"Later\",\"avatarUrl\":null,\"loginMethod\":\"google\"");
	}

	@Test
	void needsAToken() throws Exception {
		assertThat(get("/v1/me", null).body()).isEqualTo(NOT_AUTHENTICATED);
	}

	private static void lastSignedIn(UUID user) {
		admin().sql("update auth.users set last_sign_in_at = ? where id = ?")
			.params(OffsetDateTime.parse(SIGNED_IN), user).update();
	}

	private static void identity(UUID user, String provider, String data, String createdAt) {
		admin().sql("insert into auth.identities (user_id, provider, identity_data, created_at) values (?, ?, ?::jsonb, ?)")
			.params(user, provider, data, OffsetDateTime.parse(createdAt)).update();
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString());
	}

}
