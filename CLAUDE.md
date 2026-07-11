# Trailstamp

旅遊足跡 App：讓全球旅人在景點留下便條紙、記錄足跡，
並與世界各地的旅人互動。核心機制：依當前位置留下便條、
發現附近（100m）的便條、走近（50m）撿起便條。

## 技術棧
- 行動端：原生 iOS（夥伴開發），地圖用 MapKit，
  以 supabase-swift SDK 對接
- 資料庫：Supabase Postgres + PostGIS，region：東京 ap-northeast-1
- 後端語言：TypeScript
- Auth：Supabase 匿名登入起步，設計上須支援日後
  無縫升級綁定正式帳號

## 架構原則
- MVP 不建獨立 API server：業務邏輯以 Postgres functions（RPC）
  ＋ RLS 為主，Edge Functions 處理不適合 SQL 的邏輯；
  出現遷移訊號才建獨立 API 層
- 需要原子性的操作（如撿便條）在 DB 層以 row lock 處理併發
- 座標一律以 WGS-84（SRID 4326）儲存；GCJ-02 轉換
  留待中國市場啟動時處理
- 團隊很小：優先 boring technology，避免過度設計；
  schema 需考慮全球化，但單一 region 起步

## 協作分工
- 我負責後端；iOS 由夥伴開發
- docs/api/ 是 iOS 夥伴的介面契約：每個 RPC／endpoint 的
  參數、回傳 JSON、錯誤碼，附 supabase-swift 呼叫範例；
  介面變更必須同步更新文件

## 文件地圖
- 產品路線圖與各階段範圍：docs/roadmap.md
- 架構總覽：docs/architecture.md
- 重大決策紀錄：docs/adr/（編號遞增）