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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * 票 09 的 TTL：未撿的公開便條在 {@code createdAt} ＋ 90 天退出地圖。過期是**讀時推導**的
 * （ADR-0010，沒有欄位、沒有 cron），所以「同一刻退出」不是排程的巧合，而是三處
 * 用了逐字相同的半開區間 {@code created_at > now() - 90 天}——那個秒數在 Java 端只有
 * 一份（{@code NoteService.TTL}），三處都由它推導。
 *
 * <p>情境＝{@code supabase/tests/notes.test.sql} 的「TTL：未撿的公開便條 90 天後不再出現」段。
 * 邊界一律以直接寫入 {@code created_at} 製造——等 90 天不是一個測試策略。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TtlTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	/** 位移只動緯度：30m 在探索半徑（100m）與撿取半徑（50m）之內。 */
	private static final double M30 = 0.00027039;

	private static final double BASE_LAT = ThreadLocalRandom.current().nextDouble(-60, 55);

	private static final double BASE_LNG = ThreadLocalRandom.current().nextDouble(-180, 180);

	private static final AtomicInteger SITE = new AtomicInteger();

	@LocalServerPort
	int port;

	/**
	 * 三處同一刻：界線兩側各 10 秒的便條，在探索、撿取、額度三處的死活**完全一致**。
	 *
	 * <p>±10 秒才是這條的實心處。89／91 天那兩輪只證明界線落在 89 與 91 天之間，
	 * 某一處若用了 90.5 天照樣全綠；±10 秒把三處的界線一起釘在 90 天上。
	 *
	 * <p>{@code >} 與 {@code >=} 的差別**測不到**，別誤以為這裡守得住：兩者只在
	 * {@code created_at} 恰等於 cutoff 的那一個瞬間不同，而 {@code created_at} 由插入時的
	 * {@code now()} 決定、查詢時的 {@code now()} 已經前進了幾毫秒，那個瞬間造不出來。
	 * 「整整 90 天」那一輪因此等價於 91 天那輪，留著是為了對上票面文字。
	 *
	 * <p>順序刻意是探索 → 額度 → 撿取：撿取會改寫 {@code picked_up_at}，跑在前面的話
	 * 後兩處量到的是「被撿走」而不是「過期」。
	 */
	@ParameterizedTest(name = "{2}")
	@MethodSource
	void theSameBoundaryAppliesToExploringQuotaAndPicking(String age, boolean alive, String label) throws Exception {
		Site site = site();
		Traveler author = traveler();
		UUID id = seed(author, site, "anyone", agedBy(age));

		// ① 探索：100m 內看不看得到這個 pin
		List<String> pins = nearbyIds(traveler(), site.lat() + M30, site.lng());
		// ② 額度：把作者補到同齡的 50 張，第 51 張放不放得下
		seed(author, site, 49, "anyone", agedBy(age));
		HttpResponse<String> fiftyFirst = drop(author, site);
		// ③ 撿取
		HttpResponse<String> pickup = pickup(traveler(), id, site.lat() + M30, site.lng());

		if (alive) {
			assertThat(pins).describedAs("探索").contains(id.toString());
			assertProblem(fiftyFirst, 422, "active_note_limit");
			assertThat(pickup.statusCode()).describedAs("撿取：" + pickup.body()).isEqualTo(200);
		}
		else {
			assertThat(pins).describedAs("探索").doesNotContain(id.toString());
			assertThat(fiftyFirst.statusCode()).describedAs("額度：" + fiftyFirst.body()).isEqualTo(200);
			assertProblem(pickup, 404, "note_not_found");
		}
	}

	static Stream<Arguments> theSameBoundaryAppliesToExploringQuotaAndPicking() {
		// 括號不可省：agedBy 組出的是 `now() - <offset>`，少了它 `- interval '10 seconds'`
		// 會落在減號的右邊，兩輪的死活就對調了（第一次寫就是這樣紅的）。
		String ninety = "make_interval(days => 90)";
		return Stream.of(Arguments.of("make_interval(days => 89)", true, "89 天：三處都還活著"),
				Arguments.of("(" + ninety + " - interval '10 seconds')", true, "差 10 秒滿 90 天：三處都還活著"),
				Arguments.of("(" + ninety + " + interval '10 seconds')", false, "剛過 90 天 10 秒：三處都退出"),
				Arguments.of(ninety, false, "整整 90 天：三處都退出"),
				Arguments.of("make_interval(days => 91)", false, "91 天：三處都退出"));
	}

	/**
	 * 過期不等於消失：便條仍在作者的 my_notes 裡（那份紀錄的完整性是 user story 15 本身），
	 * {@code expiresAt} 仍是 {@code createdAt} ＋ 90 天——它是關於這張便條的事實，不隨狀態改變。
	 *
	 * <p>同時搬 {@code notes.test.sql} 的「400 天的旅遊紀錄」：TTL 只管公開便條，
	 * 旅遊紀錄放多久都在、{@code expiresAt} 恆為 null。同一份列表裡兩種命運，
	 * 才看得出 TTL 沒有誤傷隔壁那一種。
	 */
	@Test
	void anExpiredNoteStaysInItsAuthorsListAndAJournalNeverExpiresAtAll() throws Exception {
		Site site = site();
		Traveler author = traveler();
		String expired = seed(author, site, "anyone", days(91)).toString();
		String journal = seed(author, site, "self", days(400)).toString();

		Map<String, JsonNode> mine = JSON.readTree(get("/v1/me/notes", author).body())
			.get("items")
			.valueStream()
			.collect(Collectors.toMap((note) -> note.get("id").asString(), (note) -> note));

		assertThat(mine).describedAs("過期與否都不影響它在不在自己的列表裡").containsOnlyKeys(expired, journal);
		JsonNode note = mine.get(expired);
		assertThat(instant(note.get("expiresAt"))).isEqualTo(instant(note.get("createdAt")).plus(Duration.ofDays(90)));
		assertThat(instant(note.get("expiresAt"))).describedAs("91 天前投放 ⇒ expiresAt 已是過去").isBefore(Instant.now());
		assertThat(note.get("pickedUpAt").isNull()).isTrue();
		assertThat(mine.get(journal).get("expiresAt").isNull()).describedAs("旅遊紀錄不會過期").isTrue();
	}

	/**
	 * 已撿走的不受 TTL 影響：它早就離開地圖了，過期與否改變不了「有人撿走了」這個既成事實。
	 * 所以是 409 {@code note_taken}，不是 404——診斷順序把「已被撿走」排在「已過期」之前。
	 */
	@Test
	void anExpiredNoteThatWasAlreadyTakenIsStillAConflict() throws Exception {
		Site site = site();
		UUID id = seed(traveler(), site, "anyone", days(91));
		admin().sql("update public.notes set picked_up_by = ?::uuid, picked_up_at = now() where id = ?::uuid")
			.params(traveler().id().toString(), id.toString())
			.update();

		assertProblem(pickup(traveler(), id, site.lat() + M30, site.lng()), 409, "note_taken");
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

	/** {@code created_at} 的 SQL 運算式：由資料庫的時鐘往回推，測試不自己算時刻。 */
	private static String agedBy(String offset) {
		return "now() - " + offset;
	}

	private static String days(int age) {
		return agedBy("make_interval(days => " + age + ")");
	}

	private static UUID seed(Traveler author, Site at, String audience, String createdAt) {
		return seed(author, at, 1, audience, createdAt).getFirst();
	}

	private static List<UUID> seed(Traveler author, Site at, int count, String audience, String createdAt) {
		return admin()
			.sql("insert into public.notes (author_id, content, lat, lng, audience, created_at)"
					+ " select ?::uuid, 'seed', ?, ?, ?, " + createdAt + " from generate_series(1, ?) returning id")
			.params(author.id().toString(), at.lat(), at.lng(), audience, count)
			.query(UUID.class)
			.list();
	}

	private List<String> nearbyIds(Traveler traveler, double latitude, double longitude) throws Exception {
		HttpResponse<String> response = post("/v1/notes/nearby", traveler, coordinates(latitude, longitude));
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		return JSON.readTree(response.body())
			.get("items")
			.valueStream()
			.map((hint) -> hint.get("id").asString())
			.toList();
	}

	private HttpResponse<String> drop(Traveler traveler, Site at) throws Exception {
		return post("/v1/notes", traveler, "{\"content\":\"第 51 張\",\"coordinate\":{\"latitude\":" + at.lat()
				+ ",\"longitude\":" + at.lng() + "}}");
	}

	private HttpResponse<String> pickup(Traveler traveler, UUID id, double latitude, double longitude)
			throws Exception {
		return post("/v1/notes/" + id + "/pickup", traveler, coordinates(latitude, longitude));
	}

	private static String coordinates(double latitude, double longitude) {
		return "{\"coordinate\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}}";
	}

	private HttpResponse<String> post(String path, Traveler traveler, String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + traveler.token())
			.POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		return HttpClient.newHttpClient().send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> get(String path, Traveler traveler) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
			.header("Authorization", "Bearer " + traveler.token())
			.build();
		return HttpClient.newHttpClient().send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
		assertThat(JSON.readTree(response.body()).path("code").asString()).describedAs(response.body())
			.isEqualTo(code);
	}

	private static Instant instant(JsonNode wireTimestamp) {
		return Instant.from(WIRE.parse(wireTimestamp.asString()));
	}

}
