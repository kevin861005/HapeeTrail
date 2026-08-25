package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * 票 06：{@code GET /v1/me/notes} 的 limit 與游標分頁。情境清單＝
 * {@code supabase/tests/notes.test.sql} 的「列表 RPC：envelope ＋ 不透明游標」段，逐條搬過來。
 *
 * <p>只驗**外部可觀察的行為**：原樣回傳可翻頁、竄改被拒、{@code nextCursor} 為 null ＝ 結束。
 * 游標的內部編碼是實作細節，刻意不斷言——斷言它，改編碼就會誤紅。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MyNotesTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	@LocalServerPort
	int port;

	// ─── limit ───────────────────────────────────────────────────────────────

	/**
	 * 契約：省略 ＝ 50，越界**不報錯**、靜默夾到 1–100。越界報錯的話，client 只要算錯一次
	 * 頁碼就整個列表打不開；夾住則最多是「這一頁少幾筆」。
	 */
	@Test
	void limitIsOmittedFiftyAndSilentlyClampedToOneHundred() throws Exception {
		Traveler me = traveler();
		seed(me, 101, "now() - make_interval(secs => g)", null);

		assertThat(items(page(me, "")).size()).describedAs("省略 limit").isEqualTo(50);
		assertThat(items(page(me, "?limit=0")).size()).describedAs("0 → 1").isEqualTo(1);
		assertThat(items(page(me, "?limit=-5")).size()).describedAs("負數 → 1").isEqualTo(1);
		assertThat(items(page(me, "?limit=101")).size()).describedAs("101 → 100").isEqualTo(100);
		assertThat(items(page(me, "?limit=100")).size()).describedAs("100 是上界，照收").isEqualTo(100);
		// 大到爆 int 仍然只是「越界」。用 Integer 接參數的話這裡會是 400，契約在那裡靜默破掉。
		assertThat(items(page(me, "?limit=99999999999")).size()).describedAs("超過 int32 → 100").isEqualTo(100);
	}

	/** {@code limit} 不是整數是型別錯誤：400 且**沒有 code**（契約 §2 的唯一閘門）。 */
	@ParameterizedTest(name = "limit={0}")
	@MethodSource
	void nonIntegerLimitIs400WithoutACode(String limit) throws Exception {
		var response = get("/v1/me/notes?limit=" + limit, traveler());

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(400);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).has("code")).describedAs(response.body()).isFalse();
	}

	static Stream<String> nonIntegerLimitIs400WithoutACode() {
		return Stream.of("abc", "1.5");
	}

	// ─── 翻頁 ────────────────────────────────────────────────────────────────

	/**
	 * 29 張以每頁 10 走完：不重複、不遺漏、(createdAt, id) 嚴格遞減。
	 * 終止訊號只看 {@code nextCursor} 為 null——不靠「多打一次拿到空陣列」。
	 */
	@Test
	void walksEveryNoteExactlyOnceNewestFirst() throws Exception {
		Traveler me = traveler();
		seed(me, 29, "now() - make_interval(secs => g)", null);

		List<String> walked = walk(me, 10);

		assertThat(walked).hasSize(29).doesNotHaveDuplicates().containsExactlyElementsOf(newestFirst(me));
	}

	/**
	 * 全部同刻的便條——複合游標平手邏輯的壓力測試。只用 {@code createdAt} 當游標的話，
	 * 這裡會無限翻頁或整批重複。
	 */
	@Test
	void notesCreatedAtTheSameInstantPaginateWithoutOverlap() throws Exception {
		Traveler me = traveler();
		seed(me, 60, "'2026-01-01T00:00:00Z'::timestamptz", null);

		List<String> walked = walk(me, 25);

		assertThat(walked).hasSize(60).doesNotHaveDuplicates().containsExactlyElementsOf(newestFirst(me));
	}

	/** 頁大小恰等於總筆數 ⇒ nextCursor 必為 null（旅人不必為了確認結束多轉一次載入圈）。 */
	@Test
	void anExactFitPageEndsPagination() throws Exception {
		Traveler me = traveler();
		seed(me, 29, "now() - make_interval(secs => g)", null);

		JsonNode page = page(me, "?limit=29");

		assertThat(items(page)).hasSize(29);
		assertThat(page.get("nextCursor").isNull()).describedAs(page.toString()).isTrue();
	}

	/** 跨使用者隔離的正面斷言：B 有 29 張，A 一張也看不到。 */
	@Test
	void travelersOnlySeeTheirOwnNotes() throws Exception {
		Traveler b = traveler();
		seed(b, 29, "now() - make_interval(secs => g)", null);
		Traveler a = traveler();

		assertThat(get("/v1/me/notes", a).body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");
	}

	/**
	 * 這份列表是「我留過的**全部**」：公開便條與旅遊紀錄、已被撿走的與已過期的，一張都不少
	 * （ADR-0010／spec user story 15：不會有便條莫名消失）。
	 * 「還在地圖上嗎」是 client 讀 pickedUpAt 與 expiresAt 自己判斷的事，不是列表篩掉的。
	 */
	@Test
	void everyKindOfNoteTheTravelerLeftStaysInTheList() throws Exception {
		Traveler me = traveler();
		Traveler picker = traveler();
		seed(me, 1, "now()", picker.id());
		seed(me, 1, "now() - make_interval(days => 91)", null);
		seed(me, 1, "now()", null);
		seed(me, 1, "now()", null, "self");

		List<JsonNode> items = items(page(me, ""));

		assertThat(items).hasSize(4);
		assertThat(items.stream().map((n) -> n.get("audience").asString()))
			.describedAs("公開便條與旅遊紀錄都在，以 audience 分辨")
			.containsOnlyOnce("self")
			.contains("anyone");
		assertThat(items.stream().filter((n) -> !n.get("pickedUpAt").isNull())).describedAs("已撿走的仍在")
			.hasSize(1);
		assertThat(items.stream()
			.filter((n) -> !n.get("expiresAt").isNull())
			.filter((n) -> Instant.from(WIRE.parse(n.get("expiresAt").asString())).isBefore(Instant.now())))
			.describedAs("已過期的仍在")
			.hasSize(1);
	}

	// ─── 壞游標 ──────────────────────────────────────────────────────────────

	/**
	 * 無法解碼、被竄改、版本不符、不是本列表的游標一律大聲失敗——靜默退化成第一頁會掉列或
	 * 無限翻頁。不手寫游標編碼：{@code {"hello":"world"}} 解得開但版本讀出來不是 1，
	 * 走的就是版本閘門那一行（未來版本 2 的游標走同一條路），斷言的是行為不是編碼。
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void badCursorsAreRejected(String variant, String cursor) throws Exception {
		assertProblem(get("/v1/me/notes?cursor=" + cursor, traveler()), 400, "invalid_cursor");
	}

	static Stream<Arguments> badCursorsAreRejected() {
		String foreignJson = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));
		return Stream.of(Arguments.of("不是 base64", "%25%25%25"), Arguments.of("空字串", ""),
				Arguments.of("解得開但不是游標", foreignJson), Arguments.of("亂字串", "not-a-cursor"));
	}

	/** 竄改（截斷）一枚真游標。 */
	@Test
	void aTamperedCursorIsRejected() throws Exception {
		Traveler me = traveler();
		seed(me, 5, "now() - make_interval(secs => g)", null);
		String cursor = page(me, "?limit=1").get("nextCursor").asString();

		assertProblem(get("/v1/me/notes?cursor=" + cursor.substring(0, 20), me), 400, "invalid_cursor");
	}

	/**
	 * 屬於其他列表的游標一律拒絕：收藏依 {@code pickedUpAt} 排序，拿我的便條的游標去比它
	 * 會靜默回錯頁而毫無訊號。收藏端點還不存在（票 08），所以這一枚游標由 {@code Cursor}
	 * 自己鑄——**不是**手寫編碼：改編碼時它跟著改，這條測試不會誤紅。
	 * 斷言的仍是外部行為：400 ＋ {@code invalid_cursor}。
	 */
	@Test
	void aCursorFromAnotherListIsRejected() throws Exception {
		String foreign = new Cursor("my_collection", OffsetDateTime.now(), UUID.randomUUID()).encode();

		assertProblem(get("/v1/me/notes?cursor=" + foreign, traveler()), 400, "invalid_cursor");
	}

	// ─── helpers ─────────────────────────────────────────────────────────────

	/** 一個旅人：auth.users 上真的有這列（notes.author_id 的 FK 要）。 */
	record Traveler(UUID id, String token) {
	}

	private static Traveler traveler() {
		UUID id = UUID.randomUUID();
		admin().sql("insert into auth.users (id) values (?::uuid)").param(id.toString()).update();
		return new Traveler(id, TestJwt.valid(id));
	}

	/**
	 * 直接以超級使用者塞列：分頁測試要上百張，走 HTTP 太慢，而且留便條的規則不是本票要驗的事。
	 * {@code createdAt} 是 SQL 片段，可用 {@code g}（generate_series 的序號）錯開時間。
	 */
	private static void seed(Traveler author, int count, String createdAt, UUID pickedUpBy) {
		seed(author, count, createdAt, pickedUpBy, "anyone");
	}

	private static void seed(Traveler author, int count, String createdAt, UUID pickedUpBy, String audience) {
		admin()
			.sql("insert into public.notes (author_id, content, lat, lng, created_at, picked_up_by, picked_up_at,"
					+ " audience) select ?::uuid, 'seed', 0, 0, " + createdAt + ", ?::uuid,"
					+ " case when ?::uuid is null then null else now() end, ? from generate_series(1, ?) g")
			.params(author.id().toString(), (pickedUpBy == null) ? null : pickedUpBy.toString(),
					(pickedUpBy == null) ? null : pickedUpBy.toString(), audience, count)
			.update();
	}

	/** 期望的順序，由資料庫直接算——測試不重算一次分頁邏輯。 */
	private static List<String> newestFirst(Traveler traveler) {
		return admin()
			.sql("select id::text from public.notes where author_id = ?::uuid order by created_at desc, id desc")
			.param(traveler.id().toString())
			.query(String.class)
			.list();
	}

	/**
	 * 照 client 的義務翻完整份列表：把 nextCursor **原樣**放回去，null 就停。
	 * 順帶守住兩件事：非 null 的游標保證真的還有資料、每頁不超過 limit。
	 */
	private List<String> walk(Traveler traveler, int limit) throws Exception {
		List<String> ids = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String cursor = null;
		while (true) {
			// 刻意不 URL-encode：游標要是編出 base64 的 + 或 /，query 會被解錯，這裡就紅。
			JsonNode page = page(traveler,
					"?limit=" + limit + ((cursor != null) ? "&cursor=" + cursor : ""));
			List<JsonNode> items = items(page);
			assertThat(items.size()).describedAs("每頁不得超過 limit").isLessThanOrEqualTo(limit);
			JsonNode previous = null;
			for (JsonNode item : items) {
				assertThat(seen.add(item.get("id").asString())).describedAs("跨頁重複").isTrue();
				if (previous != null) {
					assertThat(sortKey(item)).describedAs("(createdAt, id) 未嚴格遞減")
						.isLessThan(sortKey(previous));
				}
				previous = item;
				ids.add(item.get("id").asString());
			}
			if (page.get("nextCursor").isNull()) {
				return ids;
			}
			assertThat(items).describedAs("nextCursor 非 null 卻回了空頁").isNotEmpty();
			cursor = page.get("nextCursor").asString();
		}
	}

	private static String sortKey(JsonNode note) {
		return note.get("createdAt").asString() + "|" + note.get("id").asString();
	}

	private JsonNode page(Traveler traveler, String query) throws Exception {
		var response = get("/v1/me/notes" + query, traveler);
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		JsonNode page = JSON.readTree(response.body());
		// envelope 的形狀是契約：兩個鍵，不多不少。
		assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
		return page;
	}

	private static List<JsonNode> items(JsonNode page) {
		return page.get("items").valueStream().toList();
	}

	private HttpResponse<String> get(String path, Traveler traveler) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Authorization", "Bearer " + traveler.token());
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

}
