package com.kevin.hapeetrail.config;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** 契約在 wire 上的共用常數。 */
public final class Wire {

	/** 時間戳格式：永遠六位小數、永遠 {@code Z}，不因秒數恰為整數而縮水（notes.md 共同約定）。 */
	public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	/**
	 * trim 的字元集＝Unicode {@code White_Space} ＋ U+001C–U+001F（契約 §4 逐字）。便條內容與
	 * 暱稱共用這一份：兩份字元集遲早漂移，症狀是「同一個 app 預檢，一邊過、一邊回 *_empty」。
	 *
	 * <p>刻意兩個都不用：{@code Character.isWhitespace} 少了 NBSP、U+2007、U+202F；
	 * Java 的 {@code \s} 只認 ASCII。兩者都會與契約分歧，而分歧的症狀是
	 * 「app 預檢說可以送、伺服器回 content_empty」。
	 *
	 * <p>結尾用 {@code \z} 而不是 {@code $}：後者在 Java 也匹配「最後一個換行之前」，
	 * 語意與 Postgres 的 {@code $}（純字串結尾）不同。頭尾之外不動——多行內容中間那幾行的
	 * 縮排是內容的一部分。
	 */
	private static final Pattern EDGE_WHITESPACE = Pattern
		.compile("^[\\p{IsWhite_Space}\\x{1C}-\\x{1F}]+|[\\p{IsWhite_Space}\\x{1C}-\\x{1F}]+\\z");

	private Wire() {
	}

	/** 剝掉頭尾的契約空白字元（{@link #EDGE_WHITESPACE}）。 */
	public static String trim(String s) {
		return EDGE_WHITESPACE.matcher(s).replaceAll("");
	}

	/**
	 * Postgres 的 text 存不進、或存進去會變樣的字串：U+0000（存不下，而且不是空白——剝不掉、
	 * 也不算空），以及孤立代理對（沒配對的 U+D800–U+DFFF；轉 UTF-8 時被靜默換成 U+FFFD／「?」，
	 * 使用者送的與存的不同、兩側都沒有訊號）。兩者都是合法的 JSON 逸出，所以 Jackson 不擋；
	 * 呼叫端一律當格式錯誤（400 無 code），不新增契約 token。配對正確的代理對（emoji）不受影響。
	 */
	public static boolean unstorable(String s) {
		if (s.indexOf(0) >= 0) {
			return true;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
					i++;
					continue;
				}
				return true;
			}
			if (Character.isLowSurrogate(c)) {
				return true;
			}
		}
		return false;
	}

}
