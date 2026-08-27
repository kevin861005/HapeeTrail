# HapeeTrail API（Spring Boot）

業務規則全部在這裡（ADR-0011）。Supabase 只剩「代管 Postgres ＋ GoTrue」，
本服務只驗它簽的 JWT，不代理任何 auth 路徑。

## 本機測試

```bash
cd api && ./mvnw test        # 需要 Docker Desktop 開著（Testcontainers 起 Supabase Postgres）
```

## 設定（全部由環境變數注入）

這五個**缺了就啟動失敗**，這是刻意的：

| 環境變數 | 值 |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres?sslmode=require`（session pooler，**5432 不是 6543**） |
| `SPRING_DATASOURCE_USERNAME` | `hapeetrail_api.iwkuywlrggxolyoiyrui`（Supavisor 用 `<角色>.<專案ref>` 認 tenant——對自訂角色也適用，已探針證實） |
| `SPRING_DATASOURCE_PASSWORD` | 一次性手動 SQL 設的角色密碼 |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | `https://iwkuywlrggxolyoiyrui.supabase.co/auth/v1/.well-known/jwks.json` |
| `HAPEETRAIL_JWT_ISSUER` | `https://iwkuywlrggxolyoiyrui.supabase.co/auth/v1`（驗 `iss`；`aud=authenticated` 是所有 Supabase 專案的共同值，擋跨專案 token 靠這個） |

下面這個**有預設值（RS256），漏了不會啟動失敗、只會每個請求靜靜 401**——所以打 hosted JWKS 時一定要帶：

| 環境變數 | 值 |
|---|---|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS` | `RS256,ES256`——**這個專案實測就是 `ES256`**，少了它每顆真 token 都 401 |

repo 內沒有任何機密值。`fly.toml` 只放可公開的設定：Fly 上 `JWS_ALGORITHMS` 與
`HAPEETRAIL_JWT_ISSUER` 兩個都由 `[env]` 帶（都不是機密），其餘走 `fly secrets`。
`JWS_ALGORITHMS` **為什麼不能放 `application.properties`** 寫在 `fly.toml` 的註解裡。

## 本機跑容器（不必碰 Fly）

```bash
docker build -t hapeetrail-api:local api
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/postgres \
  -e SPRING_DATASOURCE_USERNAME=... -e SPRING_DATASOURCE_PASSWORD=... \
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=... \
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS=RS256,ES256 \
  -e HAPEETRAIL_JWT_ISSUER=https://<專案ref>.supabase.co/auth/v1 \
  hapeetrail-api:local
```

## 部署到 Fly.io（東京 `nrt`）

> **狀態（2026-08-25）：刻意延後。** 步驟 1–3 已完成、flyctl 已安裝登入；
> 只剩 `fly` 那三步，等 iOS 夥伴約定開工日前幾天再做。理由與已查證的事實見
> `.scratch/java-rewrite/issues/10-first-deploy-fly.md`。Fly 無免費方案，
> 常駐 shared-cpu-1x／1GB 約 US$5.70／月，沒人用時開著是純燒錢。

### 一次性設定

```bash
brew install flyctl && fly auth login

# 1) 準備 migration 套到 hosted（建 hapeetrail_api 角色、授權、RLS policy）
#    ✅ 2026-08-25 已完成
supabase db push        # 會問 hosted 的 DB 密碼

# 2) 角色密碼：只設一次，值只存在 fly secrets（不進 migration、不進 git）
#    ✅ 2026-08-25 已完成。密碼用 `openssl rand -base64 24` 產，在 SQL Editor 執行：
#      alter role hapeetrail_api password '<那組密碼>';
#    ⚠️ 執行完把 SQL Editor 的 query 清掉——dashboard 會留 query history。

# 3) dashboard → Authentication → Sign In / Providers → 開啟 Allow anonymous sign-ins，**按 Save**
#    ✅ 2026-08-25 確認已開啟。整個 App 靠匿名登入；沒開的話 /auth/v1/signup 回
#    422 anonymous_provider_disabled，下面驗證那條 curl 拿不到 token。

# 4) 建 app（不要讓 fly launch 覆寫既有的 fly.toml）
cd api && fly apps create hapeetrail

# 5) secrets（pooler host 已探針證實是 aws-0-，不是 aws-1-）
# 值全部已查證，只有密碼要你自己貼（它不該出現在任何檔案裡）
fly secrets set --app hapeetrail \
  SPRING_DATASOURCE_URL='jdbc:postgresql://aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres?sslmode=require' \
  SPRING_DATASOURCE_USERNAME='hapeetrail_api.iwkuywlrggxolyoiyrui' \
  SPRING_DATASOURCE_PASSWORD='<步驟 2 那組>' \
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI='https://iwkuywlrggxolyoiyrui.supabase.co/auth/v1/.well-known/jwks.json'
# HAPEETRAIL_JWT_ISSUER 不是機密，已寫在 fly.toml 的 [env]——放這裡會被 secrets 蓋掉、兩處漂移
```

### 每次部署

```bash
cd api && fly deploy
```

**首次**要加 `--ha=false`：`fly deploy` 的 `--ha` 預設 true，會多開一台待命機器，
而這一票要的是常駐**一台**。之後的部署沿用既有機器，不必再帶。

```bash
cd api && fly deploy --ha=false
```

### 部署後驗證（四條，一分鐘）

```bash
APP=https://hapeetrail.fly.dev
REF=iwkuywlrggxolyoiyrui
ANON=$(supabase projects api-keys --project-ref $REF | grep publishable | awk '{print $4}')

curl -s -o /dev/null -w '%{http_code}\n' $APP/actuator/health          # 200
curl -s -o /dev/null -w '%{http_code}\n' $APP/v1/me/notes              # 401
curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer not.a.token' $APP/v1/me/notes   # 401

# 真的 GoTrue 匿名 token（JWKS 驗證的唯一實證）
TOKEN=$(curl -s -X POST "https://$REF.supabase.co/auth/v1/signup" \
  -H "apikey: $ANON" -H 'Content-Type: application/json' -d '{}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
curl -s -H "Authorization: Bearer $TOKEN" $APP/v1/me/notes             # {"items":[],"nextCursor":null}
```

`fly logs --app hapeetrail` 看啟動與健康檢查；`fly status` 看機器數（應為 1，不縮零）。
