package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 票 05：{@code POST /v1/notes}。情境清單＝{@code supabase/tests/notes.test.sql} 的
 * drop、style 代號、私人便條驗證、兩個上限、wire 形狀五段，逐條搬過來。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DropNoteTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** 契約凍結的 9 鍵。多一鍵少一鍵都是破壞性變更，所以比對的是整個集合。 */
	private static final Set<String> NOTE_KEYS = Set.of("id", "content", "color", "style", "audience", "coordinate",
			"createdAt", "expiresAt", "pickedUpAt");

	private static final Pattern WIRE_TS = Pattern
		.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z$");

	private static final DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	/**
	 * 契約 §4 的空白字元集：Unicode White_Space（25 個碼位）＋ U+001C–U+001F 四個 C0
	 * 資訊分隔符。{@code Character.isWhitespace} 不含 NBSP、U+2007、U+202F——那三個是本表
	 * 的實心處，用它實作會在這裡紅。
	 */
	private static final int[] WHITESPACE = { 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0,
			0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028,
			0x2029, 0x202F, 0x205F, 0x3000 };

	/**
	 * 同一段契約的反面：Unicode 的格式字元**不是**空白，不剝、也不判空
	 * ——只由它們組成的便條建得起來（已知邊界，ADR-0009）。
	 */
	private static final int[] FORMAT_CHARS = { 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x180E };

	/** 測試座標每次隨機：共用資料庫上重跑不互擾（T18 的不變式）。 */
	private static final double BASE_LAT = ThreadLocalRandom.current().nextDouble(-60, 55);

	private static final double BASE_LNG = ThreadLocalRandom.current().nextDouble(-180, 180);

	/** 每個旅人一塊整數度的地盤。 */
	private static final AtomicInteger SLOT = new AtomicInteger();

	@LocalServerPort
	int port;

	/** 併發超越量的上界。從設定讀而不是寫死：池子調大時這條測試要跟著鬆，不然會變成假紅。 */
	@Value("${spring.datasource.hikari.maximum-pool-size}")
	int poolSize;

	// ─── 正常路徑與 wire 形狀 ────────────────────────────────────────────────

	@Test
	void dropsANoteAndGetsTheCanonicalNoteBack() throws Exception {
		Traveler me = traveler();

		JsonNode note = created(drop(me, body(me, "神社後面的拉麵店超好吃")));

		// 恰 9 鍵：author_id／picked_up_by 這類身分欄位永遠不上 wire。
		assertThat(fieldNames(note)).isEqualTo(NOTE_KEYS);
		assertThat(UUID.fromString(note.get("id").asString())).isNotNull();
		assertThat(note.get("content").asString()).isEqualTo("神社後面的拉麵店超好吃");
		assertThat(note.get("color").asInt()).isEqualTo(1);
		assertThat(note.get("style").asInt()).isEqualTo(1);
		assertThat(note.get("audience").asString()).isEqualTo("anyone");
		assertThat(note.get("coordinate").get("latitude").asDouble()).isCloseTo(me.lat(), within(1e-9));
		assertThat(note.get("coordinate").get("longitude").asDouble()).isCloseTo(me.lng(), within(1e-9));
		assertThat(note.get("pickedUpAt").isNull()).isTrue();

		// 六位小數＋Z，且公開便條的 expiresAt 恰好是 createdAt ＋ 90 天。
		assertThat(note.get("createdAt").asString()).matches(WIRE_TS);
		assertThat(note.get("expiresAt").asString()).matches(WIRE_TS);
		assertThat(instant(note.get("expiresAt"))).isEqualTo(instant(note.get("createdAt")).plus(Duration.ofDays(90)));
	}

	/** 秒數恰為整數時位數不得縮水——預設序列化會，明確格式化才不會。 */
	@Test
	void timestampsAlwaysHaveSixDecimals() throws Exception {
		Traveler me = traveler();

		for (int i = 0; i < 8; i++) {
			JsonNode note = created(drop(me, body(me, "整秒也要六位 " + i)));
			assertThat(note.get("createdAt").asString()).matches(WIRE_TS);
		}
	}

	/** 旅遊紀錄不會過期 ⇒ expiresAt 為 null（鍵仍在）。 */
	@Test
	void privateNotesNeverExpire() throws Exception {
		Traveler me = traveler();
		Map<String, Object> body = body(me, "今天走了 18 公里");
		body.put("audience", "self");

		JsonNode note = created(drop(me, body));

		assertThat(fieldNames(note)).isEqualTo(NOTE_KEYS);
		assertThat(note.get("audience").asString()).isEqualTo("self");
		assertThat(note.get("expiresAt").isNull()).isTrue();
	}

	/**
	 * 座標存成 WGS-84 geography（產生欄位），且回傳座標等於送出座標。
	 *
	 * <p>刻意不用完全相等：Supabase 的映像設了 {@code extra_float_digits = 0}，float8 以
	 * **文字**回傳時截到 15 位有效數字，而 pgjdbc 要同一句 SQL 在同一條池連線上跑滿
	 * {@code prepareThreshold}（預設 5）次才轉二進位傳輸、精確往返——換句話說「相等」與否
	 * 取決於這次請求落在哪條連線、是第幾次執行，是會隨測試順序翻面的假紅。
	 * 1e-9 度約 0.1mm，遠在任何地理意義之下，但足以抓到「回錯了一個點」。
	 */
	@Test
	void coordinatesAreStoredAsWgs84Geography() throws Exception {
		Traveler me = traveler();

		JsonNode note = created(drop(me, body(me, "座標往返")));

		assertThat(note.get("coordinate").get("latitude").asDouble()).isCloseTo(me.lat(), within(1e-9));
		assertThat(note.get("coordinate").get("longitude").asDouble()).isCloseTo(me.lng(), within(1e-9));
		assertThat(admin()
			.sql("select extensions.st_srid(location) = 4326"
					+ " and extensions.st_x(location::extensions.geometry) = lng"
					+ " and extensions.st_y(location::extensions.geometry) = lat from public.notes where id = ?::uuid")
			.param(note.get("id").asString())
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void droppedNotesShowUpInMyNotesNewestFirst() throws Exception {
		Traveler me = traveler();
		String first = created(drop(me, body(me, "第一張"))).get("id").asString();
		String second = created(drop(me, body(me, "第二張"))).get("id").asString();
		String third = created(drop(me, body(me, "第三張"))).get("id").asString();

		JsonNode page = JSON.readTree(get("/v1/me/notes", me).body());

		assertThat(page.get("items").findValuesAsString("id")).containsExactly(third, second, first);
	}

	// ─── 座標 ────────────────────────────────────────────────────────────────

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource
	void coordinatesOutOfRangeAreRejected(double latitude, double longitude) throws Exception {
		Traveler me = traveler();
		Map<String, Object> body = body(me, "越界");
		body.put("coordinate", coordinate(latitude, longitude));

		assertProblem(drop(me, body), 400, "invalid_coordinates");
	}

	static Stream<Arguments> coordinatesOutOfRangeAreRejected() {
		return Stream.of(Arguments.of(90.000001, 0.0), Arguments.of(-90.000001, 0.0), Arguments.of(0.0, 180.000001),
				Arguments.of(0.0, -180.000001), Arguments.of(91.0, 181.0));
	}

	/** 四個角落是合法的，不是「接近上限就拒絕」。 */
	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource
	void coordinatesOnTheBoundaryAreAccepted(double latitude, double longitude) throws Exception {
		Traveler me = traveler();
		Map<String, Object> body = body(me, "邊界");
		body.put("coordinate", coordinate(latitude, longitude));

		JsonNode note = created(drop(me, body));

		assertThat(note.get("coordinate").get("latitude").asDouble()).isEqualTo(latitude);
		assertThat(note.get("coordinate").get("longitude").asDouble()).isEqualTo(longitude);
	}

	static Stream<Arguments> coordinatesOnTheBoundaryAreAccepted() {
		return Stream.of(Arguments.of(90.0, 180.0), Arguments.of(-90.0, -180.0), Arguments.of(0.0, 0.0));
	}

	// ─── trim 與字數 ─────────────────────────────────────────────────────────

	/**
	 * 契約字元集的 29 個碼位，逐一驗它算空白。只由它組成的內容 ⇒ trim 後為空 ⇒
	 * {@code content_empty}。
	 */
	@ParameterizedTest(name = "U+{0}")
	@MethodSource
	void whitespaceOnlyContentIsEmpty(String codePointName, String content) throws Exception {
		Traveler me = traveler();

		assertProblem(drop(me, body(me, content)), 400, "content_empty");
	}

	static Stream<Arguments> whitespaceOnlyContentIsEmpty() {
		return IntStream.of(WHITESPACE)
			.mapToObj((cp) -> Arguments.of("%04X".formatted(cp), new String(Character.toChars(cp)).repeat(3)));
	}

	/** 反面：格式字元不是空白，原樣保留、建得起來。 */
	@ParameterizedTest(name = "U+{0}")
	@MethodSource
	void formatCharactersAreNotWhitespace(String codePointName, String content) throws Exception {
		Traveler me = traveler();

		assertThat(created(drop(me, body(me, content))).get("content").asString()).isEqualTo(content);
	}

	static Stream<Arguments> formatCharactersAreNotWhitespace() {
		return IntStream.of(FORMAT_CHARS)
			.mapToObj((cp) -> Arguments.of("%04X".formatted(cp), new String(Character.toChars(cp))));
	}

	/** 只剝頭尾：多行內容中間那幾行的縮排是內容的一部分。 */
	@Test
	void trimsOnlyBothEnds() throws Exception {
		Traveler me = traveler();

		assertThat(created(drop(me, body(me, "　你好\n　"))).get("content").asString()).isEqualTo("你好");
		assertThat(created(drop(me, body(me, "第一行\n  第二行"))).get("content").asString()).isEqualTo("第一行\n  第二行");
		assertThat(created(drop(me, body(me, "  前後都有空白\t\n"))).get("content").asString()).isEqualTo("前後都有空白");
	}

	/** 上限量的是 trim **之後**的內容，順序不能反。 */
	@Test
	void countsCodePointsAfterTrimming() throws Exception {
		Traveler me = traveler();

		assertThat(created(drop(me, body(me, "　".repeat(10) + "字".repeat(500) + "\n"))).get("content").asString())
			.hasSize(500);
	}

	/** 500 恰好合法、501 拒絕；計數以 code point 為單位（不是 UTF-16 char）。 */
	@Test
	void contentIsAtMostFiveHundredCodePoints() throws Exception {
		Traveler me = traveler();

		assertThat(created(drop(me, body(me, "字".repeat(500)))).get("content").asString()).hasSize(500);
		// 500 個星群平面字元 ＝ 1000 個 char：用 String.length() 算就會在這裡誤拒。
		assertThat(created(drop(me, body(me, "𝄞".repeat(500)))).get("content").asString()).hasSize(1000);

		JsonNode problem = assertProblem(drop(me, body(me, "字".repeat(501))), 400, "content_too_long");
		assertThat(problem.get("details").get("maxChars").asInt()).isEqualTo(500);
	}

	// ─── 代號與 audience ─────────────────────────────────────────────────────

	@Test
	void styleCodesDefaultToOneWhenOmittedOrNull() throws Exception {
		Traveler me = traveler();
		Map<String, Object> nulls = body(me, "明確給 null");
		nulls.put("color", null);
		nulls.put("style", null);

		JsonNode omitted = created(drop(me, body(me, "整個省略")));
		JsonNode explicit = created(drop(me, nulls));

		assertThat(omitted.get("color").asInt()).isEqualTo(1);
		assertThat(omitted.get("style").asInt()).isEqualTo(1);
		assertThat(explicit.get("color").asInt()).isEqualTo(1);
		assertThat(explicit.get("style").asInt()).isEqualTo(1);
	}

	/** 範圍內任何值原樣存原樣回（超出裝置端對照表也照收），且兩者互不干擾。 */
	@Test
	void styleCodesInRangePassThroughIndependently() throws Exception {
		Traveler me = traveler();
		Map<String, Object> body = body(me, "只給 style");
		body.put("style", 32767);

		JsonNode onlyStyle = created(drop(me, body));

		assertThat(onlyStyle.get("color").asInt()).isEqualTo(1);
		assertThat(onlyStyle.get("style").asInt()).isEqualTo(32767);

		Map<String, Object> both = body(me, "對照表外的代號照收");
		both.put("color", 9999);
		both.put("style", 12345);
		JsonNode note = created(drop(me, both));
		assertThat(note.get("color").asInt()).isEqualTo(9999);
		assertThat(note.get("style").asInt()).isEqualTo(12345);
	}

	@ParameterizedTest(name = "{0} = {1}")
	@MethodSource
	void styleCodesOutOfRangeAreRejected(String field, int value) throws Exception {
		Traveler me = traveler();
		Map<String, Object> body = body(me, "越界代號");
		body.put(field, value);

		assertThat(assertProblem(drop(me, body), 400, "invalid_style_code").has("details")).isFalse();
	}

	static Stream<Arguments> styleCodesOutOfRangeAreRejected() {
		return Stream.of("color", "style")
			.flatMap((field) -> IntStream.of(0, -1, 32768, 65536).mapToObj((v) -> Arguments.of(field, v)));
	}

	@Test
	void audienceDefaultsToAnyoneWhenOmittedOrNull() throws Exception {
		Traveler me = traveler();
		Map<String, Object> explicit = body(me, "明確給 null");
		explicit.put("audience", null);

		assertThat(created(drop(me, body(me, "整個省略"))).get("audience").asString()).isEqualTo("anyone");
		assertThat(created(drop(me, explicit)).get("audience").asString()).isEqualTo("anyone");
	}

	/** 不認得就拒絕，不比對大小寫、不 trim——猜錯的成本是「私密內容變公開」。 */
	@ParameterizedTest(name = "audience={0}")
	@ValueSource(strings = { "Anyone", "SELF", " anyone ", "public", "everyone", "" })
	void unknownAudienceIsRejected(String audience) throws Exception {
		Traveler me = traveler();
		Map<String, Object> body = body(me, "不認得的 audience");
		body.put("audience", audience);

		assertProblem(drop(me, body), 400, "invalid_audience");
	}

	// ─── 兩個上限 ────────────────────────────────────────────────────────────

	@Test
	void theFiftyFirstActiveNoteIsRejected() throws Exception {
		Traveler me = traveler();
		seed(me, 50, "anyone", "now()", null);

		JsonNode problem = assertProblem(drop(me, body(me, "第 51 張")), 422, "active_note_limit");

		assertThat(problem.get("details").get("maxActiveNotes").asInt()).isEqualTo(50);
	}

	/** 額度只算「未撿、未過期」的：被撿走或過期就釋放。 */
	@Test
	void pickedUpAndExpiredNotesDoNotCountTowardsTheActiveLimit() throws Exception {
		Traveler picker = traveler();
		Traveler me = traveler();
		seed(me, 49, "anyone", "now()", null);
		seed(me, 1, "anyone", "now()", picker.id());
		seed(me, 1, "anyone", "now() - interval '91 days'", null);

		assertThat(created(drop(me, body(me, "額度還在")))).isNotNull();
	}

	@Test
	void theFiveThousandAndFirstPrivateNoteIsRejected() throws Exception {
		Traveler me = traveler();
		seed(me, 5000, "self", "now()", null);
		Map<String, Object> body = body(me, "第 5001 張");
		body.put("audience", "self");

		JsonNode problem = assertProblem(drop(me, body), 422, "private_note_limit");

		assertThat(problem.get("details").get("maxPrivateNotes").asInt()).isEqualTo(5000);
	}

	/** 兩個閘門互不影響：公開滿了仍可記錄旅程，旅遊紀錄滿了仍可留公開便條。 */
	@Test
	void theTwoLimitsAreIndependent() throws Exception {
		Traveler publicFull = traveler();
		seed(publicFull, 50, "anyone", "now()", null);
		Map<String, Object> stillPrivate = body(publicFull, "公開滿了還是記得下旅程");
		stillPrivate.put("audience", "self");
		assertThat(created(drop(publicFull, stillPrivate)).get("audience").asString()).isEqualTo("self");

		Traveler privateFull = traveler();
		seed(privateFull, 5000, "self", "now()", null);
		assertThat(created(drop(privateFull, body(privateFull, "旅程滿了還是留得下便條"))).get("audience").asString())
			.isEqualTo("anyone");
	}

	/**
	 * 併發超越量（票 09）。計數與 INSERT 雖然是同一句，但 READ COMMITTED 下每個語句各自取
	 * 快照 ⇒ 同時在途的請求會看到同一個「還沒滿」的計數，於是一起擠進去。兩個上限因此是
	 * **advisory** 的（ADR-0003／0011 明列的已接受後果）：它防的是匿名帳號灑滿地圖，不是精準配額。
	 *
	 * <p>斷言的上界是 **Hikari 池大小**，不是在途請求數：同時只有 {@code poolSize} 條語句
	 * 到得了資料庫，全部看到同一個「還沒滿」的計數就是最壞情況 ⇒ 至多 {@code 上限 − 1 ＋ 池}。
	 * 用在途請求數當上界的話（50 上限、40 平行 → 允許到 89 張）連 SQL 版的 65 都會通過，
	 * 那種斷言擋不住任何退化。實際張數另外印出來記進票 09。
	 *
	 * <p>真的需要硬上限，修法是 DB constraint／trigger，不是把規則搬回 RPC。
	 */
	@ParameterizedTest(name = "{0} 上限 {1}、{2} 條平行請求")
	@MethodSource
	void concurrentDropsOvershootTheLimitByAtMostTheNumberInFlight(String audience, int limit, int inFlight)
			throws Exception {
		Traveler me = traveler();
		seed(me, limit - 1, audience, "now()", null);
		Map<String, Object> body = body(me, "併發");
		body.put("audience", audience);
		HttpRequest request = dropRequest(me, JSON.writeValueAsString(body));
		HttpClient client = HttpClient.newHttpClient();

		// 中間這個 toList 不可省：少了它就變成一條送完才送下一條，根本沒有競爭。
		List<CompletableFuture<HttpResponse<String>>> calls = IntStream.range(0, inFlight)
			.mapToObj((i) -> client.sendAsync(request, BodyHandlers.ofString(StandardCharsets.UTF_8)))
			.toList();
		calls.forEach(CompletableFuture::join);

		long stored = admin()
			.sql("select count(*) from public.notes where author_id = ?::uuid and audience = ?")
			.params(me.id().toString(), audience)
			.query(Long.class)
			.single();
		System.out.printf("[票 09 併發超越量] audience=%s 上限=%d 平行=%d → 實際 %d 張（超越 %d）%n", audience, limit,
				inFlight, stored, stored - limit);
		assertThat(stored).describedAs("至少一條要成功，且超越量以 Hikari 池大小為界")
			.isBetween((long) limit, (long) (limit - 1 + this.poolSize));
	}

	static Stream<Arguments> concurrentDropsOvershootTheLimitByAtMostTheNumberInFlight() {
		return Stream.of(Arguments.of("anyone", 50, 20), Arguments.of("anyone", 50, 40),
				Arguments.of("self", 5000, 20), Arguments.of("self", 5000, 40));
	}

	// ─── 錯誤信封 ────────────────────────────────────────────────────────────

	/** 「有 code 才是業務錯誤」——型別／格式錯誤不得有 code。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void malformedRequestsGet400WithoutACode(String variant, String body) throws Exception {
		Traveler me = traveler();

		var response = post("/v1/notes", me, body);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse(""))
			.startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).has("code")).isFalse();
	}

	static Stream<Arguments> malformedRequestsGet400WithoutACode() {
		return Stream.of(Arguments.of("非法 JSON", "{"), Arguments.of("空 body", ""),
				Arguments.of("latitude 給字串",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":\"35.6\",\"longitude\":139.7}}"),
				Arguments.of("color 給字串",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7},\"color\":\"紅\"}"),
				Arguments.of("缺 content", "{\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				Arguments.of("缺 coordinate", "{\"content\":\"a\"}"),
				// 座標少一半與缺整個 coordinate 同一類——openapi 的 required 兩層都列了
				// latitude／longitude，invalid_coordinates 只留給「值在、但越界」。
				Arguments.of("coordinate 缺 longitude", "{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6}}"),
				Arguments.of("latitude 給 null",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":null,\"longitude\":139.7}}"),
				Arguments.of("content 給 null",
						"{\"content\":null,\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				// 代號的非整數與超出 32 位元同樣是型別錯誤，不是 invalid_style_code（notes.md §3）。
				Arguments.of("color 給小數",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7},\"color\":1.5}"),
				Arguments.of("style 給小數",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7},\"style\":2.0}"),
				Arguments.of("color 超出 32 位元整數",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7},"
								+ "\"color\":99999999999}"),
				// 反方向那一格：數值欄位給字串上面已經守住了，字串欄位給數值同樣是型別錯誤。
				// Jackson 對「純量 → 字串」預設寬鬆，123 會被靜默轉成 "123" 存進去。
				Arguments.of("content 給數字",
						"{\"content\":123,\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				Arguments.of("content 給小數",
						"{\"content\":1.5,\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				Arguments.of("content 給布林",
						"{\"content\":true,\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				// audience 給數值若被轉成字串，會拿到 invalid_audience（有 code）——
				// 那是業務錯誤的形狀，而這是型別錯誤。
				Arguments.of("audience 給數字",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7},"
								+ "\"audience\":5}"),
				Arguments.of("audience 給布林",
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7},"
								+ "\"audience\":true}"),
				// U+0000 是合法的 JSON 逸出，但 Postgres 的 text 存不下它，而契約的 trim 字元集
				// 也不含它（它不是空白）。不擋就是一路撞到 INSERT——任何拿得到 token 的人都能
				// 無限次把 500 ＋ 堆疊打進日誌。屬格式錯誤那一桶，不新增契約 token。
				Arguments.of("content 含 U+0000",
						"{\"content\":\"a\\u0000b\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				// 孤立代理對（沒配對的 \ud800 / \udc00）是合法的 JSON 逸出，但不是合法的 Unicode
				// 純量值：轉 UTF-8 時被靜默換成 U+FFFD／「?」存進去，使用者送的與存的不同、
				// 兩側都沒有訊號。與 U+0000 同一桶：格式錯誤、400 無 code，不新增契約 token。
				Arguments.of("content 含孤立高代理",
						"{\"content\":\"a\\ud800b\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"),
				Arguments.of("content 含孤立低代理",
						"{\"content\":\"a\\udc00b\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"));
	}

	/** 對照組：配對正確的代理對（星群平面字元）照常 200 且原樣保存——孤立代理的閘門不得誤傷 emoji。 */
	@Test
	void pairedSurrogatesAreStoredVerbatim() throws Exception {
		Traveler me = traveler();

		JsonNode note = created(post("/v1/notes", me,
				"{\"content\":\"a\\ud83d\\ude00b\",\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}}"));

		assertThat(note.get("content").asString()).isEqualTo("a\ud83d\ude00b");
	}

	/** 沒有附帶資料的 token 不得有 details 鍵（client 不必分辨「沒有」與「null」）。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void tokensWithoutExtraDataCarryNoDetails(String code, int status, String body) throws Exception {
		Traveler me = traveler();

		JsonNode problem = assertProblem(post("/v1/notes", me, body), status, code);

		assertThat(problem.has("details")).isFalse();
	}

	static Stream<Arguments> tokensWithoutExtraDataCarryNoDetails() {
		String coordinate = "\"coordinate\":{\"latitude\":35.6,\"longitude\":139.7}";
		return Stream.of(
				Arguments.of("content_empty", 400, "{\"content\":\"　　\"," + coordinate + "}"),
				Arguments.of("invalid_coordinates", 400,
						"{\"content\":\"a\",\"coordinate\":{\"latitude\":95,\"longitude\":139.7}}"),
				Arguments.of("invalid_style_code", 400, "{\"content\":\"a\"," + coordinate + ",\"color\":0}"),
				Arguments.of("invalid_audience", 400, "{\"content\":\"a\"," + coordinate + ",\"audience\":\"nope\"}"));
	}

	@Test
	void droppingNeedsAToken() throws Exception {
		assertThat(post("/v1/notes", null, "{}").statusCode()).isEqualTo(401);
	}

	// ─── 工具 ────────────────────────────────────────────────────────────────

	/** 一個旅人：auth.users 上真的有這列（notes.author_id 的 FK 要），外加一塊自己的地盤。 */
	record Traveler(UUID id, String token, double lat, double lng) {
	}

	private static Traveler traveler() {
		UUID id = UUID.randomUUID();
		admin().sql("insert into auth.users (id) values (?::uuid)").param(id.toString()).update();
		double lat = BASE_LAT + SLOT.getAndIncrement() % 30;
		return new Traveler(id, TestJwt.valid(id), lat, BASE_LNG);
	}

	/** 直接以超級使用者塞列：上限測試要 5000 張，走 HTTP 太慢。 */
	private static void seed(Traveler author, int count, String audience, String createdAt, UUID pickedUpBy) {
		admin()
			.sql("insert into public.notes (author_id, content, lat, lng, audience, created_at, picked_up_by,"
					+ " picked_up_at) select ?::uuid, 'seed', ?, ?, ?, " + createdAt + ", ?::uuid,"
					+ " case when ?::uuid is null then null else now() end from generate_series(1, ?)")
			.params(author.id().toString(), author.lat(), author.lng(), audience,
					(pickedUpBy == null) ? null : pickedUpBy.toString(),
					(pickedUpBy == null) ? null : pickedUpBy.toString(), count)
			.update();
	}

	private Map<String, Object> body(Traveler traveler, String content) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("content", content);
		body.put("coordinate", coordinate(traveler.lat(), traveler.lng()));
		return body;
	}

	private static Map<String, Object> coordinate(double latitude, double longitude) {
		Map<String, Object> coordinate = new LinkedHashMap<>();
		coordinate.put("latitude", latitude);
		coordinate.put("longitude", longitude);
		return coordinate;
	}

	private HttpResponse<String> drop(Traveler traveler, Map<String, Object> body) throws Exception {
		return post("/v1/notes", traveler, JSON.writeValueAsString(body));
	}

	/** 併發測試要先把全部請求送出去才 join，所以這裡只組請求、不送。 */
	private HttpRequest dropRequest(Traveler traveler, String body) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/v1/notes"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + traveler.token())
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
	}

	private HttpResponse<String> post(String path, Traveler traveler, String body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (traveler != null) {
			request.header("Authorization", "Bearer " + traveler.token());
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> get(String path, Traveler traveler) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Authorization", "Bearer " + traveler.token());
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static JsonNode created(HttpResponse<String> response) throws Exception {
		// 200 而非 201：契約沒有單張便條的 GET 路徑，沒有 Location 可指。
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

	private static JsonNode assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.path("type").asString()).describedAs(response.body()).isEqualTo("about:blank");
		assertThat(problem.path("status").asInt()).describedAs(response.body()).isEqualTo(status);
		assertThat(problem.path("code").asString()).describedAs(response.body()).isEqualTo(code);
		// 業務錯誤的 title 等於 code（給人看的摘要，client 不得比對）。
		assertThat(problem.path("title").asString()).describedAs(response.body()).isEqualTo(code);
		return problem;
	}

	private static Set<String> fieldNames(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
	}

	private static Instant instant(JsonNode wireTimestamp) {
		return Instant.from(WIRE.parse(wireTimestamp.asString()));
	}

}
