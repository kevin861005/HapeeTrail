package com.kevin.hapeetrail;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 錯誤信封的唯一建構處。契約 v4 的形狀（RFC 9457 problem+json）是凍結的，所以這裡自己組
 * ——不用 Spring 的 {@code ProblemDetail}：它在 {@code about:blank} 時會省略 {@code type}
 * （契約列為必填）、會多帶 {@code instance}，還會把框架的 {@code detail} 訊息寫進 body。
 */
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {

	/**
	 * 業務錯誤：帶凍結的 {@code code}，有附帶資料時才有 {@code details}。
	 * {@code code} 為 null ＝ 缺必填欄位那一類，走與框架錯誤相同的無 code 形狀。
	 */
	@ExceptionHandler(ApiException.class)
	ResponseEntity<Problem> business(ApiException ex) {
		String code = ex.getMessage();
		String title = (code != null) ? code : ex.status().getReasonPhrase();
		ResponseEntity.BodyBuilder response = respond(ex.status());
		// 標準 Retry-After 直接取 details 裡的那個數字（契約：兩者相同）。從同一個值長出來的
		// 兩種表達沒有第二個來源可以漂移——各算一次遲早會分歧，而 client 兩邊都在讀。
		Object retryAfterS = (ex.details() != null) ? ex.details().get("retryAfterS") : null;
		if (retryAfterS != null) {
			response.header(HttpHeaders.RETRY_AFTER, retryAfterS.toString());
		}
		return response.body(new Problem(ex.status().value(), title, code, ex.details()));
	}

	/**
	 * Spring 自己攔下的型別／格式錯誤（非法 JSON、欄位型別不對、找不到路徑…）：
	 * 同一個信封，但**沒有 {@code code}**——「有 code 才是業務錯誤」是 v4 唯一的判斷閘門。
	 * 框架給的 body 一律丟掉：它的 {@code detail} 會回述請求內容，而請求裡有座標。
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		HttpStatus resolved = HttpStatus.valueOf(status.value());
		return respond(resolved).body(new Problem(resolved.value(), resolved.getReasonPhrase(), null, null));
	}

	/** 兩條路同一個信封：contentType 只寫在這裡，漏掉它的回應會被 client 當成非 problem+json。 */
	private static ResponseEntity.BodyBuilder respond(HttpStatusCode status) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	/**
	 * {@code type} 恆為 {@code about:blank}（型別由 {@code code} 表達）；
	 * {@code title} 業務錯誤時等於 {@code code}，其餘為通用描述。
	 * null 的鍵整個省略——client 不必分辨「沒有這個鍵」與「值為 null」。
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Problem(String type, int status, String title, String code, Map<String, Object> details) {

		Problem(int status, String title, String code, Map<String, Object> details) {
			this("about:blank", status, title, code, details);
		}
	}

}

/**
 * 業務錯誤。{@code message} 就是契約的 token——凍結的字串，同時當 {@code code} 與
 * {@code title}，兩處不會漂移。
 */
class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final Map<String, Object> details;

	ApiException(HttpStatus status, String code, Map<String, Object> details) {
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
