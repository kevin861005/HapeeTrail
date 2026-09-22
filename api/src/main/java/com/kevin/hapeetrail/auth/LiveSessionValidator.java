package com.kevin.hapeetrail.auth;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 「驗 JWT」＝ 驗簽章與 claims ＋ **session 還活著**（ADR-0013）。GoTrue 登出與註銷都會刪
 * {@code auth.sessions} 的列，所以列不在 ＝ 這張 token 已經不代表任何登入狀態，即使它還沒過期。
 *
 * <p>以 bean 的形式掛進 validator 鏈（Boot 會把容器裡所有 {@code OAuth2TokenValidator<Jwt>}
 * 併進 decoder，見 {@code SecurityConfig#issuerAndExpiryRequired}）：失敗走的是與簽章不符
 * 同一條路，401 的形狀因此逐字相同。
 *
 * <p>每個請求一次 PK lookup，刻意不做快取——快取窗就是登出後 token 還能用的窗，等於推翻需求。
 *
 * <p>validator 鏈不短路（Spring 逐一呼叫、收集全部錯誤）：簽章有效但已過期／aud／iss 不符的
 * token 也會查一次，回應照樣是 401。已接受（2026-09-22）：只有 GoTrue 簽得出的 token 走得到這裡，
 * 簽章不符的垃圾碰不到 DB；代價是這類 token 多一次 sub-ms 查詢。
 */
@Component
class LiveSessionValidator implements OAuth2TokenValidator<Jwt> {

	// 描述刻意不含 claim 值：這段字會進 Spring Security 的 debug 日誌。
	private static final OAuth2TokenValidatorResult DEAD = OAuth2TokenValidatorResult
		.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "session is not alive", null));

	private final JdbcClient jdbc;

	LiveSessionValidator(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		UUID session;
		try {
			// GoTrue 發給使用者的 token 必定帶 session_id；缺了或不是 UUID 就不是使用者 token。
			// 與 GoTrue 自己不同：它把缺席／nil UUID 當「沒有 session」放行（給 service_role 用），
			// 這裡 fail-closed——nil UUID 查不到列，自然 401。
			// 不像 sub 要求「解析回去還是同一個字串」：這個值只拿來查主鍵，縮寫形式別名到的
			// UUID 也得真有那一列才會過，而 session id 是隨機 v4。
			session = UUID.fromString(jwt.getClaimAsString("session_id"));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return DEAD;
		}
		boolean alive = this.jdbc.sql("select exists (select from hapeetrail_private.auth_sessions where id = ?)")
			.param(session)
			.query(Boolean.class)
			.single();
		return alive ? OAuth2TokenValidatorResult.success() : DEAD;
	}

}
