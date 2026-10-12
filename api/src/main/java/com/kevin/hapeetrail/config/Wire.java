package com.kevin.hapeetrail.config;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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
	 * <p>逐一列舉而不用 regex（T35）：原本的 {@code ^[ws]+|[ws]+\z} 在中段一長串空白時
	 * 每個起點都重掃尾端 ⇒ O(n²)，原始字串又不受上限保護，幾個請求就能吃滿 CPU。
	 * 這組字元全在 BMP、沒有一個是代理，所以按 {@code char} 掃描也不會把代理對切開。
	 * {@code WireTest} 以舊 regex 為 oracle 逐一比對全部 code point。
	 */
	private static boolean isTrimmable(char c) {
		return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0 || c == 0x1680
				|| (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
				|| c == 0x3000;
	}

	private Wire() {
	}

	/**
	 * 剝掉頭尾的契約空白字元（{@link #isTrimmable}），線性時間。頭尾之外不動——多行內容
	 * 中間那幾行的縮排是內容的一部分；全是空白 ⇒ 空字串。
	 */
	public static String trim(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && isTrimmable(s.charAt(start))) {
			start++;
		}
		while (end > start && isTrimmable(s.charAt(end - 1))) {
			end--;
		}
		return s.substring(start, end);
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
