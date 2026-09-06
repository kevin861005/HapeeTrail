# T26 — Cloud Run 測試部署 runbook

裁決 2026-09-06：Cloud Run `asia-northeast1`（東京），研究依據
`docs/research/2026-09-06-free-deploy-platforms.md`。定位＝**測試部署**，不推翻 ADR-0011
的 Fly 生產決定；若跑得順、要讓它取代 T23，另立 ADR 討論。

## 前置（Kevin 手動，Claude 做不了）

1. 建 GCP 帳號、開帳單帳戶（要綁卡）
2. `brew install google-cloud-sdk` → `gcloud auth login`
3. 建議：Billing → Budgets 設 US$1 預算告警（免費額度對測試流量過剩數量級，
   但東京出站流量不在免費額度內，設個哨兵安心）

## 部署步驟

```bash
gcloud projects create hapeetrail-test --set-as-default   # 名稱可改，全球唯一
gcloud billing accounts list                               # 抄 ACCOUNT_ID
gcloud billing projects link hapeetrail-test --billing-account=<ACCOUNT_ID>
gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com

# DB 密碼進 Secret Manager（read -s 讀進變數，別直接貼在指令裡——T19 踩過兩次 placeholder 當密碼）
read -s DB_PW
printf '%s' "$DB_PW" | gcloud secrets create spring-datasource-password --data-file=-
unset DB_PW

# 部署：用 --source 讓 Cloud Build 遠端建映像。
# ⚠️ 不要本機 build 直推：這台 Mac 是 Apple Silicon，本機映像是 arm64，Cloud Run 要 amd64。
# ⚠️ JWS_ALGORITHMS 的值含逗號，--set-env-vars 預設以逗號分隔 ⇒ 必須用自訂分隔符語法 ^@^
cd api
gcloud run deploy hapeetrail-api \
  --source . \
  --region asia-northeast1 \
  --allow-unauthenticated \
  --port 8080 \
  --memory 512Mi --cpu 1 \
  --min-instances 0 --max-instances 1 \
  --set-env-vars '^@^SPRING_DATASOURCE_URL=jdbc:postgresql://aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres?sslmode=require@SPRING_DATASOURCE_USERNAME=hapeetrail_api.iwkuywlrggxolyoiyrui@SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=https://iwkuywlrggxolyoiyrui.supabase.co/auth/v1/.well-known/jwks.json@SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS=RS256,ES256@HAPEETRAIL_JWT_ISSUER=https://iwkuywlrggxolyoiyrui.supabase.co/auth/v1' \
  --set-secrets 'SPRING_DATASOURCE_PASSWORD=spring-datasource-password:latest'
```

環境變數值以 `api/README.md` 的 runbook 為權威（上面的 URL／JWK 值抄自 fly.toml 與 HANDOFF，
執行前跟 README 逐字核對一次）。`HAPEETRAIL_JWT_ISSUER` 缺了啟動直接失敗、
`JWS_ALGORITHMS` 少了 ES256 就是全站靜靜 401——兩條都是 HANDOFF 雷區。

## 已知取捨

- **縮零冷啟動**：`min-instances 0` 下閒置後第一發要吃「容器拉起＋JVM＋Hikari 首連 pooler」，
  估 5–15 秒（fly.toml 註解估「十幾秒」），對手動測試可接受。受不了再談 `min-instances 1`（就要錢了）。
- **512Mi**：本機實測服務 300MB 內可活；OOM 再升 1Gi（Fly 設定用 1GB）。
- Secret Manager 免費額度 6 個 active secret version，這裡用 1 個。

## 部署後驗收（照 T19 票 11 的套路）——2026-09-06 執行

服務 URL：`https://hapeetrail-api-134868178961.asia-northeast1.run.app`（revision 00001）

- [x] health → 200 UP，含冷啟動全程 2.2 秒
- [x] 無 token／壞 token → 401 problem+json，鍵序 `type,status,title,code` 正確
- [x] 真 GoTrue token（匿名 signup）→ `GET /v1/me/notes` 200 `{"items":[],"nextCursor":null}`
- [x] newman 5 輪 **80/80 斷言 0 失敗**（8.4s，平均 91ms；以 `--env-var` 覆寫 base_url＋apikey）
- [x] Kevin 手機走行動網路開 health → UP（tailnet 外可達證實）
- [x] 契約三檔換址：openapi `servers[0]`、postman hosted env `base_url`；notes.md 只指向 openapi
      不重述值。人工三處比對一致；`check-contract.py` exit 0、`redocly lint` valid（1 警告為既有）
- [ ] commit＋push（Pages 讀 repo 的 yaml，push 才更新）→ 通知夥伴改 base URL＋移除 ATS 例外
- [ ] Mac 容器退役或留備援（等夥伴切換確認後決定）
- [ ] （可選）US$1 預算告警

部署過程實錄：帳單帳戶已存在免綁卡；secret 由本機容器 env 直接管線灌入（不經對話）；
`--set-env-vars` 用 `^@^` 分隔符處理 `RS256,ES256` 的逗號，實證可行。

## 與 T23 的關係

T23（付費上 Fly）暫緩不刪：Cloud Run 測試期表現若穩，「生產也用 Cloud Run（或付費開常駐）」
vs「照 ADR-0011 上 Fly」是一個要重新裁決的 ADR 級決定，到時再開。
