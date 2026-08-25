package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 票 07：{@code POST /v1/notes/nearby}。情境清單＝{@code supabase/tests/notes.test.sql} 的
 * 「nearby_notes：半徑、排序、pickable、排除自己」段，加上私人便條與 TTL 兩段對探索的斷言。
 *
 * <p>這是唯一跨使用者看得見便條的查詢，所以每條測試各佔一塊**整數度**的地盤
 * （1 度 ≈ 111km ≫ 100m 探索半徑）——別的測試（含別的測試類別）留下的便條因此不是
 * 「機率上」看不到，而是不變式。基準點隨機是同一個理由的另一半：共用資料庫上重跑不互擾。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NearbyTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** 契約凍結的 7 鍵。沒有 content、沒有任何 uuid 身分欄位——多一鍵就是外洩。 */
	private static final Set<String> HINT_KEYS = Set.of("id", "color", "style", "coordinate", "distanceM", "pickable",
			"createdAt");

	private static final Pattern WIRE_TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z$");

	/**
	 * 位移只動緯度：每度的公尺數不隨經度改變（110,574–111,412m／度，帶內差 0.76%），
	 * 隨機地點才能沿用這幾個手算的度數。與 {@code notes.test.sql} 同一組數字。
	 */
	private static final double M30 = 0.00027039;

	private static final double M70 = 0.00063090;

	private static final double M90 = 0.00081081;

	private static final double M130 = 0.00117167;

	/** 測試座標每次隨機；緯度帶 -60..55，加上最多幾度的地盤位移仍離 ±90 很遠。 */
	private static final double BASE_LAT = ThreadLocalRandom.current().nextDouble(-60, 55);

	private static final double BASE_LNG = ThreadLocalRandom.current().nextDouble(-180, 180);

	private static final AtomicInteger SITE = new AtomicInteger();

	@LocalServerPort
	int port;

	// ─── 半徑、距離、pickable ────────────────────────────────────────────────

	/**
	 * 100m 內看得見、50m 內撿得起。三張便條各站在判定的一側：30m（可見可撿）、
	 * 70m（可見不可撿）、130m（不可見）。距離是伺服器算的整數公尺，代號原樣上 wire
	 * ——地圖 pin 要拿作者當初選的代號才渲染得出對應樣式。
	 */
	@Test
	void thirtyMetresIsPickableSeventyIsVisibleAndOneThirtyIsNot() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler me = traveler();
		seed(author, site, M30);
		seedStyled(author, site, M70, 7, 3);
		seed(author, site, M130);

		List<JsonNode> hints = nearby(me, site);

		assertThat(hints).hasSize(2);
		JsonNode near = hints.get(0);
		assertThat(near.get("distanceM").asInt()).describedAs("30m").isBetween(29, 31);
		assertThat(near.get("pickable").asBoolean()).describedAs("50m 內").isTrue();
		assertThat(near.get("color").asInt()).isEqualTo(1);
		assertThat(near.get("style").asInt()).isEqualTo(1);
		JsonNode far = hints.get(1);
		assertThat(far.get("distanceM").asInt()).describedAs("70m").isBetween(69, 71);
		assertThat(far.get("pickable").asBoolean()).describedAs("50m 外").isFalse();
		// 非預設代號一路走到 wire：後端只存代號、不理解語意。
		assertThat(far.get("color").asInt()).isEqualTo(7);
		assertThat(far.get("style").asInt()).isEqualTo(3);
		// pin 的座標是**便條的**座標，不是探索中心。刻意不用完全相等：Supabase 的映像
		// 設了 extra_float_digits = 0，float8 以文字回傳時截到 15 位有效數字（DBL_DIG）
		// ——1e-9 度約 0.1mm，遠在任何地理意義之下，但足以抓到「回錯了一個點」。
		assertThat(far.get("coordinate").get("latitude").asDouble()).isCloseTo(site.lat() + M70, within(1e-9));
		assertThat(far.get("coordinate").get("longitude").asDouble()).isCloseTo(site.lng(), within(1e-9));
	}

	/** 零結果是空陣列而非 null，且 envelope 只有 items 一個鍵（探索無分頁）。 */
	@Test
	void nothingNearbyIsAnEmptyArray() throws Exception {
		var response = explore(traveler(), site());

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"items\":[]}");
	}

	// ─── 排除的四類 ──────────────────────────────────────────────────────────

	/**
	 * 同一點上五張便條，只有一張該出現：自己的（我的便條疊圖已經有了）、任何人的旅遊紀錄
	 * （含作者自己都看不到）、已撿走的、已過期的，四類都不進探索。
	 *
	 * <p>刻意不用「只放不該出現的、斷言為空」的寫法——那種測試在整支查詢壞掉時也會綠。
	 */
	@Test
	void ownPrivatePickedUpAndExpiredNotesAreExcluded() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler me = traveler();
		UUID visible = seed(author, site, 0);
		seed(me, site, 0);
		seed(author, site, 0, 1, "self", "now()", null, 1, 1);              // 旅遊紀錄
		seed(author, site, 0, 1, "anyone", "now()", me.id(), 1, 1);         // 已被撿走
		seed(author, site, 0, 1, "anyone", "now() - make_interval(days => 91)", null, 1, 1);   // 已過期

		List<JsonNode> hints = nearby(me, site);

		assertThat(hints.stream().map((h) -> UUID.fromString(h.get("id").asString()))).containsExactly(visible);
	}

	// ─── 上限與排序 ──────────────────────────────────────────────────────────

	/**
	 * 同點 25 張 → 恰 20 筆，而且是**最近的**那 20 張。90m 處另放 25 張：兩群都在半徑內，
	 * 回來的 20 張必須全部來自 0m 那群。
	 *
	 * <p>兩群都是 25 張不是湊數——先截斷再排序（漏掉 order by）的實作要恰好從 50 張裡
	 * 只挑中 0m 那 25 張中的 20 張，機率是 1/C(50,20) 的等級；只放一張遠的便條時，同一個
	 * 缺陷有兩成機率靜靜地綠。
	 */
	@Test
	void atMostTwentyHintsNearestFirst() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler me = traveler();
		seed(author, site, M90, 25);
		seed(author, site, 0, 25);

		List<JsonNode> hints = nearby(me, site);

		assertThat(hints).hasSize(20);
		assertThat(hints.stream().map((h) -> h.get("distanceM").asInt())).describedAs("回來的全是 0m 那群")
			.containsOnly(0);
	}

	// ─── 座標驗證 ────────────────────────────────────────────────────────────

	/** 值在、但越界：這是業務錯誤，帶 {@code code}。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void outOfRangeCoordinatesAreRejected(String variant, double latitude, double longitude) throws Exception {
		assertProblem(explore(traveler(), latitude, longitude), 400, "invalid_coordinates");
	}

	static Stream<Arguments> outOfRangeCoordinatesAreRejected() {
		return Stream.of(Arguments.of("緯度 > 90", 95.0, 139.7), Arguments.of("緯度 < -90", -95.0, 139.7),
				Arguments.of("經度 > 180", 35.6, 181.0), Arguments.of("經度 < -180", 35.6, -181.0));
	}

	/** 缺欄位／型別不對是格式錯誤那一類：400 但**沒有 code**（契約 §2 的唯一閘門）。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void malformedBodiesAre400WithoutACode(String variant, String body) throws Exception {
		var response = post("/v1/notes/nearby", traveler(), body);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).has("code")).describedAs(response.body()).isFalse();
	}

	static Stream<Arguments> malformedBodiesAre400WithoutACode() {
		return Stream.of(Arguments.of("完全沒有 body", ""), Arguments.of("沒有 coordinate", "{}"),
				Arguments.of("只有一半座標", "{\"coordinate\":{\"latitude\":35.6}}"),
				Arguments.of("座標是字串", "{\"coordinate\":{\"latitude\":\"35.6\",\"longitude\":139.7}}"),
				Arguments.of("不是 JSON", "not json"));
	}

	@Test
	void exploringNeedsAToken() throws Exception {
		assertThat(post("/v1/notes/nearby", null, "{}").statusCode()).isEqualTo(401);
	}

	// ─── 工具 ────────────────────────────────────────────────────────────────

	/** 一塊誰也踩不到的地盤（整數度位移），一條測試一塊。 */
	record Site(double lat, double lng) {
	}

	private static Site site() {
		return new Site(BASE_LAT + SITE.getAndIncrement() % 30, BASE_LNG);
	}

	record Traveler(UUID id, String token) {
	}

	private static Traveler traveler() {
		UUID id = UUID.randomUUID();
		admin().sql("insert into auth.users (id) values (?::uuid)").param(id.toString()).update();
		return new Traveler(id, TestJwt.valid(id));
	}

	private static UUID seed(Traveler author, Site at, double north) {
		return seed(author, at, north, 1);
	}

	private static UUID seed(Traveler author, Site at, double north, int count) {
		return seed(author, at, north, count, "anyone", "now()", null, 1, 1);
	}

	/** 非預設代號（其餘照預設）。 */
	private static UUID seedStyled(Traveler author, Site at, double north, int color, int style) {
		return seed(author, at, north, 1, "anyone", "now()", null, color, style);
	}

	/**
	 * 直接以超級使用者塞列：探索要看**別人**的便條，走 HTTP 得替每個作者開一次 session，
	 * 而且留便條的規則是票 05 驗的事，不是這裡。{@code north} 是相對地盤中心的緯度位移（度）。
	 */
	private static UUID seed(Traveler author, Site at, double north, int count, String audience, String createdAt,
			UUID pickedUpBy, int color, int style) {
		return admin()
			.sql("insert into public.notes (author_id, content, lat, lng, audience, created_at, picked_up_by,"
					+ " picked_up_at, color, style) select ?::uuid, 'seed', ?, ?, ?, " + createdAt + ", ?::uuid,"
					+ " case when ?::uuid is null then null else now() end, ?, ? from generate_series(1, ?)"
					+ " returning id")
			.params(author.id().toString(), at.lat() + north, at.lng(), audience,
					(pickedUpBy == null) ? null : pickedUpBy.toString(),
					(pickedUpBy == null) ? null : pickedUpBy.toString(), color, style, count)
			.query(UUID.class)
			.list()
			.getFirst();
	}

	/**
	 * 探索一次並把契約中「每次呼叫都成立」的形狀一併驗掉：envelope 只有 items、
	 * 每個提示恰 7 鍵、座標是巢狀物件、時間戳六位小數、距離不遞減（最近優先）。
	 */
	private List<JsonNode> nearby(Traveler traveler, Site at) throws Exception {
		var response = explore(traveler, at);
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		JsonNode result = JSON.readTree(response.body());
		assertThat(fieldNames(result)).describedAs("探索無分頁：envelope 只有 items").isEqualTo(Set.of("items"));
		List<JsonNode> hints = result.get("items").valueStream().toList();
		int previous = -1;
		for (JsonNode hint : hints) {
			assertThat(fieldNames(hint)).describedAs(hint.toString()).isEqualTo(HINT_KEYS);
			assertThat(fieldNames(hint.get("coordinate"))).isEqualTo(Set.of("latitude", "longitude"));
			assertThat(hint.get("createdAt").asString()).matches(WIRE_TS);
			assertThat(hint.get("distanceM").asInt()).describedAs("最近優先").isGreaterThanOrEqualTo(previous);
			previous = hint.get("distanceM").asInt();
		}
		return hints;
	}

	private HttpResponse<String> explore(Traveler traveler, Site at) throws Exception {
		return explore(traveler, at.lat(), at.lng());
	}

	private HttpResponse<String> explore(Traveler traveler, double latitude, double longitude) throws Exception {
		Map<String, Object> coordinate = new LinkedHashMap<>();
		coordinate.put("latitude", latitude);
		coordinate.put("longitude", longitude);
		return post("/v1/notes/nearby", traveler, JSON.writeValueAsString(Map.of("coordinate", coordinate)));
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

	private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.path("type").asString()).describedAs(response.body()).isEqualTo("about:blank");
		assertThat(problem.path("status").asInt()).describedAs(response.body()).isEqualTo(status);
		assertThat(problem.path("code").asString()).describedAs(response.body()).isEqualTo(code);
		assertThat(problem.path("title").asString()).describedAs(response.body()).isEqualTo(code);
	}

	private static Set<String> fieldNames(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
	}

}
