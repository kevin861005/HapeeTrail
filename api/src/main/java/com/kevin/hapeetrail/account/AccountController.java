package com.kevin.hapeetrail.account;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import com.kevin.hapeetrail.config.ApiException;
import com.kevin.hapeetrail.config.Wire;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

	/**
	 * 基本資料（T30）。登入方式＝最早綁定的 identity 的 provider（GoTrue 算 app_metadata.provider
	 * 的同一規則），沒有 ＝ 匿名。暱稱／頭像：使用者自己在 GoTrue 設的 user metadata 優先，
	 * 其次是 provider 給的 identity_data（綁定不會回寫 user metadata）；Google 兩組鍵名並存，
	 * Apple 的 id_token 不帶姓名也沒有頭像。view 只投影這八個鍵，email 等 PII 不經過服務角色。
	 *
	 * <p>這兩個值是**使用者可控、未驗證**的字串（任何旅人都能經 Supabase 的 updateUser 改
	 * user_metadata）：只回給本人所以現在無害；哪天要顯示給別人，先驗長度與 URL scheme。
	 */
	private static final String PROFILE = """
			select coalesce(provider, 'anonymous') as login_method, last_sign_in_at,
			       coalesce(meta_full_name, meta_name, identity_full_name, identity_name) as nickname,
			       coalesce(meta_avatar_url, meta_picture, identity_avatar_url, identity_picture) as avatar_url
			  from hapeetrail_private.auth_users
			 where id = ?
			""";

	/** 會員等級預留：訂閱上線前所有人都是這一級。ponytail: 常數，有第二級時才建表。 */
	private static final String MEMBER_LEVEL = "free";

	private final RestClient gotrue;

	private final JdbcClient jdbc;

	AccountController(@Value("${hapeetrail.gotrue.url}") String url,
			@Value("${hapeetrail.gotrue.secret-key}") String secretKey,
			@Value("${hapeetrail.gotrue.timeout}") Duration timeout, JdbcClient jdbc) {
		this.jdbc = jdbc;
		// 連線與回應各自封頂：沒有逾時的話，GoTrue 卡住＝一條 Tomcat thread 被占到平台放棄
		// （Cloud Run 300s），而旅人收到的是平台的 504，不是契約裡的 problem+json。
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(timeout).build());
		requests.setReadTimeout(timeout);
		this.gotrue = RestClient.builder()
			.baseUrl(url)
			.requestFactory(requests)
			// secret key 只放 apikey（官方正路）：hosted gateway 換成 service_role 的短效 JWT 再轉給 GoTrue。
			// Authorization 刻意不帶，尤其不轉送旅人自己的 Bearer（T28 研究 Q2.2、Q3.2）。
			.defaultHeader("apikey", secretKey)
			.build();
	}

	/**
	 * 匿名＝訪客＝「沒登入過」：{@code lastLoginAt} 一律 null，即使 GoTrue 在匿名註冊時也寫了
	 * {@code last_sign_in_at}。使用者列不在（過了 session 檢查、查詢前被註銷）是身分問題
	 * 不是故障：401，同 {@code ApiErrors#identityGone}。
	 */
	@GetMapping("/v1/me")
	Profile me(@AuthenticationPrincipal Jwt jwt) {
		UUID id = UUID.fromString(jwt.getSubject());
		return this.jdbc.sql(PROFILE).param(id).query((rs, rowNum) -> {
			String loginMethod = rs.getString("login_method");
			OffsetDateTime lastSignIn = rs.getObject("last_sign_in_at", OffsetDateTime.class);
			boolean guest = loginMethod.equals("anonymous");
			return new Profile(id, rs.getString("nickname"), rs.getString("avatar_url"), loginMethod,
					(guest || lastSignIn == null) ? null : Wire.TIMESTAMP.format(lastSignIn), MEMBER_LEVEL);
		}).optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "not_authenticated", null));
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

/** 基本資料，契約凍結的 6 鍵、鍵序固定；缺值是 null 不是缺鍵。 */
record Profile(UUID id, String nickname, String avatarUrl, String loginMethod, String lastLoginAt,
		String memberLevel) {
}
