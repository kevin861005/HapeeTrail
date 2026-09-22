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
		var response = get("/v1/me/notes", "Bearer " + signIn(UUID.randomUUID()));

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
		// 每一列只有一個毛病，其餘都是一個真的在線的 session：否則 session 檢查會替別的檢查
		// 把關，拿掉任何一條驗證都不會有列轉紅。
		UUID user = UUID.randomUUID();
		String subject = user.toString();
		String session = openSession(user).toString();
		Instant later = Instant.now().plusSeconds(3600);
		return Stream.of(
				Arguments.of("無 header", null),
				Arguments.of("簽章不符",
						"Bearer " + TestJwt.token(TestJwt.FOREIGN_KEY, subject, session, "authenticated", later)),
				// 過期要遠遠超過 JwtTimestampValidator 預設容許的 60 秒時鐘偏移，
				// 剛好 -60s 會落在邊界上，紅綠只差在鑄 token 到送出之間的那幾毫秒。
				Arguments.of("過期", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, session, "authenticated",
						Instant.now().minusSeconds(3600))),
				Arguments.of("缺 sub",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, null, session, "authenticated", later)),
				Arguments.of("aud 不符",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, session, "anon", later)),
				// sub 有值但不是 UUID：沒有 UUID 就沒有使用者身分，與缺 sub 同一件事。
				// 不擋就會一路帶到 controller 的 UUID.fromString——那裡炸出來的是 500，
				// 而且例外訊息會把 sub 原值寫進 ERROR 日誌。
				Arguments.of("sub 非 UUID",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, "not-a-uuid", session, "authenticated", later)),
				// 沒有 exp 的 token 就是永不過期的 token：Spring 的 JwtTimestampValidator 對
				// **缺席**的 exp 不失敗，只驗有值時的大小。契約說過期一律 401，那前提是有 exp。
				Arguments.of("無 exp",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, session, "authenticated", null)),
				// aud=authenticated 是**每個** Supabase 專案的共同值，擋跨專案 token 的原本只有簽章。
				// iss 是零成本的第二道；缺 iss 與 iss 不符同樣不是我們的 token。
				Arguments.of("iss 不符", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, session,
						"authenticated", later, "https://evil.example/auth/v1")),
				Arguments.of("無 iss", "Bearer "
						+ TestJwt.token(TestJwt.SIGNING_KEY, subject, session, "authenticated", later, null)),
				// UUID.fromString 對區段長度是寬鬆的：1-1-1-1-1 會被補成
				// 00000001-0001-0001-0001-000000000001，於是兩個不同的字串別名成同一個使用者。
				// GoTrue 只發標準形式，所以「解析得出來」不夠，要「解析回去還是同一個字串」。
				Arguments.of("sub 是縮寫 UUID",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, "1-1-1-1-1", session, "authenticated", later)),
				Arguments.of("sub 帶正號",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, "+1-1-1-1-1", session, "authenticated", later)),
				// ─── session 存活（ADR-0013）：簽得過不等於還登入著 ───
				// GoTrue 發給使用者的 token 必定帶 session_id；沒有就不是使用者 token。
				Arguments.of("無 session_id",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, null, "authenticated", later)),
				Arguments.of("session_id 非 UUID",
						"Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject, "not-a-uuid", "authenticated", later)),
				// GoTrue 自己把 nil UUID 當「沒有 session」放行（給 service_role 那類 token 用）；
				// 本服務只收使用者 token，照抄那條例外就是開一扇不必查 session 的門。
				Arguments.of("session_id 是 nil UUID", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject,
						"00000000-0000-0000-0000-000000000000", "authenticated", later)),
				Arguments.of("查無此 session", "Bearer " + TestJwt.token(TestJwt.SIGNING_KEY, subject,
						UUID.randomUUID().toString(), "authenticated", later)),
				Arguments.of("已登出", "Bearer " + loggedOut()),
				// 舊版這裡是 200：簽章對、sub 是 UUID，服務就放行一個不存在的使用者。
				Arguments.of("使用者不存在（帳號已刪除）", "Bearer " + deleted()));
	}

	/** 登出＝GoTrue 刪掉 session 列（global scope 的那句 SQL），token 本身還沒過期。 */
	private static String loggedOut() {
		UUID user = UUID.randomUUID();
		String token = signIn(user);
		admin().sql("delete from auth.sessions where user_id = ?").param(user).update();
		return token;
	}

	/** 帳號刪掉，session 跟著 FK cascade 消失——與註銷的 admin 硬刪同一件事。 */
	private static String deleted() {
		UUID user = UUID.randomUUID();
		String token = signIn(user);
		admin().sql("delete from auth.users where id = ?").param(user).update();
		return token;
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
