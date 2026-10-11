package com.kevin.hapeetrail.account;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
	 * 的同一規則），沒有 ＝ 匿名。暱稱：本人經 {@code PATCH /v1/me} 設的（T34，{@code profiles}）
	 * 最優先；其次是使用者自己在 GoTrue 設的 user metadata，再其次是 provider 給的 identity_data
	 * （綁定不會回寫 user metadata）；Google 兩組鍵名並存，Apple 的 id_token 不帶姓名。
	 * view 只投影這四個鍵，email 等 PII 不經過服務角色。
	 * 頭像 2026-10-11 會議決定先不要：view 連投影都拿掉，用不到的欄位不經過服務角色。
	 *
	 * <p>profiles 在這句 SQL 裡 left join，不改 view：view 是「代讀 auth schema」的窄門，
	 * 服務自己的表不必繞過它。
	 *
	 * <p>後三個來源仍是**使用者可控、未驗證**的字串（任何旅人都能經 Supabase 的 updateUser 改
	 * user_metadata）：只回給本人所以現在無害；哪天要顯示給別人，先驗長度。只有 profiles 那一個
	 * 經過下面的規則。
	 */
	private static final String PROFILE = """
			select coalesce(v.provider, 'anonymous') as login_method, v.last_sign_in_at,
			       coalesce(p.nickname, v.meta_full_name, v.meta_name, v.identity_full_name, v.identity_name)
			         as nickname
			  from hapeetrail_private.auth_users v
			  left join hapeetrail_private.profiles p on p.user_id = v.id
			 where v.id = ?
			""";

	/** 暱稱上限（code point）。附帶在 {@code nickname_too_long} 裡的數字與閘門用的是同一個常數。 */
	private static final int MAX_NICKNAME = 20;

	/** 一人一列：第二次設定覆蓋第一次。使用者已被註銷 ⇒ FK 23503 ⇒ {@code ApiErrors#identityGone} 的 401。 */
	private static final String SET_NICKNAME = """
			insert into hapeetrail_private.profiles (user_id, nickname) values (?, ?)
			on conflict (user_id) do update set nickname = excluded.nickname, updated_at = now()
			""";

	/** 清除 ＝ 刪列（沒有列就是沒自訂）；本來就沒有也成功——清除是冪等的。 */
	private static final String CLEAR_NICKNAME = "delete from hapeetrail_private.profiles where user_id = ?";

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
			return new Profile(id, rs.getString("nickname"), loginMethod,
					(guest || lastSignIn == null) ? null : Wire.TIMESTAMP.format(lastSignIn), MEMBER_LEVEL);
		}).optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "not_authenticated", null));
	}

	/**
	 * 更新暱稱（T34）。body 有三態，語意各不相同，所以收 {@code Map} 而不是 record——record 分不出
	 * 「沒給這個鍵」與「給了 null」：
	 * <ul>
	 * <li>缺 {@code nickname} 鍵（含沒有 body）：格式錯誤，400 無 code。
	 * <li>{@code null}：清除自訂暱稱，回到提供者給的名字。
	 * <li>字串：先 trim 再數 code point（與便條內容同一套字元集與單位），存 trim 後的版本。
	 * </ul>
	 * 代價：{@code Map<String, Object>} 不經 {@code JsonConfig} 的嚴格 coercion（Jackson 照 JSON
	 * 的原型別給 Integer／Boolean／Map…），所以「不是字串」由這裡自己用 {@code instanceof} 擋。
	 *
	 * <p>回傳直接重查 GET 那一句 SQL：兩條路逐字一致，不必在這裡重組優先序。
	 */
	@PatchMapping("/v1/me")
	Profile update(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) Map<String, Object> body) {
		if (body == null || !body.containsKey("nickname")) {
			throw malformed();
		}
		UUID id = UUID.fromString(jwt.getSubject());
		Object value = body.get("nickname");
		if (value == null) {
			this.jdbc.sql(CLEAR_NICKNAME).param(id).update();
			return me(jwt);
		}
		// 與便條 content 逐字同一條路（NoteService#drop）：U+0000／孤立代理對是格式錯誤，不是業務錯誤。
		if (!(value instanceof String raw) || Wire.unstorable(raw)) {
			throw malformed();
		}
		// 順序不能反：上限量的是 trim **之後**的暱稱。
		String nickname = Wire.trim(raw);
		if (nickname.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "nickname_empty", null);
		}
		// code point 而非 char：一個 emoji 在使用者眼中是 1 個字，String.length() 算 2。
		if (nickname.codePointCount(0, nickname.length()) > MAX_NICKNAME) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "nickname_too_long", Map.of("maxChars", MAX_NICKNAME));
		}
		this.jdbc.sql(SET_NICKNAME).params(id, nickname).update();
		return me(jwt);
	}

	/** 缺鍵／型別不對屬格式錯誤那一類：400 但**沒有 {@code code}**（v4 唯一的判斷閘門）。 */
	private static ApiException malformed() {
		return new ApiException(HttpStatus.BAD_REQUEST, null, null);
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

/** 基本資料，契約凍結的 5 鍵、鍵序固定；缺值是 null 不是缺鍵。 */
record Profile(UUID id, String nickname, String loginMethod, String lastLoginAt, String memberLevel) {
}
