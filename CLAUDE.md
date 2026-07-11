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

## 開發守則

### 主動查證，不憑記憶硬寫
- 遇到不確定的 API 用法、版本差異、最佳實踐
  （如 Supabase RPC 語法、PostGIS 函式、supabase-swift 慣例），
  先查證再動手：輕量問題直接查文件，需要大量閱讀或
  平行比較多個來源時，派 subagent 研究後回報結論
- 查證結果若推翻原計畫，先回報討論，不要靜默改方向

### 驗證不球員兼裁判
- 實作完成後，派獨立 subagent 驗證；驗證者只拿需求規格，
  不繼承實作過程的假設
- 重要功能至少兩個獨立視角：
  1. 安全審查：RLS 是否可繞過、參數驗證、濫用防護
  2. 正確性驗證：測試、邊界條件、併發情境
     （例如兩人同時撿同一張便條）
- 驗證發現的問題先回報，經同意再修

### 效能與規模意識
- 總原則：schema 與 API 契約按「未來多人規模」設計，
  基礎設施按「MVP 規模」部署
- 具體要求：所有列表查詢第一天就做 cursor-based 分頁；
  地理查詢必須用 EXPLAIN 確認走索引；避免 N+1 與全表掃描；
  可預期的擴充點在設計文件中標注
- 但禁止過度設計：MVP 用不到的 cache、queue、microservice
  不要引入；認為必要時提出討論，不要直接做

### Checklist 紀律
- 每個任務開始前，把計畫寫成 checklist 存到 docs/tasks/
  （如 sprint-1.md）；不要只放在對話裡，session 結束會消失
- 只做 checklist 上的項目。過程中發現的新需求或必要項目，
  一律先停下來與我討論，同意後才排入 checklist
- 每完成一項就標記 - [x] 並簡注結果，方便日後追蹤
- 嚴禁「順手做」：不在清單上的改動不做，避免範圍發散