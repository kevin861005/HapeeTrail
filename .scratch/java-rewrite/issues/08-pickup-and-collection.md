# 08 — 撿起 `POST /v1/notes/{id}/pickup` ＋ `GET /v1/me/collection`

**What to build:** 旅人走進 50m 撿起便條、首次看到內容；全世界只有一人撿得到同一張（先到先贏、輸家 `note_taken`）；回應遺失後重試同一張仍成功且 `pickedUpAt` 不被改寫；走太遠拿到伺服器當下算的實際距離；別人的旅遊紀錄與已過期的便條回與不存在一模一樣的 404。撿到的便條進收藏列表，依撿起時間新→舊翻頁。

**Blocked by:** 06, 07

**Status:** ready-for-agent

- [ ] 請求 body `coordinate`；`{id}` 非 uuid → 400 沒有 `code`；座標越界 → 400 `invalid_coordinates`
- [ ] **happy path 一句 SQL**：條件式 UPDATE（id 相符、未撿、`audience=anyone`、未過期、50m 內）`RETURNING`；成功回 Note 9 鍵、content 揭露、`pickedUpAt` 有值
- [ ] UPDATE 影響 0 列才診斷，順序固定：不存在 → 404 `note_not_found`；已被他人撿走 → 409 `note_taken`；已是自己撿的 → 200 回**原本的** `pickedUpAt`（冪等）；作者是自己 → 403 `own_note`；旅遊紀錄 → 404 `note_not_found`；已過期 → 404 `note_not_found`；其餘 → 403 `too_far` 附 `details.distanceM`（與探索同一算法）
- [ ] **併發**：10 條真實平行 HTTP 請求撿同一張 → 恰 1 個 200、9 個 409；撿取與診斷在同一交易
- [ ] 冪等測試把第一次撿取回撥再重試，證明回的是原 `pickedUpAt` 而非當下時間（沿用 T15 的手法）
- [ ] 撿走的便條從探索消失；作者的 my_notes 裡它的 `pickedUpAt` 非 null
- [ ] `GET /v1/me/collection?limit=&cursor=`：依 `pickedUpAt` 新→舊、（`pickedUpAt`, `id`）平手、`audience` 恆為 `anyone`、`coordinate` 是投放位置；游標機制沿用 06，拿 my_notes 的游標來 → 400 `invalid_cursor`
- [ ] 60 筆同刻 `pickedUpAt`：預設 50 → 翻頁拿剩 10、無重疊；A 只撿 1 張 → 恰 1 筆且 `nextCursor` null
- [ ] 跨使用者：撿到的只出現在撿起者的收藏，不出現在他人的
- [ ] 頻率閘門在 09 才加；本票撿取次數控制在 60 以內
- [ ] 全程紅→綠
