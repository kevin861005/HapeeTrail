package com.kevin.hapeetrail;

import java.time.Duration;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.kevin.hapeetrail.config.Wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * T35：{@link Wire#trim} 的字元集與語意不變、但時間是線性的。原本的 regex
 * （{@code ^[ws]+|[ws]+\z}）在中段一長串空白時每個起點都重掃 ⇒ O(n²)，匿名帳號
 * 幾個請求就能吃滿 CPU。
 */
class WireTest {

	/** 舊實作逐字留在這裡當 oracle：新實作必須在每一個 code point 上與它同答。 */
	private static final Pattern ORACLE = Pattern
		.compile("^[\\p{IsWhite_Space}\\x{1C}-\\x{1F}]+|[\\p{IsWhite_Space}\\x{1C}-\\x{1F}]+\\z");

	private static String oracle(String s) {
		return ORACLE.matcher(s).replaceAll("");
	}

	/** 全部 code point（含孤立代理、星群平面）：在頭、在尾、在中間、單獨一個，答案都與舊 regex 相同。 */
	@Test
	void agreesWithTheOldRegexOnEveryCodePoint() {
		for (int cp = Character.MIN_CODE_POINT; cp <= Character.MAX_CODE_POINT; cp++) {
			String c = Character.toString(cp);
			for (String s : new String[] { c, c + "a" + c + c, "a" + c + "b" }) {
				assertThat(Wire.trim(s)).describedAs("U+%04X in %s", cp, s.codePoints().boxed().toList())
					.isEqualTo(oracle(s));
			}
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
			""                             | ""
			"  "                           | ""
			"　 \u001f\n"         | ""
			"  前後都有空白\t\n"             | 前後都有空白
			"第一行\n  第二行"               | "第一行\n  第二行"
			"　a b　c　"       | "a b　c"
			"𝄞 "                | 𝄞
			" 𝄞"                | 𝄞
			"​a​"                | "​a​"
			""")
	void stripsOnlyTheEdges(String input, String expected) {
		assertThat(Wire.trim(input)).isEqualTo(expected).isEqualTo(oracle(input));
	}

	/** 舊實作在這兩個輸入上要數十秒；線性掃描應在毫秒內。 */
	@Test
	void isLinearInALongRunOfWhitespace() {
		String middle = "a" + " ".repeat(200_000) + "b";
		String blank = " ".repeat(200_000);

		assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
			assertThat(Wire.trim(middle)).isEqualTo(middle);
			assertThat(Wire.trim(blank)).isEmpty();
		});
	}

}
