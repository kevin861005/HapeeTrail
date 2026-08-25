package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第一顆穿透所有層的子彈：GoTrue 形狀的 token → Spring Security 驗簽 →
 * 以 hapeetrail_api 下 SQL → 過 RLS → 回 v4 envelope。以及它的反面：五種壞 token 全 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthTest extends SupabaseDbTest {

	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","title":"not_authenticated","status":401,"code":"not_authenticated"}""";

	@LocalServerPort
	int port;

	@Test
	void validTokenGetsAnEmptyPage() throws Exception {
		var response = get("/v1/me/notes", "Bearer " + TestJwt.valid(UUID.randomUUID()));

		assertThat(response.statusCode()).isEqualTo(200);
		// items 是空陣列不是 null；nextCursor 這個鍵必須在（null ＝ 沒有更多）。
		assertThat(response.body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");
	}

	/**
	 * 401 的五種變形同一個答案——iOS 因此不必比對 body 就知道「session 有問題、走刷新流程」。
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void badTokensAre401(String variant, String authorization) throws Exception {
		var response = get("/v1/me/notes", authorization);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("content-type").orElse(""))
			.startsWith("application/problem+json");
		assertThat(response.body()).isEqualTo(NOT_AUTHENTICATED);
	}

	static Stream<Arguments> badTokensAre401() {
		String subject = UUID.randomUUID().toString();
		Instant later = Instant.now().plusSeconds(3600);
		return Stream.of(
				Arguments.of("無 header", null),
				Arguments.of("簽章不符",
						"Bearer " + TestJwt.token(TestJwt.FOREIGN_KEY, subject, "authenticated", later)),
				// 過期要遠遠超過 JwtTimestampValidator 預設容許的 60 秒時鐘偏移，
				// 剛好 -60s 會落在邊界上，紅綠只差在鑄 token 到送出之間的那幾毫秒。
				Arguments.of("過期", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "authenticated",
						Instant.now().minusSeconds(3600))),
				Arguments.of("缺 sub", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, null, "authenticated", later)),
				Arguments.of("aud 不符", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "anon", later)));
	}

	/** 除了 health 之外沒有任何路徑是免 token 的——連不存在的路徑都不先告訴你它不存在。 */
	@Test
	void everythingElseNeedsAToken() throws Exception {
		assertThat(get("/v1/me/collection", null).statusCode()).isEqualTo(401);
		assertThat(get("/v1/notes", null).statusCode()).isEqualTo(401);
		assertThat(get("/nope", null).statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> get(String path, String authorization) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString());
	}

}
