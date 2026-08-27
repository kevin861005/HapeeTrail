package com.kevin.hapeetrail;

import java.sql.SQLException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.dao.DataIntegrityViolationException;
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

	/** PostgreSQL 的 foreign_key_violation。 */
	private static final String FOREIGN_KEY_VIOLATION = "23503";

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
	 * {@code notes} 上只有 {@code author_id} 與 {@code picked_up_by} 兩支 FK，都指向
	 * {@code auth.users} ⇒ {@code 23503} 的唯一語意就是「呼叫者的身分已不存在」
	 * （帳號刪除、token 尚未過期）。那是身分問題不是伺服器故障：回 401 讓 iOS 走刷新流程，
	 * 500 只會讓它一直重試。其餘完整性錯誤不是身分問題，維持原樣往外拋。
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Problem> identityGone(DataIntegrityViolationException ex) {
		if (ex.getMostSpecificCause() instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
			return business(new ApiException(HttpStatus.UNAUTHORIZED, "not_authenticated", null));
		}
		return unexpected(ex);
	}

	/**
	 * 信封的最後一道：沒人接的例外原本會掉出這個 advice、回 Spring 自己的 500 錯誤頁
	 * （{@code {"timestamp":…,"error":…,"path":…}}）——那個形狀沒有 {@code type}、沒有
	 * {@code code}，「有 code 才是業務錯誤」在 500 上就不成立，而且它會把路徑回述給 client。
	 *
	 * <p>ERROR 只記例外本身（訊息與堆疊），不記請求：body 裡有座標與便條內容。
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Problem> unexpected(Exception ex) {
		this.logger.error("未預期的例外", ex);
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return respond(status).body(new Problem(status.value(), status.getReasonPhrase(), null, null));
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
