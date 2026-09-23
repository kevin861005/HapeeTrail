package com.kevin.hapeetrail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T28 票 04：註銷（CONTEXT.md〈註銷〉）。{@code DELETE /v1/me} → 服務經 GoTrue Admin API 硬刪
 * → FK cascade。GoTrue 由 {@link FakeGoTrue} 扮演：它對測試 DB 真刪使用者，所以 cascade、
 * session 檢查（ADR-0013）、探索與收藏的後果全部是真的，斷言都在 HTTP 那一側。
 */
// 呼叫 GoTrue 的那段 HTTP client 開到最囉唆：金鑰「任何日誌層級都不出現」要在最壞的層級驗。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "logging.level.org.springframework.web=TRACE", "logging.level.org.springframework.http=TRACE",
				// 正式值是 10s；測試不想真的等那麼久。這個值同時是**正常路徑**的上限
				// （FakeGoTrue 會真的跑一次 cascade delete），所以留足餘裕，只要比裝死的 5 秒短就驗得到。
				"hapeetrail.gotrue.timeout=1500ms" })
class AccountDeletionTest extends SupabaseDbTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String NOT_AUTHENTICATED = """
			{"type":"about:blank","status":401,"title":"not_authenticated","code":"not_authenticated"}""";

	@LocalServerPort
	int port;

	@AfterEach
	void backToTheRealGoTrue() {
		FakeGoTrue.reply(null);
	}

	/**
	 * 整條子彈：204 → 舊 token 立即 401（重試也是，且不再打 GoTrue）→ 他寫下的便條全部消失
	 * （地圖上的、已被撿進別人收藏的、旅遊紀錄）→ 他撿過的別人便條原封不動：留在原作者的紀錄裡、
	 * 仍是已撿走，不回地圖（{@code picked_up_by} 被 SET NULL，但 {@code picked_up_at} 還在）。
	 */
	@Test
	void deletingMyAccountTakesEverythingIWroteAndNothingElse() throws Exception {
		Traveler me = traveler();
		Traveler collector = traveler();
		Traveler explorer = traveler();
		Traveler author = traveler();
		double[] mine = site();
		double[] theirs = site();
		UUID onTheMap = seed(me, mine, "anyone", null);
		UUID inTheirCollection = seed(me, mine, "anyone", collector);
		seed(me, mine, "self", null);
		UUID iPickedUp = seed(author, theirs, "anyone", me);
		assertThat(nearby(explorer, mine)).describedAs("前提：地圖上有我的便條").contains(onTheMap.toString());
		assertThat(collection(collector)).describedAs("前提：別人收藏裡有我的便條").contains(inTheirCollection.toString());

		var response = deleteMe(me);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(204);
		assertThat(response.body()).isEmpty();

		assertNotAuthenticated(get("/v1/me/notes", me));
		int calls = FakeGoTrue.RECEIVED.size();
		assertNotAuthenticated(deleteMe(me));
		assertThat(FakeGoTrue.RECEIVED).describedAs("重試進不到 controller：session 已隨帳號消失").hasSize(calls);

		assertThat(nearby(explorer, mine)).describedAs("地圖上的").doesNotContain(onTheMap.toString());
		assertThat(collection(collector)).describedAs("別人收藏裡的：靜默少一件，不是壞掉的空殼").isEmpty();
		assertThat(admin().sql("select count(*) from public.notes where author_id = ?").param(me.id())
			.query(Integer.class).single()).describedAs("旅遊紀錄也一起").isZero();

		JsonNode kept = myNotes(author).stream().filter((n) -> n.get("id").asString().equals(iPickedUp.toString()))
			.findFirst().orElseThrow();
		assertThat(kept.get("pickedUpAt").isNull()).describedAs("仍是已撿走：" + kept).isFalse();
		assertThat(nearby(explorer, theirs)).describedAs("不回地圖").doesNotContain(iPickedUp.toString());
	}

	/**
	 * 送給 GoTrue 的就是研究 Q2／Q3 釘死的形狀：刪的是 token 的 {@code sub}（沒有別的來源可指定
	 * 刪誰）、secret key 只放 {@code apikey}、明寫硬刪。旅人自己的 Bearer 絕不轉送——GoTrue 會拿它
	 * 驗 admin（403 {@code not_admin}），而且那是旅人的憑證，不是服務的。
	 */
	@Test
	void theAdminCallHardDeletesTheCallerWithTheSecretKeyAlone() throws Exception {
		Traveler me = traveler();

		assertThat(deleteMe(me).statusCode()).isEqualTo(204);

		FakeGoTrue.Request sent = FakeGoTrue.RECEIVED.getLast();
		assertThat(sent.method()).isEqualTo("DELETE");
		assertThat(sent.path()).isEqualTo("/auth/v1/admin/users/" + me.id());
		assertThat(sent.headers().getFirst("apikey")).isEqualTo(FakeGoTrue.SECRET_KEY);
		assertThat(sent.headers().containsKey("Authorization")).describedAs(sent.headers().toString()).isFalse();
		assertThat(sent.headers().getFirst("Content-Type")).startsWith("application/json");
		// 省略也是硬刪（GoTrue 的預設），但軟刪留下 users 列、便條不會 cascade——這個值不交給預設。
		JsonNode softDelete = JSON.readTree(sent.body()).get("should_soft_delete");
		assertThat(softDelete).describedAs(sent.body()).isNotNull();
		assertThat(softDelete.isBoolean() && !softDelete.asBoolean()).describedAs(sent.body()).isTrue();
	}

	/**
	 * 冪等窗口：兩個註銷請求同時過了 session 檢查，後到的那個在 GoTrue 那側已查無此人。
	 * 目標狀態已達成，所以是 204——旅人不必處理「已刪除」這種特殊錯誤。
	 */
	@Test
	void anAccountGoTrueNoLongerHasCountsAsDeleted() throws Exception {
		FakeGoTrue.reply(new FakeGoTrue.Reply(404, "application/json", FakeGoTrue.USER_NOT_FOUND));

		var response = deleteMe(traveler());

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(204);
	}

	/**
	 * GoTrue 其餘的回應都是伺服器故障：既有 catch-all 的 500、沒有 {@code code}（不發明新 token）。
	 * 帳號沒被刪，同一張 token 照常可用——旅人重試是安全的。
	 * <ul>
	 * <li>兩種 404 會被「只看狀態碼」誤判成已刪除：帳號活著，旅人卻收到 204。
	 * <li>GoTrue 的 401／403 是**服務的**金鑰有問題；照轉成 401，iOS 會去刷新旅人的 session。
	 * </ul>
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource
	void anyOtherGoTrueAnswerIs500AndTheAccountSurvives(String variant, int status, String contentType, String body)
			throws Exception {
		Traveler me = traveler();
		FakeGoTrue.reply(new FakeGoTrue.Reply(status, contentType, body));

		var response = deleteMe(me);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(500);
		assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).isEqualTo("""
				{"type":"about:blank","status":500,"title":"Internal Server Error"}""");
		assertThat(get("/v1/me/notes", me).statusCode()).describedAs("帳號還在").isEqualTo(200);
	}

	/** 形狀照 GoTrue v2.194.0 的實際回應（研究 Q2.4 的表）。 */
	static Stream<Arguments> anyOtherGoTrueAnswerIs500AndTheAccountSurvives() {
		String json = "application/json";
		return Stream.of(
				Arguments.of("404 id 不是 UUID", 404, json,
						"{\"code\":404,\"error_code\":\"validation_failed\",\"msg\":\"user_id must be an UUID\"}"),
				Arguments.of("404 網址設錯（chi 的預設 404）", 404, "text/plain; charset=utf-8", "404 page not found\n"),
				Arguments.of("401 沒帶認證", 401, json,
						"{\"code\":401,\"error_code\":\"no_authorization\",\"msg\":\"This endpoint requires a valid Bearer token\"}"),
				Arguments.of("403 金鑰不是 admin", 403, json,
						"{\"code\":403,\"error_code\":\"not_admin\",\"msg\":\"User not allowed\"}"),
				Arguments.of("500 GoTrue 自己壞了", 500, json,
						"{\"code\":500,\"error_code\":\"unexpected_failure\",\"msg\":\"Unexpected failure, please check server logs for more information\"}"));
	}

	/**
	 * 隱私：成功與失敗兩條路都走一次，失敗那條的例外會由 catch-all 寫進 ERROR（附堆疊）。
	 * 金鑰只在 {@code apikey} header 裡，而 Spring 的 client 在 DEBUG 會印 body、不印 header——
	 * 這條測試就是那句話的證明，哪天升級改了行為會紅在這裡。
	 */
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void theSecretKeyReachesNoLogAtAnyLevel(CapturedOutput output) throws Exception {
		assertThat(deleteMe(traveler()).statusCode()).isEqualTo(204);
		FakeGoTrue.reply(new FakeGoTrue.Reply(403, "application/json",
				"{\"code\":403,\"error_code\":\"not_admin\",\"msg\":\"User not allowed\"}"));
		assertThat(deleteMe(traveler()).statusCode()).isEqualTo(500);

		assertThat(output).describedAs("前提：失敗真的被記下，capture 也真的抓得到").contains("GoTrue admin 刪除失敗");
		assertThat(output).doesNotContain(FakeGoTrue.SECRET_KEY);
	}

	/**
	 * GoTrue 連不上時 RestClient 丟的是 I/O 例外，它的訊息帶著完整的 admin URL——也就是使用者 id。
	 * catch-all 會把例外寫進 ERROR，所以 id 必須在那之前被拿掉。
	 */
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void anUnreachableGoTrueIs500WithoutTheUserIdInTheLogs(CapturedOutput output) throws Exception {
		Traveler me = traveler();
		FakeGoTrue.reply(FakeGoTrue.HANG_UP);

		var response = deleteMe(me);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(500);
		assertThat(output).describedAs("前提：失敗真的被記下").contains("未預期的例外");
		assertThat(output).doesNotContain(me.id().toString());
	}

	/**
	 * GoTrue 收下請求卻永遠不回答：沒有逾時的話，這條請求會占住一條 Tomcat thread 直到平台
	 * 的請求逾時（Cloud Run 300 秒）才由平台回一個不是 problem+json 的 504。逾時把它拉回
	 * 服務自己的 500，而且帳號沒被刪、旅人重試是安全的。
	 */
	@Test
	void aGoTrueThatNeverAnswersIs500BeforeThePlatformGivesUp() throws Exception {
		Traveler me = traveler();
		FakeGoTrue.reply(FakeGoTrue.STALL);

		long start = System.nanoTime();
		var response = deleteMe(me);
		Duration waited = Duration.ofNanos(System.nanoTime() - start);

		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(500);
		assertThat(response.body()).isEqualTo("""
				{"type":"about:blank","status":500,"title":"Internal Server Error"}""");
		assertThat(waited).describedAs("逾時要比對方裝死的時間短，否則是在等對方放棄").isLessThan(FakeGoTrue.STALL_FOR);
		assertThat(get("/v1/me/notes", me).statusCode()).describedAs("帳號還在").isEqualTo(200);
	}

	/**
	 * 真實時序下的那個窗口：請求過了 session 檢查（ADR-0013）之後、寫入之前，帳號才被註銷。
	 * 寫入撞上 FK（23503），答案必須是與其他 401 逐字相同的 {@code not_authenticated}，
	 * 不是 500——iOS 只有一條「401 就走刷新」的路。
	 *
	 * <p>窗口用鎖釘住：先鎖住 {@code auth.users} 那一列，留便條的 FK 檢查（{@code FOR KEY SHARE}）
	 * 會卡在那裡，這時才 commit 刪除。兩種時序都只有一個答案，所以不靠賽跑的運氣。
	 */
	@Test
	void aNoteThatLosesTheRaceWithDeletionIs401() throws Exception {
		Traveler me = traveler();
		double[] site = site();
		var answer = new ArrayBlockingQueue<HttpResponse<String>>(1);

		try (var locker = adminConnection()) {
			locker.setAutoCommit(false);
			try (var lock = locker.prepareStatement("select id from auth.users where id = ? for update")) {
				lock.setObject(1, me.id());
				lock.executeQuery();
			}
			var writing = new Thread(() -> {
				try {
					answer.put(drop(me, site));
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});
			writing.start();
			// 請求要先卡在 FK 的鎖上，刪除才算「後到」；沒卡到也只是提早失敗，答案一樣。
			Thread.sleep(500);
			try (var delete = locker.prepareStatement("delete from auth.users where id = ?")) {
				delete.setObject(1, me.id());
				delete.executeUpdate();
			}
			locker.commit();
			writing.join();
		}

		assertNotAuthenticated(answer.take());
		assertThat(admin().sql("select count(*) from public.notes where author_id = ?")
			.param(me.id())
			.query(Integer.class)
			.single()).describedAs("沒有半張孤兒便條").isZero();
	}

	// ─── 工具 ────────────────────────────────────────────────────────────────

	record Traveler(UUID id, String token) {
	}

	private static Traveler traveler() {
		UUID id = UUID.randomUUID();
		return new Traveler(id, signIn(id));
	}

	/** 一塊誰也踩不到的地盤：隨機一點，其他測試的便條落在 100m 內的機率可以忽略。 */
	private static double[] site() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return new double[] { random.nextDouble(-60, 55), random.nextDouble(-180, 180) };
	}

	/**
	 * 直接以超級使用者塞列：留便條與撿取的規則不是本票要驗的事。已撿走的撥到兩小時前，
	 * 避開撿取頻率閘門的滾動窗。
	 */
	private static UUID seed(Traveler author, double[] at, String audience, Traveler pickedUpBy) {
		return admin()
			.sql("insert into public.notes (author_id, content, lat, lng, audience, picked_up_by, picked_up_at)"
					+ " values (?, 'seed', ?, ?, ?, ?, case when ?::uuid is null then null"
					+ " else now() - interval '2 hours' end) returning id")
			.params(author.id(), at[0], at[1], audience, (pickedUpBy == null) ? null : pickedUpBy.id(),
					(pickedUpBy == null) ? null : pickedUpBy.id())
			.query(UUID.class)
			.single();
	}

	private List<String> nearby(Traveler traveler, double[] at) throws Exception {
		var request = HttpRequest.newBuilder(uri("/v1/notes/nearby"))
			.header("Authorization", "Bearer " + traveler.token())
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(
					"{\"coordinate\":{\"latitude\":" + at[0] + ",\"longitude\":" + at[1] + "}}"));
		return ids(send(request));
	}

	/** 真的走一次留便條的端點（FK 在這裡被檢查）。 */
	private HttpResponse<String> drop(Traveler traveler, double[] at) throws Exception {
		return send(HttpRequest.newBuilder(uri("/v1/notes"))
			.header("Authorization", "Bearer " + traveler.token())
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString("{\"content\":\"賽跑\",\"coordinate\":{\"latitude\":" + at[0]
					+ ",\"longitude\":" + at[1] + "}}")));
	}

	private List<String> collection(Traveler traveler) throws Exception {
		return ids(get("/v1/me/collection", traveler));
	}

	private List<JsonNode> myNotes(Traveler traveler) throws Exception {
		return items(get("/v1/me/notes", traveler));
	}

	private static List<String> ids(HttpResponse<String> response) throws Exception {
		return items(response).stream().map((n) -> n.get("id").asString()).toList();
	}

	private static List<JsonNode> items(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
		return JSON.readTree(response.body()).get("items").valueStream().toList();
	}

	private HttpResponse<String> deleteMe(Traveler traveler) throws Exception {
		return send(HttpRequest.newBuilder(uri("/v1/me")).header("Authorization", "Bearer " + traveler.token())
			.DELETE());
	}

	private HttpResponse<String> get(String path, Traveler traveler) throws Exception {
		return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + traveler.token()));
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	private static void assertNotAuthenticated(HttpResponse<String> response) {
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(401);
		assertThat(response.body()).isEqualTo(NOT_AUTHENTICATED);
	}

}
