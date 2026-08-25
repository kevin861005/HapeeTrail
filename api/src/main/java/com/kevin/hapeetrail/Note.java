package com.kevin.hapeetrail;

import java.util.UUID;

/**
 * 便條上 wire 的形狀：契約凍結的 9 鍵，留便條／撿起／我的便條／我的收藏共用。
 * 不含任何 uuid 身分欄位（{@code author_id}／{@code picked_up_by} 永遠不上 wire）。
 *
 * <p>三個時間戳是**字串**而不是 {@code Instant}：契約要求固定六位小數＋{@code Z}，
 * 而預設序列化的位數隨秒數變動（整秒就縮水）。格式化在 {@link NoteService}，這裡只是搬運。
 */
record Note(UUID id, String content, int color, int style, String audience, Coordinate coordinate, String createdAt,
		String expiresAt, String pickedUpAt) {
}

/** WGS-84 座標。v4 起請求端與回應端是同一個型別，client 兩邊共用一個資料結構。 */
record Coordinate(Double latitude, Double longitude) {
}

/**
 * {@code POST /v1/notes} 的 body。可省略的欄位一律用包裝型別——null 就是「沒給」，
 * 與「給了 0」必須分得開（0 是越界的代號，要回 {@code invalid_style_code}）。
 */
record DropRequest(String content, Coordinate coordinate, Integer color, Integer style, String audience) {
}
