package com.kevin.hapeetrail.config;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * 業務錯誤。{@code message} 就是契約的 token——凍結的字串，同時當 {@code code} 與
 * {@code title}，兩處不會漂移。
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final Map<String, Object> details;

	public ApiException(HttpStatus status, String code, Map<String, Object> details) {
		super(code);
		this.status = status;
		this.details = details;
	}

	HttpStatus status() {
		return this.status;
	}

	Map<String, Object> details() {
		return this.details;
	}

}
