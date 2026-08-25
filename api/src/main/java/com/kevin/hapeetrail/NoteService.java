package com.kevin.hapeetrail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 便條的全部業務規則。常數與判定順序與 v3.3 的 RPC 逐字相同（契約 v4 只換 transport）。
 */
@Service
class NoteService {

	/** 內容上限。附帶在 {@code content_too_long} 裡的數字與閘門用的是同一個常數。 */
	private static final int MAX_CHARS = 500;

	/** 未撿、未過期的公開便條上限。 */
	private static final int MAX_ACTIVE = 50;

	/** 旅遊紀錄的絕對總量上限（撿不走也不過期，所以只能算總量）。 */
	private static final int MAX_PRIVATE = 5000;

	/**
	 * 未撿公開便條的存活期（ADR-0010，讀時推導）。探索／撿取／未撿額度／{@code expiresAt}
	 * 共用的唯一來源——散成幾份常數遲早漂移，症狀是「地圖上看不到但額度還被佔著」。
	 */
	static final Duration TTL = Duration.ofDays(90);

	/**
	 * trim 的字元集＝Unicode {@code White_Space} ＋ U+001C–U+001F（契約 §4 逐字）。
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

	/** 契約的時間戳格式：永遠六位小數、永遠 {@code Z}，不因秒數恰為整數而縮水。 */
	private static final DateTimeFormatter WIRE_TS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	private static final String WIRE_COLUMNS = "id, content, color, style, audience, lat, lng, created_at,"
			+ " picked_up_at";

	/**
	 * 計數與 INSERT 是**同一句**：兩句版本的競態窗口是整個 round trip，一句版本只有語句本身。
	 * 影響 0 列 ⇒ 額度已滿（唯一的失敗原因，其餘都會拋 SQLException）。
	 *
	 * <p>ponytail: advisory 上限，併發下可小幅超越（ADR-0011 已接受）；防的是匿名帳號灑滿
	 * 地圖，不是精準的配額。真的需要硬上限就加 DB constraint，不是把它搬回 RPC。
	 */
	private static final String INSERT = """
			insert into public.notes (author_id, content, lat, lng, color, style, audience)
			select :author, :content, :lat, :lng, :color, :style, :audience
			 where (select count(*) from public.notes n
			         where n.author_id = :author and %s) < %d
			returning %s
			""";

	/**
	 * 公開便條的額度算「未撿且未過期的」——被撿走或過期就釋放。
	 * 過期界線用 {@code now()} 而不是 Java 算好的時刻：{@code created_at} 與
	 * {@code expiresAt} 都出自資料庫的時鐘，界線也必須，否則兩台機器的偏移會讓
	 * 「第 50 張」的邊界飄掉。秒數仍由上面那一個 TTL 常數推導，沒有第二份。
	 */
	private static final String INSERT_PUBLIC = INSERT.formatted(
			"n.picked_up_at is null and n.audience = 'anyone'"
					+ " and n.created_at > now() - make_interval(secs => %d)".formatted(TTL.toSeconds()),
			MAX_ACTIVE, WIRE_COLUMNS);

	private static final String INSERT_PRIVATE = INSERT.formatted("n.audience = 'self'", MAX_PRIVATE, WIRE_COLUMNS);

	/** 每頁筆數：省略時的預設與硬上界。契約把它們寫死了，client 端的預檢用的是同兩個數字。 */
	private static final int DEFAULT_LIMIT = 50;

	private static final int MAX_LIMIT = 100;

	/** 游標裡的列表識別。兩支列表的排序鍵不同，值不同 ⇒ 游標互不相容（拿錯即 invalid_cursor）。 */
	private static final String MY_NOTES = "my_notes";

	private static final String MY_COLLECTION = "my_collection";

	/**
	 * keyset 分頁：以（{@code created_at}, {@code id}）為游標，不用 OFFSET。
	 * OFFSET 在「翻頁期間又留了新便條」時會整批漏列，而且深頁要重掃前面所有列。
	 * 平手鍵是 id：同刻的便條（同一批、同一個交易）只靠時間戳會整批重複或整批消失。
	 */
	private static final String PAGE = """
			select %1$s from public.notes
			 where %2$s = :traveler %4$s
			 order by %3$s desc, id desc
			 limit :limit
			""";

	private static final String PAGE_FIRST = PAGE.formatted(WIRE_COLUMNS, "author_id", "created_at", "");

	private static final String PAGE_AFTER = PAGE.formatted(WIRE_COLUMNS, "author_id", "created_at",
			"and (created_at, id) < (:key, :id)");

