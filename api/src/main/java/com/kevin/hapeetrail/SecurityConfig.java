package com.kevin.hapeetrail;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

	/**
	 * 401 的唯一形狀。所有變形（無 header、簽章不符、過期、缺 exp、sub 缺失或不是 UUID、
	 * aud 不符、iss 不符或缺失）同一個答案：iOS 對 401 一律走刷新流程，不必比對 body。
	 * {@link ApiErrors} 的「使用者已不存在」走另一條路，但形狀逐字相同。
	 */
	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","status":401,"title":"not_authenticated","code":"not_authenticated"}""";

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
		AuthenticationEntryPoint entryPoint = SecurityConfig::writeNotAuthenticated;
		return http
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers("/actuator/health").permitAll()
				.anyRequest().authenticated())
			// 這個 entry point 也接管「完全沒帶 header」的請求：resource server 是本鏈唯一
			// 註冊 entry point 的 configurer，ExceptionHandlingConfigurer 因此拿它當預設值
			// （沒有它就是 Http403ForbiddenEntryPoint ⇒ 403 而不是契約要的 401）。
			// 哪天多了第二個 configurer，everythingElseNeedsAToken 會立刻紅。
			.oauth2ResourceServer((oauth2) -> oauth2
				.authenticationEntryPoint(entryPoint)
				.jwt((jwt) -> jwt.jwtAuthenticationConverter(subjectRequired())))
			// bearer token API：沒有 session、沒有表單，csrf 沒有攻擊面也沒有 token 可帶。
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.build();
	}

	/**
	 * 縱深驗證的落腳處。Boot 的 {@code JwtDecoderConfiguration} 會把容器裡所有
	 * {@code OAuth2TokenValidator<Jwt>} bean 併進 validator 鏈，而且三條 decoder 路徑
	 * （jwk-set-uri／public-key-location／issuer-uri）都吃——所以這裡加的規則對測試與正式
	 * 環境同時成立。不用 {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}：
	 * Boot 的 {@code KeyValueCondition} 明文與 {@code public-key-location} 互斥，設了它，
	 * 測試那條路徑會整組沒有 decoder。
	 *
	 * <p>{@code iss}：{@code aud=authenticated} 是**每個** Supabase 專案的共同值，擋住跨專案
	 * token 的原本只有簽章。發行者是零成本的第二道。
	 *
	 * <p>{@code exp}：Spring 的 {@code JwtTimestampValidator} 只驗「有值時是否過期」，
	 * 缺席不失敗——沒有 {@code exp} 的 token 等於永不過期。
	 */
	@Bean
	OAuth2TokenValidator<Jwt> issuerAndExpiryRequired(@Value("${hapeetrail.jwt.issuer}") String issuer) {
		return new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(issuer),
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull));
	}

	/**
	 * 簽章、過期由 Spring 的預設 validator 管，{@code aud} 由
	 * {@code spring.security.oauth2.resourceserver.jwt.audiences} 管；
	 * 只剩 {@code sub} 沒人管——沒有它就沒有使用者身分，fail-closed。
	 *
	 * <p>「有值」不夠，必須是 UUID：GoTrue 一律發 UUID 形狀的 sub，而下游整條路
	 * （controller、SQL 參數、FK）都以 UUID 為前提。放行一個非 UUID 的 sub，
	 * 它會一路走到 {@code UUID.fromString} 才炸，那是 500、不是契約要的 401，
	 * 而且例外訊息會把 sub 原值帶進 ERROR 日誌。
	 */
	private static Converter<Jwt, AbstractAuthenticationToken> subjectRequired() {
		JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
		return (jwt) -> {
			try {
				// 解析得出來不夠：UUID.fromString 對區段長度寬鬆，"1-1-1-1-1" 會被補成
				// 00000001-0001-0001-0001-000000000001，兩個不同字串別名成同一個使用者。
				// 來回一趟還是同一個字串，才是 GoTrue 真的會發的那種 sub。
				String sub = jwt.getSubject();
				if (!UUID.fromString(sub).toString().equalsIgnoreCase(sub)) {
					throw new IllegalArgumentException();
				}
			}
			catch (IllegalArgumentException | NullPointerException ex) {
				// 訊息刻意不含 sub 原值：401 的形狀是凍結的，這裡也不留線索到日誌。
				throw new InvalidBearerTokenException("sub is not a uuid");
			}
			return delegate.convert(jwt);
		};
	}

	private static void writeNotAuthenticated(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException ex) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write(NOT_AUTHENTICATED);
	}

}
