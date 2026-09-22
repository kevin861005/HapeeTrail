package com.kevin.hapeetrail.account;

import java.io.IOException;

import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 註銷（CONTEXT.md〈註銷〉）：經 GoTrue Admin API 硬刪呼叫者的 {@code auth.users} 列，
 * FK cascade 帶走 sessions、identities 與他寫下的全部便條（他撿過的別人便條只是
 * {@code picked_up_by} 變 null，不回地圖）。立即生效、無反悔期。
 *
 * <p>不直下 SQL（T28 grilling Q4）：auth schema 是 Supabase 的私有實作，用廠商的公開契約管
 * 廠商的資料，GoTrue 的 audit log 也因此留有刪帳紀錄。服務連 DB 的角色仍然碰不到 {@code auth.users}。
 *
 * <p>刪誰只來自已驗過的 token 的 {@code sub}——沒有 body、沒有 path 參數，刪不到別人。
 * 刪完 session 列跟著消失，所以重試在 session 檢查（ADR-0013）就 401，進不到這裡。
 */
@RestController
class AccountController {

	/** GoTrue 省略 body 也是硬刪，但軟刪會留下 users 列、便條不 cascade——這個值不交給預設。 */
	private static final String HARD_DELETE = "{\"should_soft_delete\":false}";

	private final RestClient gotrue;

	// ponytail: 沒設 read timeout——GoTrue 卡住時由平台的請求逾時封頂（Cloud Run 預設 300s）；
	// 註銷是低頻操作。真遇到再給 builder 一個 JdkClientHttpRequestFactory#setReadTimeout。
	AccountController(@Value("${hapeetrail.gotrue.url}") String url,
			@Value("${hapeetrail.gotrue.secret-key}") String secretKey) {
		this.gotrue = RestClient.builder()
			.baseUrl(url)
			// secret key 只放 apikey（官方正路）：hosted gateway 換成 service_role 的短效 JWT 再轉給 GoTrue。
			// Authorization 刻意不帶，尤其不轉送旅人自己的 Bearer（T28 研究 Q2.2、Q3.2）。
			.defaultHeader("apikey", secretKey)
			.build();
	}

	@DeleteMapping("/v1/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt) {
		try {
			call(jwt.getSubject());
		}
		catch (ResourceAccessException ex) {
			// 連不上 GoTrue（斷線、掛斷、逾時）：RestClient 的訊息帶著完整 admin URL，也就是使用者 id，
			// 而 catch-all 會把例外寫進 ERROR。換掉外層、只留 I/O 根因（JDK HttpClient 的訊息不含 URL）。
			throw new IllegalStateException("GoTrue admin 連線失敗", ex.getCause());
		}
	}

	private void call(String user) {
		this.gotrue.method(HttpMethod.DELETE)
			.uri("/admin/users/{id}", user)
			.contentType(MediaType.APPLICATION_JSON)
			.body(HARD_DELETE)
			.exchange((request, response) -> {
				if (response.getStatusCode().is2xxSuccessful() || alreadyGone(response)) {
					return null;
				}
				// 其餘一律是伺服器故障（走 ApiErrors 的 catch-all → 500、沒有 code）：尤其 GoTrue 的
				// 401／403 是**服務的**金鑰有問題，照轉成 401 會讓 iOS 去刷新旅人的 session。
				// 訊息只帶狀態碼與 GoTrue 的錯誤碼：它會進 ERROR 日誌，金鑰與使用者 id 都不能在裡面。
				throw new IllegalStateException("GoTrue admin 刪除失敗：HTTP %d %s".formatted(
						response.getStatusCode().value(), response.getHeaders().getFirst("X-Sb-Error-Code")));
			});
	}

	/**
	 * 使用者已不在 ＝ 目標狀態已達成（併發的另一個註銷先到）。只認 {@code user_not_found}：
	 * id 不是 UUID（{@code validation_failed}）與網址設錯（{@code text/plain} 的 404）也是 404，
	 * 只看狀態碼會把設定錯誤靜默當成功——帳號還活著，旅人卻收到 204（T28 研究「影響」第 4 點）。
	 * 看 body 不看 {@code X-Sb-Error-Code} header：header 能否穿過 hosted gateway 未能確認，body 必出自 GoTrue。
	 */
	private static boolean alreadyGone(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
			throws IOException {
		return response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
				&& MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType())
				&& "user_not_found".equals(response.bodyTo(JsonNode.class).path("error_code").asString());
	}

}
