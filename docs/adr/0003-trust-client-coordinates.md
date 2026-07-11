# ADR-0003：MVP 接受 client 自報 GPS 座標

日期：2026-07-12　狀態：已採納（含已知風險）

## 決策

伺服器對距離規則（100m 可見／50m 可撿）的驗證，以 client 自報座標為準。
本層不做防偽。

## 已知風險

- 偽造座標可遠端掃描 pin 位置（`nearby_notes` 不回 content/作者，掃不到內容）。
- 偽造座標可遠端撿取——獨佔語意使此為**破壞性**攻擊（掃圖可清空整座城市的便條）。

## 緩解（已實作，最便宜的保險）

- `nearby_notes`：固定 100m 半徑＋20 筆上限＋不回 content/author——爬蟲只能得知 pin 密度。
- 每人未撿便條上限 50 張（`active_note_limit`）、每小時撿取上限 60 次
  （`pickup_rate_limited`）——各為一個 indexed count，advisory 性質（併發下可小幅超越）。

## 未來真緩解（出現濫用訊號才做）

- App Attest / DeviceCheck 驗證（需 Edge Function）。
- 移動速度合理性檢查（需呼叫歷史狀態）。
