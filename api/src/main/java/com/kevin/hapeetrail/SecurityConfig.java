package com.kevin.hapeetrail;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration
class SecurityConfig {

	/**
	 * 401 的唯一形狀。五種變形（無 header、簽章不符、過期、缺 sub、aud 不符）同一個答案：
	 * iOS 對 401 一律走刷新流程，不必比對 body。
	 */
	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","title":"not_authenticated","status":401,"code":"not_authenticated"}""";

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
	 * 簽章、過期由 Spring 的預設 validator 管，{@code aud} 由
	 * {@code spring.security.oauth2.resourceserver.jwt.audiences} 管；
	 * 只剩 {@code sub} 沒人管——沒有它就沒有使用者身分，fail-closed。
	 */
	private static Converter<Jwt, AbstractAuthenticationToken> subjectRequired() {
		JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
		return (jwt) -> {
			if (!StringUtils.hasText(jwt.getSubject())) {
				throw new InvalidBearerTokenException("no sub");
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
