package com.kevin.hapeetrail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.Filter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T37 非 JSON 的 body：API 只收 JSON，表單與 multipart 都不該在驗證身分之前被讀進來。
 *
 * <p>只看狀態碼證明不了什麼——修前修後都是 401／415。所以掛一個排在**所有** filter 之前的探針
 * （{@link BodyProbe}），把 request 包一層：數 {@code getInputStream()} 被讀走多少 bytes、記
 * {@code getParts()} 有沒有被呼叫。Spring 的 {@code FormContentFilter}（order -9900，早於 Security 的
 * -100）讀表單走的正是 {@code getInputStream()}；multipart resolver 解析走的正是 {@code getParts()}。
 * 修之前，匿名的 1 MB 表單會被讀完（探針數到約 1,000,009 bytes），multipart 會被解析成 Part。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		// T38 的觀測點：把 Tomcat 的表單上限從 2MB 調到 64KB，1 MB 的 POST 表單就會撞上限（見下方測試）。
		// 本類別本來就因 @Import 自成一個 context，加這行不多起 context。
		properties = "server.tomcat.max-http-form-post-size=64KB")
@Import(NonJsonBodyTest.BodyProbe.class)
class NonJsonBodyTest extends SupabaseDbTest {

	private static final String FORM = "application/x-www-form-urlencoded";

	/** 一百萬個 a 的暱稱：約 1 MB 的表單。 */
	private static final String HUGE_FORM = "nickname=" + "a".repeat(1_000_000);

	@LocalServerPort
	int port;

	@BeforeEach
	void resetTheProbe() {
		BodyProbe.BYTES_READ.set(0);
		BodyProbe.PARTS_PARSED.set(false);
	}

	/** 匿名、1 MB 表單 PATCH：Security 先回 401，在那之前一個 byte 都沒有被讀。 */
	@Test
	void anonymousFormBodiesAreNotReadBeforeAuthentication() throws Exception {
		var response = send(request("/v1/me", FORM).method("PATCH", BodyPublishers.ofString(HUGE_FORM)), null);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(401);
		assertThat(BodyProbe.BYTES_READ.get()).describedAs("驗證身分之前被讀走的 bytes").isZero();
	}

	/** 帶有效 token 的表單：415 沒有 code、什麼都沒寫；body 也沒被整份讀進來。 */
	@Test
	void authenticatedFormBodiesAre415WithoutACode() throws Exception {
		UUID me = UUID.randomUUID();

		var response = send(request("/v1/me", FORM).method("PATCH", BodyPublishers.ofString(HUGE_FORM)), signIn(me));

		assertProblemWithoutCode(response, 415);
		assertThat(BodyProbe.BYTES_READ.get()).isLessThan(8 * 1024);
		assertThat(admin().sql("select count(*) from hapeetrail_private.profiles where user_id = ?")
			.param(me)
			.query(Long.class)
			.single()).isZero();
	}

	/** 免登入的 {@code /v1/app/flags} 送 multipart：415 沒有 code，且沒有被解析成 Part（不落暫存檔）。 */
	@Test
	void multipartBodiesAre415WithoutACodeAndNeverParsed() throws Exception {
		String boundary = "t37boundary";
		String body = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"file\"; filename=\"x.bin\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n" + "a".repeat(64 * 1024) + "\r\n--" + boundary
				+ "--\r\n";

		var response = send(request("/v1/app/flags", "multipart/form-data; boundary=" + boundary)
			.POST(BodyPublishers.ofString(body)), null);

		assertProblemWithoutCode(response, 415);
		assertThat(BodyProbe.PARTS_PARSED.get()).describedAs("multipart 被解析成 Part").isFalse();
	}

