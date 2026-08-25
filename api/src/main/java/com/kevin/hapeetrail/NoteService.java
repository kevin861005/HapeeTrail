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
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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

	private final JdbcClient jdbc;

	NoteService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 依當前位置留下便條，回傳 trim 後的正規 Note。 */
	Note drop(UUID author, DropRequest request) {
		// 缺必填欄位（含座標少一半）走型別／格式錯誤那一類，與 openapi 的 required 逐一對應；
		// invalid_coordinates 因此只代表一件事：值在、但越界。
		if (request == null || request.content() == null || request.coordinate() == null
				|| request.coordinate().latitude() == null || request.coordinate().longitude() == null) {
			throw malformed();
		}
		Coordinate at = request.coordinate();
		if (!inRange(at.latitude(), 90) || !inRange(at.longitude(), 180)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_coordinates", null);
		}
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

	/** 我留過的便條，新→舊。過期的仍在（只是不再進探索），旅遊紀錄也在。 */
	List<Note> myNotes(UUID author) {
		return this.jdbc
			.sql("select " + WIRE_COLUMNS + " from public.notes where author_id = :author order by created_at desc")
			.param("author", author)
			.query(NoteService::toNote)
			.list();
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

	/**
	 * 缺必填欄位屬於型別／格式錯誤那一類：400 但**沒有 {@code code}**
	 * （「有 code 才是業務錯誤」是 v4 唯一的判斷閘門）。非法 JSON 與型別不符由 Spring 自己的
	 * {@code HttpMessageNotReadableException} 進 {@link ApiErrors}，走同一條路、同一個形狀。
	 */
	private static ApiException malformed() {
		return new ApiException(HttpStatus.BAD_REQUEST, null, null);
	}

}
