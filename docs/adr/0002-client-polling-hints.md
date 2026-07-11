# ADR-0002：100m 提示採 client 輪詢，不做推播

日期：2026-07-12　狀態：已採納

## 決策

iOS 於前景位置變化（distanceFilter 25–50m）時呼叫 `nearby_notes` RPC。
不做推播、不做伺服器端 geofence、schema 不預留 device token 欄位。

## 理由

- 推播需要背景持續上傳位置＋伺服器 geofence 比對＋APNs 管理——MVP 明顯過度設計，
  且耗電與隱私成本高。
- 輪詢零額外基礎設施，前景使用完全夠用（boring technology）。
- 升級路徑明確：日後要背景提醒再加，欄位屆時補很便宜，預鋪違反 YAGNI。

## 附帶約定

- 半徑（100m 提示／50m 撿起）是伺服器常數，client 從 `distance_m`/`pickable` 讀結果，
  不硬編門檻——調整半徑不用發版。
