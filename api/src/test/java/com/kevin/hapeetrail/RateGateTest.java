package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 票 09 的頻率閘門：滾動一小時 60 次撿取。情境＝{@code supabase/tests/notes.test.sql} 的
 * 「D：一小時內第 61 次撿取」段，逐條搬過來。
 *
 * <p>閘門是**每個旅人各自**計數，而每條測試各用全新的旅人與一塊整數度的地盤
 * （1 度 ≈ 111km ≫ 100m），所以彼此不會互相把對方推過線。
 *
 * <p>窗內的 60 次撿取一律直接塞列（{@code picked_up_at} 由 SQL 給），走 HTTP 的話
 * 每條測試要打 60 次真請求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateGateTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	/** 位移只動緯度：每度的公尺數不隨經度改變。30m 撿得到、70m 是 {@code too_far}。 */
	private static final double M30 = 0.00027039;

	private static final double M70 = 0.00063090;

	private static final double BASE_LAT = ThreadLocalRandom.current().nextDouble(-60, 55);

	private static final double BASE_LNG = ThreadLocalRandom.current().nextDouble(-180, 180);

	private static final AtomicInteger SITE = new AtomicInteger();

	@LocalServerPort
	int port;

	// ─── 閘門的界線 ──────────────────────────────────────────────────────────

	/**
	 * 窗內已有 60 次 ⇒ 第 61 次被擋：429、契約的 token、算出來的 {@code retryAfterS}，
	 * 外加標準的 {@code Retry-After} header（iOS 的通用重試機制吃的是後者）。
	 */
	@Test
	void theSixtyFirstPickupWithinAnHourIsRateLimited() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seedPickups(site, me, 60, "now()");

		HttpResponse<String> response = pickup(me, seed(traveler(), site), site.lat() + M30, site.lng());

		JsonNode problem = assertProblem(response, 429, "pickup_rate_limited");
		int retryAfterS = problem.get("details").get("retryAfterS").asInt();
		// 60 次幾乎同刻、窗都還沒開始滑 ⇒ 幾乎是整個窗長。
		assertThat(retryAfterS).isBetween(3590, 3600);
		assertThat(response.headers().firstValue("retry-after")).describedAs("429 必須帶標準 Retry-After")
			.hasValue(String.valueOf(retryAfterS));
	}

	/** 窗內 59 次 ⇒ 第 60 次照樣撿得到。界線是 60，不是 59。 */
	@Test
	void theSixtiethPickupStillGoesThrough() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seedPickups(site, me, 59, "now()");

		assertThat(pickup(me, seed(traveler(), site), site.lat() + M30, site.lng()).statusCode()).isEqualTo(200);
	}

	/** 滾動窗：一小時又一分鐘前的 60 次已經滑出去了，計數是 0。 */
	@Test
	void pickupsOlderThanAnHourHaveRolledOutOfTheWindow() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seedPickups(site, me, 60, "now() - make_interval(mins => 61)");

		assertThat(pickup(me, seed(traveler(), site), site.lat() + M30, site.lng()).statusCode()).isEqualTo(200);
	}

	/**
	 * {@code retryAfterS} 是**算出來的**：第 60 近那次撿取離開窗口的秒數。
	 * 把它撥回 17 分鐘 ⇒ 3600 − 1020 ＝ 2580。寫死 3600 的實作在這裡紅。
	 */
	@Test
	void theRetryDelayIsMeasuredFromTheSixtiethNewestPickup() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seedPickups(site, me, 59, "now()");
		seedPickups(site, me, 1, "now() - make_interval(mins => 17)");

		JsonNode problem = assertProblem(pickup(me, seed(traveler(), site), site.lat() + M30, site.lng()), 429,
				"pickup_rate_limited");

		assertThat(problem.get("details").get("retryAfterS").asInt()).isBetween(2570, 2580);
	}

	// ─── 閘門下的冪等重試 ────────────────────────────────────────────────────

	/**
	 * 閘門跳起來時，對「已經是自己的那張」重試仍然成功、且回的是**原本**的
	 * {@code pickedUpAt}——冪等重試不新增任何撿取，擋下它等於在旅人撿得最勤的時候
	 * 收回契約 §6「timeout 後可安心重試同一筆」的承諾。同時確認閘門沒有因此被整個關掉。
	 */
	@Test
	void underTheGateRetryingYourOwnPickupStillSucceeds() throws Exception {
		Site site = site();
		Traveler me = traveler();
		List<UUID> mine = seedPickups(site, me, 60, "now()");
		UUID already = mine.getFirst();
		// 撥回 30 分鐘：仍在窗內（閘門照跳），但分得出重試回的是原本那次還是當下。
		admin().sql("update public.notes set picked_up_at = now() - make_interval(mins => 30) where id = ?::uuid")
			.param(already.toString())
			.update();

		JsonNode note = picked(pickup(me, already, site.lat() + M30, site.lng()));

		assertThat(note.get("id").asString()).isEqualTo(already.toString());
		assertThat(instant(note.get("pickedUpAt"))).describedAs("回的是原本那次，不是當下")
			.isCloseTo(Instant.now().minus(Duration.ofMinutes(30)), within(2, ChronoUnit.MINUTES));
		assertProblem(pickup(me, seed(traveler(), site), site.lat() + M30, site.lng()), 429, "pickup_rate_limited");
	}

	/**
	 * 限流下不得洩漏距離（T15 的立場）：70m 外的便條在沒有閘門時會回 403 {@code too_far}
	 * 附 {@code distanceM}，閘門跳起時只能回 429——否則刷爆自己的額度就換到一個距離 oracle。
	 */
	@Test
	void theGateNeverLeaksDistance() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seedPickups(site, me, 60, "now()");

		JsonNode problem = assertProblem(pickup(me, seed(traveler(), site), site.lat() + M70, site.lng()), 429,
				"pickup_rate_limited");

		assertThat(problem.get("details").propertyNames()).containsExactly("retryAfterS");
	}

	/**
	 * 「這張是不是我的」只在閘門跳起時才問：happy path 是閘門查詢 ＋ 一句 UPDATE，不多花。
	 *
	 * <p>用 {@code pg_stat_statements}（Supabase 映像預先載入）數這句 SQL 被執行幾次。
	 * 比對的字串必須只認得那一句：{@code and n.picked_up_by = $} 不會撞到閘門查詢的
	 * {@code where n.picked_up_by = $}，也不會撞到診斷查詢的 {@code n.picked_up_by = $ as is_mine}。
	 */
	@Test
	void theHappyPathDoesNotAskWhetherTheNoteIsAlreadyYours() throws Exception {
		Site site = site();
		Traveler unGated = traveler();
		long before = ownershipQueries();
		picked(pickup(unGated, seed(traveler(), site), site.lat() + M30, site.lng()));
		assertThat(ownershipQueries()).describedAs("沒被擋的撿取不該多查一次「這張是不是我的」").isEqualTo(before);

		Traveler gated = traveler();
		UUID already = seedPickups(site, gated, 60, "now()").getFirst();
		long beforeGated = ownershipQueries();
		picked(pickup(gated, already, site.lat() + M30, site.lng()));
		assertThat(ownershipQueries()).describedAs("閘門跳起時才問一次").isEqualTo(beforeGated + 1);
	}

	private static long ownershipQueries() {
		return admin()
			.sql("select coalesce(sum(calls), 0) from pg_stat_statements where query like '%and n.picked_up_by = $%'")
			.query(Long.class)
			.single();
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

	private static UUID seed(Traveler author, Site at) {
		return admin()
			.sql("insert into public.notes (author_id, content, lat, lng) values (?::uuid, 'seed', ?, ?) returning id")
			.params(author.id().toString(), at.lat(), at.lng())
			.query(UUID.class)
			.single();
	}

	/** 窗內（或窗外）已經撿過的便條，回傳它們的 id 好讓測試對其中一張重試。 */
	private static List<UUID> seedPickups(Site at, Traveler picker, int count, String pickedUpAt) {
		UUID author = traveler().id();
		return admin()
			.sql("insert into public.notes (author_id, content, lat, lng, picked_up_by, picked_up_at)"
					+ " select ?::uuid, 'seed', ?, ?, ?::uuid, " + pickedUpAt + " from generate_series(1, ?)"
					+ " returning id")
			.params(author.toString(), at.lat(), at.lng(), picker.id().toString(), count)
			.query(UUID.class)
			.list();
	}

	private HttpResponse<String> pickup(Traveler traveler, UUID id, double latitude, double longitude)
			throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/v1/notes/" + id
				+ "/pickup"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + traveler.token())
			.POST(BodyPublishers.ofString(
					"{\"coordinate\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}}",
					StandardCharsets.UTF_8))
			.build();
		return HttpClient.newHttpClient().send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static JsonNode picked(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

	private static JsonNode assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.path("code").asString()).describedAs(response.body()).isEqualTo(code);
		assertThat(problem.path("title").asString()).describedAs(response.body()).isEqualTo(code);
		assertThat(problem.path("status").asInt()).describedAs(response.body()).isEqualTo(status);
		return problem;
	}

	private static Instant instant(JsonNode wireTimestamp) {
		return Instant.from(WIRE.parse(wireTimestamp.asString()));
	}

}
