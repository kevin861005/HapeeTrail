package com.kevin.hapeetrail.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TomcatConfig {

	/**
	 * Tomcat 不替任何 HTTP method 解析表單 body（T38）。預設是 POST：POST ＋
	 * {@code application/x-www-form-urlencoded} 打任何 {@code @RequestBody} 端點（含免登入的
	 * {@code /v1/app/flags}），Spring 在比對 media type 之前就呼叫 {@code getParameterMap()}，Tomcat 依
	 * Content-Length 配一塊 {@code byte[]} 把整份讀進來（上限 {@code max-http-form-post-size} 2MB，超過丟
	 * {@code InvalidParameterException} → 500 ＋ ERROR 堆疊）。API 只收 JSON，不需要這條路。
	 *
	 * <p>空字串的語意（Tomcat 11.0.24 {@code Connector}）：{@code StringUtils.splitCommaSeparated("")} 回空陣列
	 * ⇒ 空集合，{@code isParseBodyMethod} 對任何 method 都是 false；{@code initInternal} 只在集合為
	 * {@code null} 時才補回預設 POST，所以不會被還原。query string 不受影響（{@code @RequestParam} 照常）。
	 * 關掉後表單一律走 MVC 的「沒有 converter 讀得了」→ 415 沒有 code。
	 */
	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> noFormBodyParsing() {
		return (factory) -> factory.addConnectorCustomizers((connector) -> connector.setParseBodyMethods(""));
	}

}
