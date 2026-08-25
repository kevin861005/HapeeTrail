package com.kevin.hapeetrail;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotesController {

	private final NoteService notes;

	NotesController(NoteService notes) {
		this.notes = notes;
	}

	/** 成功是 200 而非 201：契約沒有單張便條的 GET 路徑，沒有 {@code Location} 可指。 */
	@PostMapping("/v1/notes")
	Note drop(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) DropRequest request) {
		return this.notes.drop(traveler(jwt), request);
	}

	@GetMapping("/v1/me/notes")
	NotePage myNotes(@AuthenticationPrincipal Jwt jwt) {
		// ponytail: 還沒分頁，整份撈——游標與 limit 是票 06 的事，nextCursor 先恆為 null。
		return new NotePage(this.notes.myNotes(traveler(jwt)), null);
	}

	/** 使用者身分＝JWT 的 {@code sub}；缺它的 token 進不了這裡（SecurityConfig 擋掉）。 */
	private static UUID traveler(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	/** 列表 envelope：items 永遠是陣列，nextCursor 為 null ＝ 沒有更多。 */
	record NotePage(List<Note> items, String nextCursor) {
	}

}
