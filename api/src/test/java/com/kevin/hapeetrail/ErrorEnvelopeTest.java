package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票 14 M1：信封的最後一道。業務錯誤與框架錯誤各自有人接，但「沒人接的例外」原本會掉出
 * {@link ApiErrors} 之外，回 Spring 自己的 500 錯誤頁（{@code {"timestamp":…,"error":…}}）
 * ——那個形狀沒有 {@code type}、沒有 {@code code}，client 的「有 code 才是業務錯誤」在 500 上
 * 就不成立了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorEnvelopeTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@LocalServerPort
	int port;

	/**
	 * 只在本測試的 context 註冊的路徑：真的走一次 DispatcherServlet → {@code @RestControllerAdvice}，
	 * 才驗得到信封本身。用 mock 換掉服務只會驗到 mock。
	 */
	@TestConfiguration
	@RestController
	static class Boom {

		@GetMapping("/v1/boom")
		String boom() {
			throw new IllegalStateException("沒人接的例外");
		}

		/** 23514 ＝ check_violation：完整性錯誤，但**不是**身分問題。 */
		@GetMapping("/v1/boom/check-violation")
		String checkViolation() {
			throw new DataIntegrityViolationException("check", new SQLException("check", "23514"));
		}

	}

	@Test
	void unhandledExceptionsStayInsideTheEnvelope() throws Exception {
		var response = get("/v1/boom", "Bearer " + TestJwt.valid(UUID.randomUUID()));

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(500);
		assertThat(response.headers().firstValue("content-type").orElse(""))
			.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.path("type").asString()).isEqualTo("about:blank");
		assertThat(problem.path("status").asInt()).isEqualTo(500);
		// 500 不是業務錯誤：沒有 code，client 才不會把伺服器故障當成使用者輸入錯誤。
		assertThat(problem.has("code")).describedAs(response.body()).isFalse();
		// 框架的預設 body 會回述路徑與請求；我們的信封只有這四個鍵。
		assertThat(problem.has("timestamp")).isFalse();
		assertThat(problem.has("path")).isFalse();
	}

	/**
	 * FK 違反回 401 的那條路，靠的是 SQLSTATE 恰為 {@code 23503}。放寬成「任何完整性錯誤都是
	 * 401」，伺服器故障就會被講成身分問題——iOS 對 401 會去刷新 session，然後再撞一次。
	 */
	@Test
	void integrityErrorsThatAreNotForeignKeysStay500() throws Exception {
		var response = get("/v1/boom/check-violation", "Bearer " + TestJwt.valid(UUID.randomUUID()));

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(500);
		assertThat(JSON.readTree(response.body()).has("code")).describedAs(response.body()).isFalse();
	}

	private HttpResponse<String> get(String path, String authorization) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Authorization", authorization);
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString());
	}

}
