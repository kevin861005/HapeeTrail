# ADR-0001：撿起語意採「獨佔消失」

日期：2026-07-12　狀態：已採納

## 決策

便條被一人撿走即從地圖消失、進撿起者收藏。同一便條全世界只有一次撿起。

## 理由

- 符合實體便條隱喻，稀缺性創造「搶到了」的核心體驗。
- 已知代價：早期用戶稀少時內容易被清空（冷啟動）——接受，日後可用系統便條緩解。

## 實作

- 撿起建模為 `notes.picked_up_by` + `picked_up_at` 兩欄位（1:0..1 用欄位即結構保證），
  不建 pickups 表、不建 status 欄。「在地圖上」= `picked_up_at IS NULL` 單一謂詞。
- 原子性：單一 conditional `UPDATE ... WHERE picked_up_at IS NULL ... RETURNING`。
  READ COMMITTED 下輸家阻塞於 row lock、EvalPlanQual 重評後 match 0 rows——
  check 與 write 同語句，無競態窗口，不需 `SELECT FOR UPDATE`。
- 冪等重試：已是本人撿走 → 回成功（行動網路掉回應的重試不可誤報「被搶走」）。
- `picked_up_by` FK 用 `ON DELETE SET NULL` 且 `picked_up_at` 永不清除：
  撿起者刪號時便條不返回地圖、作者紀錄不被連坐刪除。