	/**
	 * 收藏依**撿起**時間排序，不是投放時間——最舊的便條可能是今天才撿到的。
	 *
	 * <p>計畫形狀（10 萬列、其中 5 萬已撿，ANALYZE 後實測）：
	 * {@code Index Scan using notes_picker_ix} ＋ {@code Incremental Sort}
	 * （{@code Presorted Key: picked_up_at}）——索引是 {@code (picked_up_by, picked_up_at desc)}，
	 * 沒有 id，所以**只有同刻那幾筆**要再排一次，不是整份列表。翻頁那句還會把
	 * {@code picked_up_at <= :key} 推進 Index Cond。要消掉這次 sort 得把索引改成三欄，
	 * 那是 schema 變更（票 06 已就 {@code notes_author_ix} 的同一個形狀請示過，未定案）。
	 */
	private static final String COLLECTION_FIRST = PAGE.formatted(WIRE_COLUMNS, "picked_up_by", "picked_up_at", "");

	private static final String COLLECTION_AFTER = PAGE.formatted(WIRE_COLUMNS, "picked_up_by", "picked_up_at",
			"and (picked_up_at, id) < (:key, :id)");

	/** 探索半徑與撿取半徑，公尺。兩者都只出現在 SQL 語句裡——Java 永遠不比較距離。 */
	private static final double EXPLORE_RADIUS_M = 100.0;

	private static final double PICKUP_RADIUS_M = 50.0;

	/** 探索一次最多回幾個 pin（截斷不另行標示：無分頁，最近的 20 個就是全部）。 */
	private static final int MAX_HINTS = 20;

	/** 呼叫者當下的位置。組成點的是 SQL，不是 Java——距離、半徑、pickable 全都在這個型別上算。 */
	private static final String CALLER_POINT = "extensions.st_setsrid(extensions.st_makepoint(:lng, :lat), 4326)"
			+ "::extensions.geography";

	/**
	 * 探索：100m 內、非自己的、未撿走、未過期、公開的便條，最近優先取 20。
	 *
	 * <p>前兩個 where 條件與 {@code notes_active_location_gix} 的 partial index 述詞**逐字一致**
	 * ——不一致的話查詢計畫用不到它，探索會靜默退化成全表掃描。TTL 條件進不了索引述詞
	 * （{@code now()} 不是 immutable），落在 Filter 是預期的計畫形狀。
	 *
	 * <p>{@code distanceM}、{@code pickable} 與撿取的 {@code too_far} 距離都出自這裡同一組
	 * geography 運算：兩份算法遲早分歧，症狀是「探索說 60 公尺、走過去撿卻說還差 70 公尺」。
	 */
	private static final String NEARBY = """
			select n.id, n.lat, n.lng, n.color, n.style, n.created_at,
			       round(extensions.st_distance(n.location, %1$s))::int as distance_m,
			       extensions.st_dwithin(n.location, %1$s, %3$s) as pickable
			  from public.notes n
			 where n.picked_up_at is null
			   and n.audience = 'anyone'
			   and n.author_id <> :traveler
			   and n.created_at > now() - make_interval(secs => %4$d)
			   and extensions.st_dwithin(n.location, %1$s, %2$s)
			 order by distance_m
			 limit %5$d
			""".formatted(CALLER_POINT, EXPLORE_RADIUS_M, PICKUP_RADIUS_M, TTL.toSeconds(), MAX_HINTS);

	/**
	 * 撿起。獨佔性的全部保證就是這一句：check 與 write 同語句同 row version，沒有競態窗口。
	 * happy path 因此只有一次 round trip，診斷只在它影響 0 列時才跑。
	 *
	 * <p>五個條件各擋一種便條：已被撿走的、旅遊紀錄（探索看不到它，但 id 未必永不外流）、
	 * 自己的、已過期的、太遠的。{@code picked_up_at} 用 {@code now()} 而不是 Java 的時刻
	 * ——它要與 {@code created_at} 出自同一個時鐘，否則兩台機器的偏移會讓收藏的排序飄掉。
	 *
	 * <p>前兩個條件與 {@code notes_active_location_gix} 的 partial index 述詞
	 * （{@code picked_up_at is null and audience = 'anyone'}）**逐字一致**，與探索同一個不變式：
	 * 實測計畫走 {@code Index Scan using notes_active_location_gix}，id 與 TTL 落在 Filter。
	 */
	private static final String PICKUP = """
			update public.notes n
			   set picked_up_by = :traveler, picked_up_at = now()
			 where n.id = :id
			   and n.picked_up_at is null
			   and n.audience = 'anyone'
			   and n.author_id <> :traveler
			   and n.created_at > now() - make_interval(secs => %2$d)
			   and extensions.st_dwithin(n.location, %1$s, %3$s)
			returning %4$s
			""".formatted(CALLER_POINT, TTL.toSeconds(), PICKUP_RADIUS_M, WIRE_COLUMNS);

