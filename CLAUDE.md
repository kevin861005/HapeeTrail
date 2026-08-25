# HapeeTrail

旅遊足跡 App：讓全球旅人在景點留下便條紙、記錄足跡，
並與世界各地的旅人互動。核心機制：依當前位置留下便條、
發現附近（100m）的便條、走近（50m）撿起便條。

## 技術棧

> 本節與「架構原則」描述 ADR-0011 的目標架構。Java 版施工中（T19），
> 驗收通過前線上仍是 Supabase RPC 版。

- 後端：Spring Boot 4.1／Java 21 服務（Maven，repo 內 `api/`），
  容器化部署於東京、常駐不縮零
- 資料庫：Supabase 代管 Postgres 17 + PostGIS，region：東京 ap-northeast-1
- Auth：Supabase GoTrue，匿名登入起步，設計上須支援日後
  無縫升級綁定正式帳號；服務只驗它簽的 JWT，不代理任何 auth 路徑
- 行動端：原生 iOS（夥伴開發），地圖用 MapKit；**兩個 base URL**——
  `/auth/v1/*` 打 Supabase（supabase-swift），業務 API 打 HapeeTrail 服務

## 架構原則
- 業務規則全部在 Java 服務：距離判定、便條上限、撿取頻率閘門、
  TTL、內容驗證、游標分頁；DB 端不放業務函式（ADR-0011）
- DB 存取用 `JdbcClient` 寫普通 SQL，不用 JPA；單一實作不抽介面
- 距離與半徑一律在 SQL 語句內用 PostGIS geography 運算，
  Java 永遠不自己算距離——探索、撿取共用同一算法
- 撿便條的原子性靠單句條件式 UPDATE（`RETURNING`）；
  診斷只在影響 0 列後才跑，happy path 一句 SQL
- 服務以最小權限角色 `hapeetrail_api` 連線，不用 `postgres` 超級使用者
- schema 與 migration 仍由 `supabase/migrations` ＋ Supabase CLI 管，
  不引入 Flyway
- client 角色對 `/rest/v1/*` 零權限：資料進出的唯一路徑是
  HapeeTrail 服務（ADR-0007 延續）
- 座標一律以 WGS-84（SRID 4326）儲存；GCJ-02 轉換
  留待中國市場啟動時處理
- 團隊很小：優先 boring technology，避免過度設計；
  schema 需考慮全球化，但單一 region 起步

## 協作分工
- 我負責後端；iOS 由夥伴開發
- docs/api/ 是 iOS 夥伴的介面契約：每個 endpoint 的
  參數、回傳 JSON、錯誤碼，附 curl 範例；文件保持語言中立
  ——不放任何 client 語言（Swift 等）程式碼，不替 iOS 做
  實作決定；介面變更必須同步更新文件

## 文件地圖
- 產品路線圖與各階段範圍：docs/roadmap.md
- 重大決策紀錄：docs/adr/（編號遞增）；後端 Java 化見 ADR-0011
- 後端全換 Java 的 spec 與施工票：.scratch/java-rewrite/
  （spec.md、issues/、README.md 施工順序表）

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

### 隱私
- 日誌不記座標與便條內容：INFO 層只記路徑、狀態碼、耗時、
  錯誤 code；請求 body 永不落日誌

### 效能與規模意識
- 總原則：schema 與 API 契約按「未來多人規模」設計，
  基礎設施按「MVP 規模」部署
- 具體要求：所有列表查詢第一天就做 cursor-based 分頁；
  地理查詢必須用 EXPLAIN 確認走索引；避免 N+1 與全表掃描；
  可預期的擴充點在設計文件中標注
- 但禁止過度設計：MVP 用不到的 cache、queue、microservice
  不要引入；認為必要時提出討論，不要直接做

### Ticket 紀律
- 每個任務開始前，規劃走 Matt 流程（/to-spec → /to-tickets）；
  tickets 棲息地依 skill 而定（tracker 或 .scratch/<feature>/issues/），
  不要只放在對話裡，session 結束會消失
- docs/tasks/ 既有的 checklist 一律視為 legacy 施工紀錄，僅供查閱
- 只做 ticket 上的項目。過程中發現的新需求或必要項目，
  一律先停下來與我討論，同意後才立新 ticket
- 每完成一張 ticket 就標記完成並簡注結果，方便日後追蹤
- 嚴禁「順手做」：沒有 ticket 的改動不做，避免範圍發散