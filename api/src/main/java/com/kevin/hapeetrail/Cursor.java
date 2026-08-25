package com.kevin.hapeetrail;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;

/**
 * 列表的不透明游標，兩支列表共用的唯一編解碼處。內容是 base64url 的
 * JSON{版本, 所屬列表, 排序鍵, id}——**不屬於契約**，client 唯一的義務是原樣回傳。
 *
 * <p>兩道閘門各擋一種漂移：{@code v} 擋編碼格式變更，{@code l} 擋排序語意變更
 * ——日後某支列表改以距離或熱門度排序時舊游標即失效，同時讓兩支列表的游標無法互換
 * （否則收藏的游標會被我的便條拿去跟 {@code created_at} 比較，靜默回錯頁而毫無訊號）。
 *
 * <p>複合鍵（排序鍵 ＋ id）不是保險：時間戳會平手（同一批匯入、同一個交易），
 * 只用時間戳當游標時平手那幾筆會整批重複或整批消失。
 *
 * <p>ponytail: 不簽章不加密。游標不授予任何權限——查詢永遠限縮在呼叫者自己的資料上，
 * 竄改最多只能改變自己看到的起點。哪天游標開始編碼「跨使用者」的查詢條件就得加簽章。
 *
 * <p>base64**url** 而非標準 base64：游標活在 query 參數裡，標準版的 {@code +} 會被
 * 解成空白（且 Postman 不替變數編碼），症狀是 client 忠實地原樣回傳卻拿到 invalid_cursor。
 */
record Cursor(String list, OffsetDateTime key, UUID id) {

	/** 目前的編碼版本。改了編碼就 +1，舊游標於是被大聲拒絕而不是靜默誤讀。 */
	private static final int VERSION = 1;

	private static final ObjectMapper JSON = new ObjectMapper();

	String encode() {
		String json = JSON.writeValueAsString(JSON.createObjectNode()
			.put("v", VERSION)
			.put("l", this.list)
			.put("t", this.key.toString())
			.put("i", this.id.toString()));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @param raw query 上的游標；null ＝ 第一頁（{@code ""} 不是——那是 client 送了個壞值）
	 * @param list 呼叫端宣告自己是哪一支列表，不符即拒
	 * @return null ＝ 第一頁
	 * @throws ApiException 無法解碼、被竄改、版本不符、屬於其他列表——對外都是同一個 token。
	 * 靜默退化成第一頁會掉列或無限翻頁，所以一律大聲失敗。
	 */
	static Cursor decode(String raw, String list) {
		if (raw == null) {
			return null;
		}
		try {
			JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(raw));
			if (payload.path("v").asInt() != VERSION || !list.equals(payload.path("l").asString())) {
				throw new IllegalArgumentException("cursor version or list");
			}
			return new Cursor(list, OffsetDateTime.parse(payload.path("t").asString()),
					UUID.fromString(payload.path("i").asString()));
		}
		catch (RuntimeException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", null);
		}
	}

}
