package com.kevin.hapeetrail;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

	/** 探索是 POST 而非 GET：座標放 body，才不會被存取日誌與各層 proxy 的快取收走。 */
	@PostMapping("/v1/notes/nearby")
	NearbyResult nearby(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) NearbyRequest request) {
		return this.notes.nearby(traveler(jwt), request);
	}

	/**
	 * 走進 50m 撿起。{@code {id}} 由 Spring 轉成 {@code UUID}——不是合法 uuid 就在進到這裡
	 * 之前 400（且**沒有 code**，與其他型別錯誤同一條路），業務層因此只處理「id 合法但撿不到」。
	 */
	@PostMapping("/v1/notes/{id}/pickup")
	Note pickup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestBody(required = false) PickupRequest request) {
		return this.notes.pickup(traveler(jwt), id, request);
	}

	/** {@code limit} 省略 ＝ 50、{@code cursor} 省略 ＝ 第一頁；兩者都是型別檢查在先。 */
	@GetMapping("/v1/me/notes")
	NotePage myNotes(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long limit,
			@RequestParam(required = false) String cursor) {
		return this.notes.myNotes(traveler(jwt), limit, cursor);
	}

	/** 我撿到的便條。參數與分頁規則同 {@link #myNotes}，但游標互不相容（排序鍵不同）。 */
	@GetMapping("/v1/me/collection")
	NotePage myCollection(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long limit,
			@RequestParam(required = false) String cursor) {
		return this.notes.myCollection(traveler(jwt), limit, cursor);
	}

	/** 使用者身分＝JWT 的 {@code sub}；缺它的 token 進不了這裡（SecurityConfig 擋掉）。 */
	private static UUID traveler(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
