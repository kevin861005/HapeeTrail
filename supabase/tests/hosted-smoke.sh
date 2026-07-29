#!/usr/bin/env bash
# hosted 專案的部署後煙霧測試。
#
# 本機的 notes.test.sql 跑在 superuser 上、驗的是 SQL 邊界；這支只驗
# **「部署到別的機器之後才可能壞掉」** 的那些事：擴充套件的 schema、資料庫 locale、
# 匿名登入開關、契約 RPC 的可達性、以及不該可達的路徑確實不可達。
#
# 用法：
#   supabase/tests/hosted-smoke.sh <project-ref> <publishable-key>
# 例：
#   supabase/tests/hosted-smoke.sh ndaxaajmcryxsppxhjhs sb_publishable_xxx
#
# publishable key 在 dashboard 的 Project Settings → API Keys。
# ⚠️ 不要傳 service_role key——這支只該用 client 角色驗，那正是重點。
set -uo pipefail

REF="${1:?用法：$0 <project-ref> <publishable-key>}"
KEY="${2:?用法：$0 <project-ref> <publishable-key>}"
BASE="https://${REF}.supabase.co"

pass=0; fail=0
ok()   { printf '  ✅ %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  ❌ %s\n     → %s\n' "$1" "$2"; fail=$((fail+1)); }
code() { curl -s -o /tmp/hs_body -w '%{http_code}' "$@"; }

echo "── ${BASE}"
echo
echo "① 匿名登入（dashboard 的 Anonymous sign-ins 開關）"
c=$(code -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d '{}')
if [ "$c" = "200" ]; then
  TOKEN=$(python3 -c 'import json;print(json.load(open("/tmp/hs_body"))["access_token"])')
  ok "signup 200，取得 access_token"
else
  bad "signup 回 ${c}（預期 200）" "$(head -c 200 /tmp/hs_body)"
  echo; echo "422 通常代表 Anonymous sign-ins 沒開：Authentication → Sign In / Providers"
  exit 1
fi
AUTH=(-H "apikey: $KEY" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

echo
echo "② 契約 RPC 可達且行為正確（PostGIS 若不在 extensions schema，drop_note 會在此爆掉）"
c=$(code -X POST "$BASE/rest/v1/rpc/drop_note" "${AUTH[@]}" \
      -d '{"p_content":"hosted smoke  ","p_lat":35.6595,"p_lng":139.7005,"p_color":7,"p_style":3}')
if [ "$c" = "200" ]; then
  python3 - <<'PY'
import json
n=json.load(open('/tmp/hs_body'))
keys=sorted(n)
want=['audience','color','content','coordinate','createdAt','expiresAt','id','pickedUpAt','style']
assert keys==want, f'鍵集不符\n  得 {keys}\n  預期 {want}'
assert n['content']=='hosted smoke', f'trim 沒生效：{n["content"]!r}'
assert n['color']==7 and n['style']==3, '代號沒有原樣回傳'
assert n['expiresAt'] is not None, '公開便條的 expiresAt 不該是 null'
open('/tmp/hs_note_id','w').write(n['id'])
PY
  if [ $? -eq 0 ]; then ok "drop_note 200，Note 9 鍵、trim 生效、代號原樣、expiresAt 有值"
  else bad "drop_note 的回傳形狀不對" "見上方 assert 訊息"; fi
else
  bad "drop_note 回 ${c}（預期 200）" "$(head -c 300 /tmp/hs_body)"
fi

c=$(code -X POST "$BASE/rest/v1/rpc/nearby_notes" "${AUTH[@]}" -d '{"p_lat":35.65977,"p_lng":139.7005}')
[ "$c" = "200" ] && ok "nearby_notes 200（PostGIS 的 st_dwithin 可用）" \
                 || bad "nearby_notes 回 $c" "$(head -c 300 /tmp/hs_body)"

c=$(code -X POST "$BASE/rest/v1/rpc/my_notes" "${AUTH[@]}" -d '{"p_limit":1}')
[ "$c" = "200" ] && ok "my_notes 200（游標與 envelope）" \
                 || bad "my_notes 回 $c" "$(head -c 300 /tmp/hs_body)"

echo
echo "③ 資料庫 locale：T16 的 Unicode 空白 trim 是否真的生效（C locale 下會靜默失效）"
c=$(code -X POST "$BASE/rest/v1/rpc/drop_note" "${AUTH[@]}" \
      -d '{"p_content":"　　","p_lat":35.6595,"p_lng":139.7005}')
msg=$(python3 -c 'import json;print(json.load(open("/tmp/hs_body")).get("message",""))' 2>/dev/null)
if [ "$c" = "400" ] && [ "$msg" = "content_empty" ]; then
  ok "全形空白 → content_empty（locale 支援 Unicode 空白）"
else
  bad "全形空白應回 400/content_empty，實得 $c/$msg" \
      "資料庫 ctype 可能是 C locale ⇒ ADR-0009 的 trim 對全形空白與 NBSP 失效"
fi

echo
echo "④ 契約外路徑確實不可達（ADR-0007：client 對資料表無任何權限）"
for q in "notes?select=*" "notes?select=id,picked_up_by"; do
  c=$(code "$BASE/rest/v1/$q" "${AUTH[@]}")
  [ "$c" = "403" ] && ok "GET /rest/v1/$q → 403" || bad "GET /rest/v1/$q → ${c}（預期 403）" "$(head -c 200 /tmp/hs_body)"
done
for f in as_note_wire as_wire_ts as_cursor parse_cursor distance_m note_ttl; do
  c=$(code -X POST "$BASE/rest/v1/rpc/$f" "${AUTH[@]}" -d '{}')
  # 404 = PostgREST 找不到符合簽名的函式（送 {} 本來就不符）；403 = 有函式但無權限。兩者都不算外洩
  { [ "$c" = "403" ] || [ "$c" = "404" ]; } && ok "rpc/$f → ${c}（不可用）" \
    || bad "rpc/$f → ${c}（預期 403 或 404）" "$(head -c 200 /tmp/hs_body)"
done

echo
echo "⑤ anon（未登入）完全不可達"
for p in "rest/v1/notes" "rest/v1/rpc/my_notes"; do
  c=$(code "$BASE/$p" -H "apikey: $KEY")
  [ "$c" = "401" ] && ok "anon $p → 401" || bad "anon $p → ${c}（預期 401）" "$(head -c 200 /tmp/hs_body)"
done

echo
echo "── 通過 $pass 項，失敗 $fail 項"
[ -f /tmp/hs_note_id ] && echo "（此次建立的測試便條 id：$(cat /tmp/hs_note_id)，90 天後自動退出地圖）"
rm -f /tmp/hs_body /tmp/hs_note_id
exit $(( fail > 0 ))
