package com.kevin.hapeetrail;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotesController {

	private final JdbcClient jdbc;

	NotesController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/v1/me/notes")
	NotePage myNotes(@AuthenticationPrincipal Jwt jwt) {
		// ponytail: 只查 id、不分頁——這一票只證明「以 hapeetrail_api 過 RLS 讀得到自己的列」。
		// Note 的 9 鍵 wire 是票 05 的事，游標與 limit 是票 06 的事。
		List<String> items = this.jdbc
			.sql("select id from public.notes where author_id = ?::uuid order by created_at desc")
			.param(jwt.getSubject())
			.query(String.class)
			.list();
		return new NotePage(items, null);
	}

	/** 列表 envelope：items 永遠是陣列，nextCursor 為 null ＝ 沒有更多。 */
	record NotePage(List<String> items, String nextCursor) {
	}

}
