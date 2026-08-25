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
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 票 08：{@code POST /v1/notes/{id}/pickup} 與 {@code GET /v1/me/collection}。情境清單＝
 * {@code supabase/tests/notes.test.sql} 的「pickup_note：距離、獨佔、冪等、診斷碼」段
 * ＋「列表 RPC」段裡屬於 my_collection 的那幾條，逐條搬過來。
 *
 * <p>撿取跨使用者看得見便條，所以每條測試各佔一塊**整數度**的地盤（1 度 ≈ 111km ≫ 50m）
 * ——別的測試留下的便條因此不是「機率上」碰不到，而是不變式。
 *
 * <p>全類別的 HTTP 撿取次數刻意控制在 60 以內：票 09 會加上「滾動一小時 60 次」的閘門，
 * 越線的話這裡會在那時整批誤紅。批次資料一律直接塞列（不走 HTTP），且 {@code picked_up_at}
 * 撥到窗外。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PickupTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** 契約凍結的 9 鍵。撿起回的是完整的 Note，與留便條同一個形狀。 */
	private static final Set<String> NOTE_KEYS = Set.of("id", "content", "color", "style", "audience", "coordinate",
			"createdAt", "expiresAt", "pickedUpAt");

	private static final Pattern WIRE_TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z$");

	private static final DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	/** 位移只動緯度：每度的公尺數不隨經度改變，隨機地點才能沿用這兩個手算的度數。 */
	private static final double M30 = 0.00027039;

	private static final double M70 = 0.00063090;

	private static final double BASE_LAT = ThreadLocalRandom.current().nextDouble(-60, 55);

	private static final double BASE_LNG = ThreadLocalRandom.current().nextDouble(-180, 180);

	private static final AtomicInteger SITE = new AtomicInteger();

	@LocalServerPort
	int port;

	// ─── 距離與正常路徑 ──────────────────────────────────────────────────────

	/**
	 * 走進 50m 撿起：回的是完整的 9 鍵 Note，**content 在此揭露**（探索只給 pin），
	 * {@code pickedUpAt} 有值，座標仍是便條被投放的位置。
	 */
	@Test
	void thirtyMetresAwayPicksItUpAndRevealsTheContent() throws Exception {
		Site site = site();
		UUID id = seed(traveler(), site);
		Traveler me = traveler();

		JsonNode note = picked(pickup(me, id, site.lat() + M30, site.lng()));

		assertThat(fieldNames(note)).isEqualTo(NOTE_KEYS);
		assertThat(note.get("id").asString()).isEqualTo(id.toString());
		assertThat(note.get("content").asString()).describedAs("撿起才看得到內容").isEqualTo("seed");
		assertThat(note.get("pickedUpAt").asString()).matches(WIRE_TS);
		assertThat(note.get("audience").asString()).isEqualTo("anyone");
		// 撿走了仍保有 expiresAt——那是關於這張便條的事實，不隨狀態改變（ADR-0010）。
		assertThat(instant(note.get("expiresAt"))).isEqualTo(instant(note.get("createdAt")).plus(Duration.ofDays(90)));
		// 投放位置，不是撿起者的位置（第一階段不記錄撿起位置）。
		assertThat(note.get("coordinate").get("latitude").asDouble()).isCloseTo(site.lat(), within(1e-9));
		assertThat(note.get("coordinate").get("longitude").asDouble()).isCloseTo(site.lng(), within(1e-9));
	}

	/**
	 * 50m 外撿不走，而且拿到的是伺服器**當下**算的距離——不是 app 沿用上次探索的估計值。
	 * 距離是量測值，故以範圍斷言：寫死數字等於把 PostGIS 的算法抄進測試。
	 */
	@Test
	void seventyMetresAwayIsTooFarWithTheServersOwnDistance() throws Exception {
		Site site = site();
		UUID id = seed(traveler(), site);
		Traveler me = traveler();

		JsonNode problem = assertProblem(pickup(me, id, site.lat() + M70, site.lng()), 403, "too_far");

		assertThat(problem.get("details").get("distanceM").asInt()).isBetween(60, 80);
	}

	// ─── 診斷順序 ────────────────────────────────────────────────────────────

	/** 不存在的 id。合法 uuid、但沒有這張便條 ⇒ 業務錯誤（帶 code），不是格式錯誤。 */
	@Test
	void anUnknownNoteIsNotFound() throws Exception {
		Site site = site();

		assertProblem(pickup(traveler(), UUID.randomUUID(), site.lat(), site.lng()), 404, "note_not_found");
	}

	/** 已被別人撿走 → 409：pin 該從地圖上消失，client 重新探索。 */
	@Test
	void aNoteSomeoneElseAlreadyTookIsAConflict() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler winner = traveler();
		UUID id = seed(author, site);
		picked(pickup(winner, id, site.lat() + M30, site.lng()));

		assertProblem(pickup(traveler(), id, site.lat() + M30, site.lng()), 409, "note_taken");
	}

	/** 作者撿自己的 → 403，含自己的旅遊紀錄（對自己沒有隱藏的必要，順序在私人便條之前）。 */
	@Test
	void theAuthorCannotPickUpTheirOwnNote() throws Exception {
		Site site = site();
		Traveler author = traveler();
		UUID mine = seed(author, site);
		UUID myJournal = seed(author, site, "self", "now()");

		assertProblem(pickup(author, mine, site.lat() + M30, site.lng()), 403, "own_note");
		assertProblem(pickup(author, myJournal, site.lat() + M30, site.lng()), 403, "own_note");
	}

	/**
	 * 別人的旅遊紀錄與已過期的便條，回的都與「不存在」一模一樣。區分等於向外人確認
	 * 該座標存在一張他看不到的便條。
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void notesTheTravelerMayNotSeeAreIndistinguishableFromMissingOnes(String variant, String audience,
			String createdAt) throws Exception {
		Site site = site();
		UUID id = seed(traveler(), site, audience, createdAt);

		assertProblem(pickup(traveler(), id, site.lat() + M30, site.lng()), 404, "note_not_found");
	}

	static Stream<Arguments> notesTheTravelerMayNotSeeAreIndistinguishableFromMissingOnes() {
		return Stream.of(Arguments.of("別人的旅遊紀錄", "self", "now()"),
				Arguments.of("已過期（91 天）", "anyone", "now() - make_interval(days => 91)"));
	}

	// ─── 獨佔與冪等 ──────────────────────────────────────────────────────────

	/**
	 * 全世界只有一人撿得到同一張。10 條**真實平行**的 HTTP 請求搶同一張 → 恰 1 個 200、
	 * 9 個 409。獨佔的全部保證是那一句條件式 UPDATE：check 與 write 同語句同 row version。
	 */
	@Test
	void tenSimultaneousPickupsLeaveExactlyOneWinner() throws Exception {
		Site site = site();
		UUID id = seed(traveler(), site);
		List<Traveler> racers = IntStream.range(0, 10).mapToObj((i) -> traveler()).toList();
		HttpClient client = HttpClient.newHttpClient();

		// 先全部送出（中間這個 toList 不可省：少了它就變成一條送完才送下一條，根本沒有競爭）
		List<CompletableFuture<HttpResponse<String>>> calls = racers.stream()
			.map((racer) -> client.sendAsync(pickupRequest(racer, id, site.lat() + M30, site.lng()),
					BodyHandlers.ofString(StandardCharsets.UTF_8)))
			.toList();
		List<HttpResponse<String>> responses = calls.stream().map(CompletableFuture::join).toList();

		assertThat(responses.stream().filter((r) -> r.statusCode() == 200)).describedAs("恰一位贏家").hasSize(1);
		for (HttpResponse<String> loser : responses.stream().filter((r) -> r.statusCode() != 200).toList()) {
			assertProblem(loser, 409, "note_taken");
		}
	}

	/**
	 * 回應遺失後重試同一張仍成功，而且 {@code pickedUpAt} 是**原本那次**的時間。
	 * 手法沿用 T15：把第一次的撿取時刻撥回一天前，重試若回的是當下時間就會在這裡紅
	 * ——那正是「重試把撿取時間改寫掉」的症狀。
	 */
	@Test
	void retryingAPickupReturnsTheOriginalPickedUpAt() throws Exception {
		Site site = site();
		UUID id = seed(traveler(), site);
		Traveler me = traveler();
		picked(pickup(me, id, site.lat() + M30, site.lng()));
		admin().sql("update public.notes set picked_up_at = now() - make_interval(days => 1) where id = ?::uuid")
			.param(id.toString())
			.update();

		JsonNode retry = picked(pickup(me, id, site.lat() + M30, site.lng()));

		assertThat(retry.get("id").asString()).isEqualTo(id.toString());
		assertThat(instant(retry.get("pickedUpAt"))).describedAs("回的是原本那次，不是當下")
			.isBefore(Instant.now().minus(Duration.ofHours(12)));
	}

	/** 撿走之後：它從探索消失，而作者的 my_notes 裡它仍在、{@code pickedUpAt} 非 null。 */
	@Test
	void aPickedUpNoteLeavesTheMapButStaysInTheAuthorsList() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler me = traveler();
		UUID id = seed(author, site);
		picked(pickup(me, id, site.lat() + M30, site.lng()));

		JsonNode hints = JSON.readTree(post("/v1/notes/nearby", traveler(), coordinates(site.lat(), site.lng())).body())
			.get("items");
		assertThat(hints.valueStream().map((h) -> h.get("id").asString())).doesNotContain(id.toString());

		List<JsonNode> mine = items(JSON.readTree(get("/v1/me/notes", author).body()));
		assertThat(mine).hasSize(1);
		assertThat(mine.getFirst().get("pickedUpAt").asString()).matches(WIRE_TS);
	}

	/**
	 * 票上的「撿取與診斷在同一交易」，它的失敗模式是**靜默**的：{@code @Transactional} 只作用在
	 * public 方法上（{@code AnnotationTransactionAttributeSource.publicMethodsOnly} 預設為 true），
	 * 把 {@code pickup} 收成包內可見的話註解會被忽略，而上面每一條行為測試仍然全綠。
	 * 這條直接問 Spring 解不解得出交易屬性——收窄可見性或拿掉註解就紅。
	 */
	@Test
	void pickupReallyRunsInATransaction() throws Exception {
		var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
				NoteService.class.getDeclaredMethod("pickup", UUID.class, UUID.class, PickupRequest.class),
				NoteService.class);

		assertThat(attribute).describedAs("@Transactional 沒生效（pickup 不是 public？）").isNotNull();
	}

	// ─── 請求驗證 ────────────────────────────────────────────────────────────

	/** {@code {id}} 不是 uuid ＝ 格式錯誤：400 但**沒有 code**（契約 §2 的唯一閘門）。 */
	@Test
	void aNonUuidPathIdIs400WithoutACode() throws Exception {
		var response = post("/v1/notes/not-a-uuid/pickup", traveler(), coordinates(0, 0));

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).has("code")).describedAs(response.body()).isFalse();
	}

	/**
	 * 值在、但越界：這是業務錯誤，帶 {@code code}。順帶鎖住順序——id 是隨機 uuid（查不到），
	 * 仍然回 {@code invalid_coordinates}，證明座標驗證排在便條查詢之前。
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void outOfRangeCoordinatesAreRejected(String variant, double latitude, double longitude) throws Exception {
		assertProblem(pickup(traveler(), UUID.randomUUID(), latitude, longitude), 400, "invalid_coordinates");
	}

	static Stream<Arguments> outOfRangeCoordinatesAreRejected() {
		return Stream.of(Arguments.of("緯度 > 90", 95.0, 139.7), Arguments.of("緯度 < -90", -95.0, 139.7),
				Arguments.of("經度 > 180", 35.6, 181.0), Arguments.of("經度 < -180", 35.6, -181.0));
	}

	/** 缺欄位／型別不對是格式錯誤那一類：400 但**沒有 code**（契約 §2 的唯一閘門）。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void malformedBodiesAre400WithoutACode(String variant, String body) throws Exception {
		var response = post("/v1/notes/" + UUID.randomUUID() + "/pickup", traveler(), body);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(400);
		assertThat(JSON.readTree(response.body()).has("code")).describedAs(response.body()).isFalse();
	}

	static Stream<Arguments> malformedBodiesAre400WithoutACode() {
		return Stream.of(Arguments.of("完全沒有 body", ""), Arguments.of("沒有 coordinate", "{}"),
				Arguments.of("只有一半座標", "{\"coordinate\":{\"latitude\":35.6}}"),
				Arguments.of("座標是字串", "{\"coordinate\":{\"latitude\":\"35.6\",\"longitude\":139.7}}"),
				Arguments.of("不是 JSON", "not json"));
	}

	@Test
	void pickingUpNeedsAToken() throws Exception {
		assertThat(post("/v1/notes/" + UUID.randomUUID() + "/pickup", null, "{}").statusCode()).isEqualTo(401);
	}

	@Test
	void theCollectionNeedsAToken() throws Exception {
		assertThat(get("/v1/me/collection", null).statusCode()).isEqualTo(401);
	}

	// ─── 收藏列表 ────────────────────────────────────────────────────────────

	/**
	 * 撿到的便條進收藏，依**撿起時間**新→舊（不是投放時間——最舊的便條可能是今天撿的）。
	 * {@code audience} 恆為 {@code anyone}：旅遊紀錄撿不走，所以收藏裡不可能有 self。
	 */
	@Test
	void theCollectionIsNewestPickupFirst() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler me = traveler();
		// 投放順序與撿起順序相反：只有依 pickedUpAt 排序才會過。
		UUID older = seed(author, site, "anyone", "now() - make_interval(days => 2)");
		UUID newer = seed(author, site, "anyone", "now()");
		picked(pickup(me, newer, site.lat() + M30, site.lng()));
		picked(pickup(me, older, site.lat() + M30, site.lng()));

		List<JsonNode> items = items(page(me, ""));

		assertThat(items.stream().map((n) -> n.get("id").asString())).containsExactly(older.toString(),
				newer.toString());
		assertThat(items.stream().map((n) -> n.get("audience").asString())).containsOnly("anyone");
		assertThat(items.stream().map((n) -> n.get("pickedUpAt").asString())).allMatch(WIRE_TS.asMatchPredicate());
		assertThat(fieldNames(items.getFirst())).isEqualTo(NOTE_KEYS);
		// coordinate 是投放位置。
		assertThat(items.getFirst().get("coordinate").get("latitude").asDouble()).isCloseTo(site.lat(),
				within(1e-9));
	}

	/**
	 * 全部同刻撿起——複合游標平手邏輯的壓力測試。只用 {@code pickedUpAt} 當游標的話，
	 * 這裡會無限翻頁或整批重複。預設頁 50 ＋ 第二頁 10，且兩頁不重疊。
	 */
	@Test
	void pickupsAtTheSameInstantPaginateWithoutOverlap() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seedPicked(traveler(), site, me, 60, "'2026-01-01T00:00:00Z'::timestamptz");

		JsonNode first = page(me, "");
		assertThat(items(first)).describedAs("省略 limit ＝ 50").hasSize(50);
		JsonNode second = page(me, "?cursor=" + first.get("nextCursor").asString());
		assertThat(items(second)).hasSize(10);
		assertThat(second.get("nextCursor").isNull()).describedAs(second.toString()).isTrue();

		List<String> walked = new ArrayList<>(ids(first));
		walked.addAll(ids(second));
		assertThat(walked).doesNotHaveDuplicates().containsExactlyElementsOf(newestPickupFirst(me));
	}

	/** 跨使用者隔離的正面斷言：B 撿了 60 張，A 只撿 1 張 ⇒ A 恰 1 筆、沒有下一頁。 */
	@Test
	void travelersOnlySeeWhatTheyPickedUpThemselves() throws Exception {
		Site site = site();
		Traveler author = traveler();
		Traveler b = traveler();
		seedPicked(author, site, b, 60, "'2026-01-01T00:00:00Z'::timestamptz");
		Traveler a = traveler();
		UUID mine = seed(author, site);
		picked(pickup(a, mine, site.lat() + M30, site.lng()));

		JsonNode page = page(a, "");

		assertThat(ids(page)).containsExactly(mine.toString());
		assertThat(page.get("nextCursor").isNull()).describedAs(page.toString()).isTrue();
	}

	/**
	 * 收藏依 {@code pickedUpAt} 排序、我的便條依 {@code createdAt}：拿錯游標會靜默回錯頁
	 * 而毫無訊號，所以游標編碼了所屬列表，不符即大聲失敗。
	 */
	@Test
	void aCursorFromMyNotesIsRejected() throws Exception {
		Site site = site();
		Traveler me = traveler();
		seed(me, site);
		seed(me, site);
		String fromMyNotes = JSON.readTree(get("/v1/me/notes?limit=1", me).body()).get("nextCursor").asString();

		assertProblem(get("/v1/me/collection?cursor=" + fromMyNotes, me), 400, "invalid_cursor");
	}

	/** 壞游標的行為與我的便條一致（同一個 {@link Cursor} 編解碼處，變形清單在 MyNotesTest）。 */
	@Test
	void badCollectionCursorsAreRejected() throws Exception {
		Traveler me = traveler();

		assertProblem(get("/v1/me/collection?cursor=", me), 400, "invalid_cursor");
		assertProblem(get("/v1/me/collection?cursor=not-a-cursor", me), 400, "invalid_cursor");
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
		return seed(author, at, "anyone", "now()");
	}

	/** 直接以超級使用者塞列：撿取要看**別人**的便條，而留便條的規則是票 05 驗的事。 */
	private static UUID seed(Traveler author, Site at, String audience, String createdAt) {
		return admin()
			.sql("insert into public.notes (author_id, content, lat, lng, audience, created_at)"
					+ " values (?::uuid, 'seed', ?, ?, ?, " + createdAt + ") returning id")
			.params(author.id().toString(), at.lat(), at.lng(), audience)
			.query(UUID.class)
			.single();
	}

	/**
	 * 已撿起的批次資料。{@code pickedUpAt} 一律撥到過去：票 09 的頻率閘門只看滾動一小時內的
	 * 撿取，這批若落在窗內，那張票上線後這裡會整批誤紅。
	 */
	private static void seedPicked(Traveler author, Site at, Traveler picker, int count, String pickedUpAt) {
		admin()
			.sql("insert into public.notes (author_id, content, lat, lng, picked_up_by, picked_up_at)"
					+ " select ?::uuid, 'seed', ?, ?, ?::uuid, " + pickedUpAt + " from generate_series(1, ?)")
			.params(author.id().toString(), at.lat(), at.lng(), picker.id().toString(), count)
			.update();
	}

	/** 期望的順序，由資料庫直接算——測試不重算一次分頁邏輯。 */
	private static List<String> newestPickupFirst(Traveler picker) {
		return admin()
			.sql("select id::text from public.notes where picked_up_by = ?::uuid"
					+ " order by picked_up_at desc, id desc")
			.param(picker.id().toString())
			.query(String.class)
			.list();
	}

	private HttpResponse<String> pickup(Traveler traveler, UUID id, double latitude, double longitude)
			throws Exception {
		return HttpClient.newHttpClient()
			.send(pickupRequest(traveler, id, latitude, longitude), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpRequest pickupRequest(Traveler traveler, UUID id, double latitude, double longitude) {
		return HttpRequest.newBuilder(uri("/v1/notes/" + id + "/pickup"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + traveler.token())
			.POST(BodyPublishers.ofString(coordinates(latitude, longitude), StandardCharsets.UTF_8))
			.build();
	}

	private static String coordinates(double latitude, double longitude) {
		return "{\"coordinate\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}}";
	}

	private JsonNode page(Traveler traveler, String query) throws Exception {
		var response = get("/v1/me/collection" + query, traveler);
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		JsonNode page = JSON.readTree(response.body());
		assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
		return page;
	}

	private static List<JsonNode> items(JsonNode page) {
		return page.get("items").valueStream().toList();
	}

	private static List<String> ids(JsonNode page) {
		return items(page).stream().map((n) -> n.get("id").asString()).toList();
	}

	private HttpResponse<String> post(String path, Traveler traveler, String body) throws Exception {
		var request = HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (traveler != null) {
			request.header("Authorization", "Bearer " + traveler.token());
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> get(String path, Traveler traveler) throws Exception {
		var request = HttpRequest.newBuilder(uri(path));
		if (traveler != null) {
			request.header("Authorization", "Bearer " + traveler.token());
		}
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	private static JsonNode picked(HttpResponse<String> response) throws Exception {
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
