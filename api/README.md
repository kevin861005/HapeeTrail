# HapeeTrail API（Spring Boot）

業務規則全部在這裡（ADR-0011）。Supabase 只剩「代管 Postgres ＋ GoTrue」，
本服務只驗它簽的 JWT，不代理任何 auth 路徑。

## 本機測試

```bash
cd api && ./mvnw test        # 需要 Docker Desktop 開著（Testcontainers 起 Supabase Postgres）
```

## 設定（全部由環境變數注入）

前四個**缺了就啟動失敗**，這是刻意的：

| 環境變數 | 值 |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<pooler-host>:5432/postgres`（session pooler，**5432 不是 6543**） |
| `SPRING_DATASOURCE_USERNAME` | `hapeetrail_api.<project-ref>`（Supavisor 用 `<角色>.<專案ref>` 認 tenant） |
| `SPRING_DATASOURCE_PASSWORD` | 一次性手動 SQL 設的角色密碼 |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json` |

第五個**有預設值（RS256），漏了不會啟動失敗、只會每個請求靜靜 401**——所以打 hosted JWKS 時一定要帶：

| 環境變數 | 值 |
|---|---|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS` | `RS256,ES256`（Supabase 非對稱金鑰預設是 ES256） |

repo 內沒有任何機密值。`fly.toml` 只放可公開的設定；Fly 上這一個由 `fly.toml` 的 `[env]` 帶，
**為什麼不能放 `application.properties`** 寫在那裡的註解。

## 本機跑容器（不必碰 Fly）

```bash
docker build -t hapeetrail-api:local api
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/postgres \
  -e SPRING_DATASOURCE_USERNAME=... -e SPRING_DATASOURCE_PASSWORD=... \
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=... \
  -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS=RS256,ES256 \
  hapeetrail-api:local
```

## 部署到 Fly.io（東京 `nrt`）

### 一次性設定

```bash
brew install flyctl && fly auth login

# 1) 準備 migration 套到 hosted（建 hapeetrail_api 角色、授權、RLS policy）
supabase db push        # 會問 hosted 的 DB 密碼

# 2) 角色密碼：只設一次，值只存在 fly secrets（不進 migration、不進 git）
#    在 Supabase dashboard 的 SQL Editor 執行：
#      alter role hapeetrail_api password '<自己產一組長亂碼>';

# 3) dashboard → Authentication → Sign In / Providers → 開啟 Allow anonymous sign-ins，**按 Save**
#    整個 App 靠匿名登入；沒開的話 /auth/v1/signup 回 422 anonymous_provider_disabled，
#    下面驗證那條 curl 拿不到 token。

# 4) 建 app（不要讓 fly launch 覆寫既有的 fly.toml）
cd api && fly apps create hapeetrail

# 5) secrets（dashboard → Connect → Session pooler 抄連線資訊）
fly secrets set --app hapeetrail \
  SPRING_DATASOURCE_URL='jdbc:postgresql://<pooler-host>:5432/postgres' \
  SPRING_DATASOURCE_USERNAME='hapeetrail_api.<project-ref>' \
  SPRING_DATASOURCE_PASSWORD='<步驟 2 那組>' \
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI='https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json'
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
REF=<project-ref>
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
