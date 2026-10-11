package com.kevin.hapeetrail.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.kevin.hapeetrail.config.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 層的設定：更新檢查（T31）與功能總開關（T32）。兩者都只是讀 {@code hapeetrail_private}
 * 的一張小表——改門檻或撥開關是 SQL editor 一句 UPDATE，不必重部署。
 *
 * <p>兩支都**不需要登入**（SecurityConfig 對 {@code /v1/app/**} permitAll 且忽略 Authorization）：
 * App 一啟動、還沒匿名登入或 token 已過期時就要能問「要不要強制更新」。
 * ponytail: 公開端點沒有限流——每個請求是一次 PK 查詢或最多 50 個 key 的 {@code any()}，
 * 表只有幾列；真被灌爆時先上平台層的 per-IP 限流（Cloud Armor／Fly 的 rate limit），不在這裡做。
 */
@RestController
class AppController {

	/** 與 migration 的 CHECK 同一條：存得進去的 key 才查得到，兩邊不會各自漂移。 */
	private static final Pattern FLAG_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	/** 一次最多問幾個 key——擋的是失手或惡意的巨量陣列，不是產品規則。 */
	private static final int MAX_KEYS = 50;

	private final JdbcClient jdbc;

	AppController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 只交出兩個門檻，比較在 App（2026-10-11 會議）：低於 minimum 強制、低於 latest 提示。
	 * 不收參數，所以這支沒有 400；格式由 migration 的 CHECK 保證，App 拿到的必然是純數字點分版本。
	 * ponytail: 平台先寫死 ios（只有 iOS）；加 Android 時變成選填 query 參數，非破壞性。
	 */
	@GetMapping("/v1/app/update")
	UpdateCheck update() {
		return this.jdbc.sql("select minimum_version, latest_version from hapeetrail_private.app_versions"
				+ " where platform = 'ios'")
			.query((rs, rowNum) -> new UpdateCheck(rs.getString("minimum_version"), rs.getString("latest_version")))
			// 列不在是設定壞了（migration 一開始就塞了 ios）：500，不是「不用更新」。
			.single();
	}

	/** 表裡沒有的 key 是 false，不是錯誤也不是缺鍵；重複 key 合併，回傳順序照請求。 */
	@PostMapping("/v1/app/flags")
	FlagsResult flags(@RequestBody(required = false) FlagsRequest request) {
		List<String> keys = (request != null) ? request.keys() : null;
		if (keys == null || keys.size() > MAX_KEYS
				|| keys.stream().anyMatch((key) -> key == null || !FLAG_KEY.matcher(key).matches())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, null, null);
		}
		Map<String, Boolean> flags = new LinkedHashMap<>();
		keys.forEach((key) -> flags.put(key, false));
		if (!flags.isEmpty()) {
			this.jdbc.sql("select key, enabled from hapeetrail_private.feature_flags where key = any(?)")
				.param(flags.keySet().toArray(String[]::new))
				.query((rs, rowNum) -> flags.put(rs.getString("key"), rs.getBoolean("enabled")))
				.list();
		}
		return new FlagsResult(flags);
	}

}

/** {@code GET /v1/app/update} 的回應：恰好兩個門檻，判定交給 App。 */
record UpdateCheck(String minimumVersion, String latestVersion) {
}

/** {@code POST /v1/app/flags} 的 body。 */
record FlagsRequest(List<String> keys) {
}

/** 包在物件裡而不是裸 map：日後加欄位不是破壞性變更。 */
record FlagsResult(Map<String, Boolean> flags) {
}