	/**
	 * 失敗診斷：一句 SQL 把「為什麼撿不到」的六種可能一次算完，Java 只負責照固定順序讀。
	 * 沒有列 ＝ 不存在。距離也在這裡算——與探索的 {@code distanceM} 同一組 geography 運算，
	 * 兩份算法遲早分歧，症狀是「探索說 60 公尺、走過去撿卻說還差 70 公尺」。
	 *
	 * <p>此讀取相對 UPDATE 仍有 race（READ COMMITTED 下每個語句各自取快照），但它只影響
	 * 回報哪個錯誤碼，不影響獨佔的正確性——獨佔由上面那一句 UPDATE 獨力保證。
	 */
	private static final String DIAGNOSE = """
			select %3$s,
			       n.picked_up_by = :traveler                            as is_mine,
			       n.picked_up_at is not null                            as is_taken,
			       n.author_id = :traveler                               as is_own,
			       n.audience <> 'anyone'                                as is_private,
			       n.created_at <= now() - make_interval(secs => %2$d)   as is_expired,
			       round(extensions.st_distance(n.location, %1$s))::int  as distance_m
			  from public.notes n
			 where n.id = :id
			""".formatted(CALLER_POINT, TTL.toSeconds(), WIRE_COLUMNS);

	private final JdbcClient jdbc;

