package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T36 請求體總量上限：Jackson 在**解析時**就擋下過大的 body（256 KiB）與過多的 token（10,000），
 * 不是整份解析進記憶體之後才由業務檢查擋。
 *
 * <p>怎麼證明是解析階段擋的：主要的案例都用 {@code PATCH /v1/me}——它把 body 解析成 Map、
 * 只看 {@code nickname} 鍵、**其他鍵一律忽略**。所以「一個合法暱稱＋大量填充」若被整份解析，
 * 業務層會回 200 並把暱稱存進去；只有解析階段的上限能讓它變成 400 無 code、且一列都沒寫。
 * 沒有這兩道上限時，這些案例都是 200（紅燈即是證據）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestLimitsTest extends SupabaseDbTest {

	private static final int MAX_DOCUMENT_BYTES = 262_144;

	@LocalServerPort
	int port;

	/** 20,000 個未知鍵 ≈ 40,000 個 token，但 body 只有約 220 KB（< 256 KiB）——擋它的只能是 token 上限。 */
	@Test
	void tooManyTokensAreRejectedWhileParsing() throws Exception {
		UUID me = UUID.randomUUID();
		String body = "{" + IntStream.range(0, 20_000).mapToObj((i) -> "\"k" + i + "\":0").collect(Collectors.joining(","))
				+ ",\"nickname\":\"Kevin\"}";
		assertThat(utf8(body)).isLessThan(MAX_DOCUMENT_BYTES);

		assertRejectedWithoutStoring(me, patch(signIn(me), body));
	}

	/** 只有 4 個 token、每個字串都短——300 KiB 的是 token 之間的空白，擋它的只能是文件長度上限。 */
	@Test
	void tooLongDocumentsAreRejectedWhileParsing() throws Exception {
		UUID me = UUID.randomUUID();

		assertRejectedWithoutStoring(me, patch(signIn(me), padded(300 * 1024)));
	}

	/** 邊界：剛好 256 KiB 的合法請求 200；多 1 byte 就 400 無 code（單位是 UTF-8 bytes）。 */
	@Test
	void theDocumentLimitIsExactly256KiB() throws Exception {
		UUID me = UUID.randomUUID();
		String token = signIn(me);

		var atTheLimit = patch(token, padded(MAX_DOCUMENT_BYTES));
		assertThat(atTheLimit.statusCode()).describedAs(atTheLimit.body()).isEqualTo(200);
		assertThat(atTheLimit.body()).contains("\"nickname\":\"Kevin\"");

		assertProblemWithoutCode(patch(token, padded(MAX_DOCUMENT_BYTES + 1)));
	}

	/** 屬性名超過 256 個字元：未知鍵本來會被忽略（200），現在解析時就 400。 */
	@Test
	void overlongPropertyNamesAreRejectedWhileParsing() throws Exception {
		UUID me = UUID.randomUUID();

		assertRejectedWithoutStoring(me,
				patch(signIn(me), "{\"" + "k".repeat(300) + "\":0,\"nickname\":\"Kevin\"}"));
	}

	/**
	 * 最大的合法便條：65,535 個全形空白（U+3000，UTF-8 每個 3 bytes）＋「a」≈ 192 KiB，
	 * 是字串上限內 bytes 最多的寫法，仍要 200；trim 後存的是「a」。
	 */
	@Test
	void theLargestLegalNoteStillFits() throws Exception {
		String body = "{\"content\":\"" + "　".repeat(65_535)
				+ "a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}";
		assertThat(utf8(body)).isGreaterThan(190 * 1024).isLessThan(MAX_DOCUMENT_BYTES);

		var response = send(request("/v1/notes").POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
				signIn(UUID.randomUUID()));

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).contains("\"content\":\"a\"");
	}

	/**
	 * T36 的原始情境：{@code keys} 塞 100,000 個短字串（約 1 MB）→ 400 無 code。
	 * 修之前也是 400（整份解析完才被 MAX_KEYS 擋）——這條是回歸護欄，證明解析階段的是上面幾條。
	 */
	@Test
	void hugeFlagsArraysAre400WithoutACode() throws Exception {
		String body = "{\"keys\":["
				+ IntStream.range(0, 100_000).mapToObj((i) -> "\"f%06d\"".formatted(i)).collect(Collectors.joining(",")) + "]}";
		assertThat(utf8(body)).isGreaterThan(999_000);

		assertProblemWithoutCode(
				send(request("/v1/app/flags").POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8)), null));
	}

	/** {@code {"nickname":"Kevin"}} 在結尾的 {@code }} 前補空白，補到整份剛好 {@code bytes} 個 UTF-8 bytes。 */
	private static String padded(int bytes) {
		String head = "{\"nickname\":\"Kevin\"";
		return head + " ".repeat(bytes - head.length() - 1) + "}";
	}

	private static int utf8(String body) {
		return body.getBytes(StandardCharsets.UTF_8).length;
	}

	private static void assertRejectedWithoutStoring(UUID user, HttpResponse<String> response) {
		assertProblemWithoutCode(response);
		assertThat(admin().sql("select count(*) from hapeetrail_private.profiles where user_id = ?")
			.param(user)
			.query(Long.class)
			.single()).isZero();
	}

	private static void assertProblemWithoutCode(HttpResponse<String> response) {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"status\":400").doesNotContain("\"code\"");
	}

	private HttpResponse<String> patch(String token, String body) throws Exception {
		return send(request("/v1/me").method("PATCH", BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
				token);
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Content-Type", "application/json");
	}

	private static HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

}
