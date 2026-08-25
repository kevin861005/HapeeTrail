package com.kevin.hapeetrail;

import java.util.List;
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

/** 列表 envelope：items 永遠是陣列（不會是 null），nextCursor 為 null ＝ 沒有更多。 */
record NotePage(List<Note> items, String nextCursor) {
}

/** {@code POST /v1/notes/nearby} 的 body。座標刻意在 body 不在 query：query 會把它寫進存取日誌與各層 proxy 的快取。 */
record NearbyRequest(Coordinate coordinate) {
}

/**
 * 探索 pin，契約凍結的 7 鍵。**沒有 content**——內容是走進 50m 撿起的獎勵；
 * 也沒有任何身分欄位。代號在，是為了地圖 pin 能渲染成作者選的樣式。
 *
 * <p>{@code distanceM} 與 {@code pickable} 都是伺服器算的快照：前者是整數公尺，
 * 後者是「呼叫當下在撿取半徑內」，撿起時伺服器會重驗（client 勿硬編半徑）。
 */
record NearbyHint(UUID id, int color, int style, Coordinate coordinate, int distanceM, boolean pickable,
		String createdAt) {
}

/**
 * {@code POST /v1/notes/{id}/pickup} 的 body。便條 id 在路徑上，body 只有呼叫者的當前位置
 * ——伺服器據此重算距離（探索回的 {@code pickable} 是快照，client 不得拿它當授權）。
 */
record PickupRequest(Coordinate coordinate) {
}

/** 探索 envelope：無分頁（上限 20 就是全部），但仍是物件而非裸陣列——日後加欄位才不是破壞性變更。 */
record NearbyResult(List<NearbyHint> items) {
}