	NoteService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 依當前位置留下便條，回傳 trim 後的正規 Note。 */
	Note drop(UUID author, DropRequest request) {
		if (request == null || request.content() == null) {
			throw malformed();
		}
		Coordinate at = located(request.coordinate());
		// 順序不能反：上限量的是 trim **之後**的內容。
		String content = EDGE_WHITESPACE.matcher(request.content()).replaceAll("");
		if (content.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "content_empty", null);
		}
		// code point 而非 char：一個星群平面字元在使用者眼中是 1 個字，String.length() 算 2。
		if (content.codePointCount(0, content.length()) > MAX_CHARS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "content_too_long", Map.of("maxChars", MAX_CHARS));
		}
		int color = styleCode(request.color());
		int style = styleCode(request.style());
		// audience 相反：值必須被後端理解，不認得就拒絕（不比對大小寫、不 trim）——
		// 猜使用者意圖在這個欄位上的失敗成本是「私密內容變公開」。
		String audience = (request.audience() != null) ? request.audience() : "anyone";
		boolean isPublic = audience.equals("anyone");
		if (!isPublic && !audience.equals("self")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audience", null);
		}

		JdbcClient.StatementSpec statement = this.jdbc.sql(isPublic ? INSERT_PUBLIC : INSERT_PRIVATE)
			.param("author", author)
			.param("content", content)
			.param("lat", at.latitude())
			.param("lng", at.longitude())
			.param("color", color)
			.param("style", style)
			.param("audience", audience);
		if (isPublic) {
			statement = statement.param("ttlCutoff", OffsetDateTime.now(ZoneOffset.UTC).minus(TTL));
		}
		return statement.query(NoteService::toNote)
			.optional()
			.orElseThrow(() -> isPublic
					? new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "active_note_limit",
							Map.of("maxActiveNotes", MAX_ACTIVE))
					: new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "private_note_limit",
							Map.of("maxPrivateNotes", MAX_PRIVATE)));
	}

	/**
	 * 我留過的便條，{@code createdAt} 新→舊、以 id 平手。過期的仍在（只是不再進探索），
	 * 旅遊紀錄也在——這份紀錄的完整性是 user story 15 本身。
	 */
	NotePage myNotes(UUID author, Long limit, String cursor) {
		return page(author, limit, cursor, MY_NOTES, PAGE_FIRST, PAGE_AFTER, Note::createdAt);
	}

	/**
	 * 我撿到的便條，{@code pickedUpAt} 新→舊。{@code audience} 恆為 {@code anyone}
	 * （旅遊紀錄撿不走），{@code coordinate} 是**投放**位置——第一階段不記錄撿起位置。
	 */
	NotePage myCollection(UUID picker, Long limit, String cursor) {
		return page(picker, limit, cursor, MY_COLLECTION, COLLECTION_FIRST, COLLECTION_AFTER, Note::pickedUpAt);
	}

	/**
	 * 兩支列表共用的 keyset 分頁：以（排序鍵, id）為游標，不用 OFFSET。
	 * 差別只有三處——查誰的欄位、排序鍵、游標裡的列表識別，全部由呼叫端給。
	 */
	private NotePage page(UUID traveler, Long limit, String rawCursor, String list, String first, String after,
			Function<Note, String> sortKey) {
		// 越界不報錯，靜默夾住：拒絕的話 client 算錯一次頁碼就整個列表打不開，夾住最多是這頁少幾筆。
		// 收 long 而不是 int：`limit=99999999999` 是越界（該夾成 100），不是型別錯誤——
		// 用 Integer 接的話 Spring 在轉型階段就 400 了，契約的「越界不報錯」在那裡靜默破掉。
		// ponytail: 天花板搬到 2^63，沒有搬走。真要無上限就自己收字串再 parse，
		// 但那要連「abc → 400 無 code」一起自己實作，換來的只是更大的一個數字。
		int size = Math.clamp((limit != null) ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);
		Cursor cursor = Cursor.decode(rawCursor, list);
		JdbcClient.StatementSpec statement = this.jdbc.sql((cursor != null) ? after : first)
			.param("traveler", traveler)
			// 多取一筆：只用來判斷還有沒有下一頁，不上 wire。count(*) 要多掃一次整份列表。
			.param("limit", size + 1);
		if (cursor != null) {
			statement = statement.param("key", cursor.key()).param("id", cursor.id());
		}
		List<Note> rows = statement.query(NoteService::toNote).list();
		boolean more = rows.size() > size;
		List<Note> items = more ? rows.subList(0, size) : rows;
		if (!more) {
			// 只有真的還有下一筆才給游標 ⇒ nextCursor 為 null 是確定的終止訊號，
			// client 不必為了確認結束多轉一次載入圈。
			return new NotePage(items, null);
		}
		Note last = items.getLast();
		return new NotePage(items,
				new Cursor(list, OffsetDateTime.parse(sortKey.apply(last)), last.id()).encode());
	}

	/**
	 * 附近的便條 pin。內容與作者刻意不在回傳裡；判定與距離全由 {@link #NEARBY} 那一句 SQL 做完
	 * ——這個方法只負責驗座標、綁參數、把列搬成 wire 形狀。
	 */
	NearbyResult nearby(UUID traveler, NearbyRequest request) {
		Coordinate at = located((request != null) ? request.coordinate() : null);
		return new NearbyResult(this.jdbc.sql(NEARBY)
			.param("traveler", traveler)
			.param("lat", at.latitude())
			.param("lng", at.longitude())
			.query(NoteService::toHint)
			.list());
	}

	/**
	 * 走進 50m 撿起，全世界只有一人撿得到同一張。happy path 一句 SQL；影響 0 列才診斷。
	 *
	 * <p>刻意是 {@code public}：{@code @Transactional} 只作用在 public 方法上
	 * （{@code AnnotationTransactionAttributeSource.publicMethodsOnly} 預設為 true），
	 * 包內可見的話這個註解會被**靜默忽略**，撿取與診斷就落在兩個交易、兩條連線上。
	 */
	@Transactional
	public Note pickup(UUID traveler, UUID id, PickupRequest request) {
		Coordinate at = located((request != null) ? request.coordinate() : null);
		return this.jdbc.sql(PICKUP)
			.param("traveler", traveler)
			.param("id", id)
			.param("lat", at.latitude())
			.param("lng", at.longitude())
			.query(NoteService::toNote)
			.optional()
			.orElseGet(() -> diagnose(traveler, id, at));
	}

	/** 撿不到的原因。沒有這一列 ＝ 不存在；其餘六種由 {@link #verdict} 照固定順序判。 */
	private Note diagnose(UUID traveler, UUID id, Coordinate at) {
		return this.jdbc.sql(DIAGNOSE)
			.param("traveler", traveler)
			.param("id", id)
			.param("lat", at.latitude())
			.param("lng", at.longitude())
			.query(NoteService::verdict)
			.optional()
			.orElseThrow(NoteService::notFound);
	}

	/**
	 * 診斷的順序是契約的一部分，不能重排：已是自己的擺第一，回應遺失後的重試才會拿到
	 * 成功而不是 {@code note_taken}（而且回的是**原本**那次的 {@code picked_up_at}——
	 * 這裡沒有任何 UPDATE，時間戳沒有被改寫的機會）。已被撿走排在自己的便條之前，因為
	 * 撿走是既成事實；旅遊紀錄與過期都回 {@code note_not_found}：區分等於向外人確認
	 * 該座標有一張他看不到的便條。剩下的唯一可能就是太遠。
	 */
	private static Note verdict(ResultSet rs, int rowNum) throws SQLException {
		if (rs.getBoolean("is_mine")) {
			return toNote(rs, rowNum);
		}
		if (rs.getBoolean("is_taken")) {
			throw new ApiException(HttpStatus.CONFLICT, "note_taken", null);
		}
		if (rs.getBoolean("is_own")) {
			// 自己的（含自己的旅遊紀錄）：對自己沒有隱藏的必要。
			throw new ApiException(HttpStatus.FORBIDDEN, "own_note", null);
		}
		if (rs.getBoolean("is_private") || rs.getBoolean("is_expired")) {
			throw notFound();
		}
		throw new ApiException(HttpStatus.FORBIDDEN, "too_far", Map.of("distanceM", rs.getInt("distance_m")));
	}

	/** 不存在、別人的旅遊紀錄、已過期——三者對外是同一個答案。 */
	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "note_not_found", null);
	}

	/**
	 * 座標的共用閘門。缺欄位（含只給一半）走型別／格式錯誤那一類，與 openapi 的 required
	 * 逐一對應；{@code invalid_coordinates} 因此只代表一件事：值在、但越界。
	 */
	private static Coordinate located(Coordinate at) {
		if (at == null || at.latitude() == null || at.longitude() == null) {
			throw malformed();
		}
		if (!inRange(at.latitude(), 90) || !inRange(at.longitude(), 180)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_coordinates", null);
		}
		return at;
	}

	/**
	 * {@code color} 與 {@code style} 共用的代號驗證（契約的 {@code StyleCode}，兩欄同一套規則）。
	 * null／越界一律走契約的 token，不讓它撞到 smallint 的原生溢位。
	 */
	private static int styleCode(Integer code) {
		if (code == null) {
			return 1;
		}
		// 只做型別與範圍粗檢，不驗證語意：超出裝置端對照表的代號照收、原樣存、原樣回。
		if (code < 1 || code > 32767) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_style_code", null);
		}
		return code;
	}

	/** 寫成「不在範圍內」而不是「大於上界」：NaN 兩種比較都是 false，那樣會靜默放行。 */
	private static boolean inRange(double value, double bound) {
		return value >= -bound && value <= bound;
	}

	private static Note toNote(ResultSet rs, int rowNum) throws SQLException {
		String audience = rs.getString("audience");
		Instant createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
		OffsetDateTime pickedUpAt = rs.getObject("picked_up_at", OffsetDateTime.class);
		return new Note(rs.getObject("id", UUID.class), rs.getString("content"), rs.getInt("color"),
				rs.getInt("style"), audience, new Coordinate(rs.getDouble("lat"), rs.getDouble("lng")),
				WIRE_TS.format(createdAt),
				// 旅遊紀錄不會過期 ⇒ null；已撿走的仍保有 expiresAt（那是關於這張便條的事實）。
				audience.equals("anyone") ? WIRE_TS.format(createdAt.plus(TTL)) : null,
				(pickedUpAt != null) ? WIRE_TS.format(pickedUpAt) : null);
	}

	/** {@code distanceM} 與 {@code pickable} 直接取 SQL 算好的欄位——不在這裡重算，也沒得重算。 */
	private static NearbyHint toHint(ResultSet rs, int rowNum) throws SQLException {
		return new NearbyHint(rs.getObject("id", UUID.class), rs.getInt("color"), rs.getInt("style"),
				new Coordinate(rs.getDouble("lat"), rs.getDouble("lng")), rs.getInt("distance_m"),
				rs.getBoolean("pickable"),
				WIRE_TS.format(rs.getObject("created_at", OffsetDateTime.class).toInstant()));
	}

	/**
	 * 缺必填欄位屬於型別／格式錯誤那一類：400 但**沒有 {@code code}**
	 * （「有 code 才是業務錯誤」是 v4 唯一的判斷閘門）。非法 JSON 與型別不符由 Spring 自己的
	 * {@code HttpMessageNotReadableException} 進 {@link ApiErrors}，走同一條路、同一個形狀。
	 */
	private static ApiException malformed() {
		return new ApiException(HttpStatus.BAD_REQUEST, null, null);
	}

}
