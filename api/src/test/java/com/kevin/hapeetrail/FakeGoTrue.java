package com.kevin.hapeetrail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 測試用的 GoTrue Admin API（JDK 內建 http server）：只接 {@code DELETE /auth/v1/admin/users/{id}}，
 * 回應形狀照真 GoTrue v2.194.0（T28 研究 Q2.4）。預設行為就是真的那樣——對測試 DB 真刪那個
 * 使用者（FK cascade 真的跑），刪到回 200 {@code {}}、查無此人回 404 {@code user_not_found}。
 * {@link #reply} 換掉回應、不動 DB，用來模擬錯誤與冪等窗口。
 *
 * <p>收到的請求留在 {@link #RECEIVED}：服務送出去的形狀（路徑、認證 header、body）要由測試斷言。
 * 整個 JVM 共用一顆，與 {@link SupabaseDbTest} 的容器同一個理由（每個 Spring context 都要它的網址）。
 */
final class FakeGoTrue {

	/** 服務設定的 secret key。測試專用值——真的那把只存在部署平台的 secrets。 */
	static final String SECRET_KEY = "sb_secret_test-only-not-a-secret";

	static final String USER_NOT_FOUND = """
			{"code":404,"error_code":"user_not_found","msg":"User not found"}""";

	static final List<Request> RECEIVED = new CopyOnWriteArrayList<>();

	private static volatile Reply override;

	private static final HttpServer SERVER = start();

	record Request(String method, String path, Headers headers, String body) {
	}

	record Reply(int status, String contentType, String body) {
	}

	/** 不回任何東西就掛斷：服務那側拿到的是 I/O 例外，不是 HTTP 回應（網路斷、GoTrue 掛掉）。 */
	static final Reply HANG_UP = new Reply(0, null, null);

	/** 收下請求但不回答：連線活著、答案永遠不來（GoTrue 卡死、網路黑洞）。只有逾時救得了。 */
	static final Reply STALL = new Reply(-1, null, null);

	/**
	 * {@link #STALL} 裝死多久。服務的逾時必須比這短，否則測試就是在等它。
	 * ponytail: 這顆 server 單執行緒，裝死期間下一個測試的請求也在排隊——所以只裝兩秒。
	 */
	static final Duration STALL_FOR = Duration.ofSeconds(2);

	private FakeGoTrue() {
	}

	/** 服務設定的 GoTrue 網址（形狀同 hosted 的 {@code https://<ref>.supabase.co/auth/v1}）。 */
	static String url() {
		return "http://localhost:" + SERVER.getAddress().getPort() + "/auth/v1";
	}

	/** 之後每個請求都回這個、不動 DB；null ＝ 回到真刪。 */
	static void reply(Reply reply) {
		override = reply;
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/auth/v1/admin/users/", FakeGoTrue::handle);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		RECEIVED.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestHeaders(), body));
		Reply reply = override;
		if (reply == HANG_UP) {
			exchange.close();
			return;
		}
		if (reply == STALL) {
			try {
				Thread.sleep(STALL_FOR);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
			return;
		}
		if (reply == null) {
			// GoTrue 的硬刪就是這一句（pop 的 Destroy）；sessions、identities、notes 都靠 FK 跟著走。
			int deleted = SupabaseDbTest.admin()
				.sql("delete from auth.users where id = ?::uuid")
				.param(path.substring(path.lastIndexOf('/') + 1))
				.update();
			reply = (deleted == 1) ? new Reply(200, "application/json", "{}")
					: new Reply(404, "application/json", USER_NOT_FOUND);
		}
		byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", reply.contentType());
		exchange.sendResponseHeaders(reply.status(), bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
