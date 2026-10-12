package com.kevin.hapeetrail;

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

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
