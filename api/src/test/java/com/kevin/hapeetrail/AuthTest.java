package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
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
 * 以 hapeetrail_api 下 SQL → 過 RLS → 回 v4 envelope。以及它的反面：壞 token 全 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthTest extends SupabaseDbTest {

	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","status":401,"title":"not_authenticated","code":"not_authenticated"}""";

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
	 * 每一種壞 token 都是同一個答案——iOS 因此不必比對 body 就知道「session 有問題、走刷新流程」。
	 * 這張表就是 fail-closed 的清單：新增一種驗證，就在這裡多一列。
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
				Arguments.of("aud 不符", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "anon", later)),
				// sub 有值但不是 UUID：沒有 UUID 就沒有使用者身分，與缺 sub 同一件事。
				// 不擋就會一路帶到 controller 的 UUID.fromString——那裡炸出來的是 500，
				// 而且例外訊息會把 sub 原值寫進 ERROR 日誌。
				Arguments.of("sub 非 UUID",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, "not-a-uuid", "authenticated", later)),
				// 沒有 exp 的 token 就是永不過期的 token：Spring 的 JwtTimestampValidator 對
				// **缺席**的 exp 不失敗，只驗有值時的大小。契約說過期一律 401，那前提是有 exp。
				Arguments.of("無 exp", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "authenticated", null)),
				// aud=authenticated 是**每個** Supabase 專案的共同值，擋跨專案 token 的原本只有簽章。
				// iss 是零成本的第二道；缺 iss 與 iss 不符同樣不是我們的 token。
				Arguments.of("iss 不符", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "authenticated",
						later, "https://evil.example/auth/v1")),
				Arguments.of("無 iss",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "authenticated", later, null)),
				// UUID.fromString 對區段長度是寬鬆的：1-1-1-1-1 會被補成
				// 00000001-0001-0001-0001-000000000001，於是兩個不同的字串別名成同一個使用者。
				// GoTrue 只發標準形式，所以「解析得出來」不夠，要「解析回去還是同一個字串」。
				Arguments.of("sub 是縮寫 UUID",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, "1-1-1-1-1", "authenticated", later)),
				Arguments.of("sub 帶正號",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, "+1-1-1-1-1", "authenticated", later)));
	}

	/**
	 * 簽得過、sub 也是 UUID，但那個使用者已經不在了（帳號刪除、token 尚未過期）。
	 * {@code notes} 上只有 {@code author_id}／{@code picked_up_by} 兩支 FK、都指向
	 * {@code auth.users}，所以 FK 違反的唯一語意就是「呼叫者的身分已不存在」＝ 401，
	 * 不是 500——iOS 對 401 走刷新流程，對 500 只會一直重試。
	 */
	@Test
	void tokenForADeletedUserIs401() throws Exception {
		var response = post("/v1/notes", "Bearer " + TestJwt.valid(UUID.randomUUID()),
				"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}");

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(401);
		assertThat(response.headers().firstValue("content-type").orElse(""))
			.startsWith("application/problem+json");
		assertThat(response.body()).isEqualTo(NOT_AUTHENTICATED);
	}

	/** 除了 health 之外沒有任何路徑是免 token 的——連不存在的路徑都不先告訴你它不存在。 */
	@Test
	void everythingElseNeedsAToken() throws Exception {
		assertThat(get("/v1/me/collection", null).statusCode()).isEqualTo(401);
		assertThat(get("/v1/notes", null).statusCode()).isEqualTo(401);
		assertThat(get("/nope", null).statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> post(String path, String authorization, String body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Content-Type", "application/json")
			.header("Authorization", authorization)
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> get(String path, String authorization) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString());
	}

}