	/**
	 * T38：POST 表單不再由 Tomcat 解析。探針看不到這件事——Tomcat 是在 Spring 呼叫
	 * {@code getParameterMap()} 時從自己的 coyote 緩衝讀 body，不經過任何 wrapper。可觀測的 Tomcat 層證據是
	 * {@code max-http-form-post-size}（本類別調成 64KB）：Tomcat 要解析時，1 MB 超過上限就丟
	 * {@code InvalidParameterException} → 走 catch-all 變 500 ＋ ERROR 堆疊；不解析時根本碰不到上限，
	 * 照常是 415 沒有 code、沒有 ERROR。上限只是觀測用的刻度，任何大小（含正式環境 2MB 以上）走的都是同一條路。
	 *
	 * <p>不直接用預設上限送 3 MB：Tomcat 回應後只肯吞掉 2MB（max-swallow-size）未讀的 body 就斷線，
	 * client 讀不到狀態碼——修前修後都一樣，證明不了什麼。
	 */
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void postFormBodiesOverTheTomcatFormLimitAre415WithoutAnErrorLog(CapturedOutput output) throws Exception {
		String overTheFormLimit = "keys=" + "a".repeat(1_000_000);

		var response = send(request("/v1/app/flags", FORM).POST(BodyPublishers.ofString(overTheFormLimit)), null);

		assertProblemWithoutCode(response, 415);
		assertThat(output.getAll()).doesNotContain("InvalidParameterException").doesNotContain(" ERROR ");
	}

	/**
	 * 同上，換成 chunked（沒有 Content-Length）：Tomcat 解析表單時走另一條讀法（readChunkedPostBody），
	 * 一樣受 64KB 上限管；修前 500 ＋ ERROR，修後 415 沒有 code、沒有 ERROR。
	 */
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void chunkedPostFormBodiesAre415WithoutAnErrorLog(CapturedOutput output) throws Exception {
		byte[] overTheFormLimit = ("keys=" + "a".repeat(1_000_000)).getBytes(StandardCharsets.US_ASCII);

		var response = send(request("/v1/app/flags", FORM)
			.POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(overTheFormLimit))), null);

		assertProblemWithoutCode(response, 415);
		assertThat(output.getAll()).doesNotContain("InvalidParameterException").doesNotContain(" ERROR ");
	}

	/**
	 * parseBodyMethods 只管 body：GET 端點的 {@code @RequestParam} 走 query string，照常被讀到。
	 * 用非整數的 limit 才有鑑別力——參數被讀到是 400 沒有 code，被忽略則會是 200（預設 50）。
	 */
	@Test
	void queryStringParametersAreStillRead() throws Exception {
		var response = send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/v1/me/notes?limit=abc")),
				signIn(UUID.randomUUID()));

		assertProblemWithoutCode(response, 400);
	}

	private static void assertProblemWithoutCode(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"status\":" + status).doesNotContain("\"code\"");
	}

	private HttpRequest.Builder request(String path, String contentType) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Content-Type", contentType);
	}

	private static HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** 排在所有 filter 之前，觀察下游（含框架的 filter）對 body 做了什麼。 */
	@TestConfiguration(proxyBeanMethods = false)
	static class BodyProbe {

		static final AtomicLong BYTES_READ = new AtomicLong();

		static final AtomicBoolean PARTS_PARSED = new AtomicBoolean();

		@Bean
		FilterRegistrationBean<Filter> bodyProbe() {
			Filter probe = (request, response, chain) -> chain
				.doFilter(new Probed((HttpServletRequest) request), response);
			FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(probe);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}

	}

	private static final class Probed extends HttpServletRequestWrapper {

		Probed(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			ServletInputStream in = super.getInputStream();
			return new ServletInputStream() {

				@Override
				public int read() throws IOException {
					int b = in.read();
					if (b != -1) {
						BodyProbe.BYTES_READ.incrementAndGet();
					}
					return b;
				}

				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					int n = in.read(buffer, offset, length);
					if (n > 0) {
						BodyProbe.BYTES_READ.addAndGet(n);
					}
					return n;
				}

				@Override
				public boolean isFinished() {
					return in.isFinished();
				}

				@Override
				public boolean isReady() {
					return in.isReady();
				}

				@Override
				public void setReadListener(ReadListener listener) {
					in.setReadListener(listener);
				}
			};
		}

		@Override
		public Collection<Part> getParts() throws IOException, ServletException {
			BodyProbe.PARTS_PARSED.set(true);
			return super.getParts();
		}

		@Override
		public Part getPart(String name) throws IOException, ServletException {
			BodyProbe.PARTS_PARSED.set(true);
			return super.getPart(name);
		}

	}

}
