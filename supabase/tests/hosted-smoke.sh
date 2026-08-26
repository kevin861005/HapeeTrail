#!/usr/bin/env bash
# 部署後煙霧測試（v4／Java 版）。
#
# `api/` 的 mvn 測試跑在 Testcontainers 上、驗的是業務規則；這支只驗
# **「部署到別的機器之後才可能壞掉」** 的那些事：
#   * 服務連得到 Supabase 的 session pooler，且 hapeetrail_api 的密碼與權限是對的
#   * 服務抓得到 GoTrue 的 JWKS，且認得它簽的 ES256（少了 JWS_ALGORITHMS 會每顆 token 靜靜 401）
#   * hosted 的 PostGIS 裝在 extensions schema 且服務的 search_path 找得到它
#   * 匿名登入開關還開著
#   * 不該可達的路徑確實不可達
#
# 用法：
#   supabase/tests/hosted-smoke.sh <project-ref> <publishable-key> [service-base-url]
# 例：
#   supabase/tests/hosted-smoke.sh iwkuywlrggxolyoiyrui sb_publishable_xxx
#   supabase/tests/hosted-smoke.sh http://127.0.0.1:54321 sb_publishable_xxx http://127.0.0.1:8080   # 本機乾跑
#
# publishable key 在 dashboard 的 Project Settings → API Keys。
# ⚠️ 不要傳 service_role key——這支只該用 client 憑證驗，那正是重點。
set -uo pipefail

REF="${1:?用法：$0 <project-ref> <publishable-key> [service-base-url]}"
KEY="${2:?用法：$0 <project-ref> <publishable-key> [service-base-url]}"
API="${3:-https://hapeetrail.fly.dev}"
# ref 也接受完整網址，好讓這支能對本機的 supabase 乾跑一次（部署前先確認它自己是對的）
case "$REF" in http*) SB="$REF" ;; *) SB="https://${REF}.supabase.co" ;; esac

# 固定檔名會在兩個人同時跑時互相覆蓋；mktemp 順便讓 trap 收得乾淨。
BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT

pass=0; fail=0
ok()   { printf '  ✅ %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  ❌ %s\n     → %s\n' "$1" "$2"; fail=$((fail+1)); }
# 先清空再打：curl 連不上時（http_code 000）不會寫檔，留著上一次的 body
# 會讓下面的錯誤訊息印出**別的請求**的內容。
http() { : > "$BODY"; curl -s -o "$BODY" -w '%{http_code}' "$@"; }
# JSON 的 null 經 python 會印成 "None"，而 "None" 對 [ -n ] 是真——
# 撿取那條斷言會在 pickedUpAt／content 都是 null 時假綠。
jget() { python3 -c "import json;v=json.load(open('$BODY')).get('$1');print('' if v is None else v)" 2>/dev/null; }

# 每次跑換一個隨機地點：API 沒有刪除路徑，固定座標跑久了會累積便條，
# 讓 nearby 的「最近 20 筆」把本次的目標擠掉——失敗起來很像後端 bug。
read -r LAT LNG < <(python3 -c 'import random;print(f"{random.uniform(-60,60):.7f} {random.uniform(-180,180):.7f}")')
# 位移只動緯度：每度的公尺數不隨經度改變，隨機地點的距離才穩定。
NEAR=$(python3 -c "print(f'{$LAT + 0.00027:.7f}')")   # ~30m ⇒ 撿得到
FAR=$(python3 -c "print(f'{$LAT + 0.00117:.7f}')")    # ~130m ⇒ too_far

echo "── 服務 ${API}"
echo "── Supabase ${SB}"
echo

echo "① 匿名登入（Supabase GoTrue；dashboard 的 Anonymous sign-ins 開關）"
c=$(http -X POST "$SB/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d '{}')
if [ "$c" = "200" ]; then
  TOKEN_A=$(jget access_token)
  ok "旅人 A signup 200，取得 access_token"
else
  bad "signup 回 ${c}（預期 200）" "$(head -c 200 "$BODY")"
  echo; echo "422 通常代表 Anonymous sign-ins 沒開：Authentication → Sign In / Providers"
  exit 1
fi
c=$(http -X POST "$SB/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d '{}')
if [ "$c" = "200" ]; then TOKEN_B=$(jget access_token); ok "旅人 B signup 200（撿取需要第二個身分）"
else bad "第二次 signup 回 ${c}" "$(head -c 200 "$BODY")"; exit 1; fi

# 業務請求只帶 Bearer——apikey 不是 v4 契約的一部分（notes.md 共同約定）。
A=(-H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json')
B=(-H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json')

echo
echo "② 五支業務端點可達且形狀正確（JWKS＋ES256、pooler 連線、hapeetrail_api 權限全在這裡驗掉）"
c=$(http -X POST "$API/v1/notes" "${A[@]}" \
      -d "{\"content\":\"hosted smoke  \",\"coordinate\":{\"latitude\":$LAT,\"longitude\":$LNG},\"color\":7,\"style\":3}")
if [ "$c" = "200" ]; then
  if BODY="$BODY" python3 - <<'PY'
import json, os
n = json.load(open(os.environ['BODY']))
keys = sorted(n)
want = ['audience','color','content','coordinate','createdAt','expiresAt','id','pickedUpAt','style']
assert keys == want, f'Note 不是契約的 9 鍵\n  得 {keys}\n  預期 {want}'
assert sorted(n['coordinate']) == ['latitude','longitude'], f'座標不是巢狀物件：{n["coordinate"]!r}'
assert n['content'] == 'hosted smoke', f'trim 沒生效：{n["content"]!r}'
assert n['color'] == 7 and n['style'] == 3, '代號沒有原樣回傳'
assert n['audience'] == 'anyone', 'audience 省略時應補 anyone'
assert n['pickedUpAt'] is None, '剛建立的便條不該有 pickedUpAt'
assert n['expiresAt'] is not None, '公開便條的 expiresAt 不該是 null'
PY
  then ok "POST /v1/notes → 200，Note 9 鍵、座標巢狀、trim 生效、代號原樣、expiresAt 有值"
  else bad "POST /v1/notes 的回傳形狀不對" "見上方 assert 訊息"; fi
else
  bad "POST /v1/notes 回 ${c}（預期 200）" "$(head -c 300 "$BODY")"
fi
NOTE_ID=$(jget id)

# 探索與撿取的距離都在 SQL 裡用 PostGIS geography 算——PostGIS 沒裝在 extensions
# schema、或服務的 search_path 找不到它，就是在這兩支爆掉。
c=$(http -X POST "$API/v1/notes/nearby" "${B[@]}" \
      -d "{\"coordinate\":{\"latitude\":$NEAR,\"longitude\":$LNG}}")
if [ "$c" = "200" ] && python3 -c "
import json,sys
b = json.load(open('$BODY'))
assert sorted(b) == ['items'], b.keys()
h = next((x for x in b['items'] if x['id'] == '$NOTE_ID'), None)
assert h, '剛留的便條沒出現在 30m 的探索結果裡'
assert sorted(h) == ['color','coordinate','createdAt','distanceM','id','pickable','style'], sorted(h)
assert 20 <= h['distanceM'] <= 40, f'距離算出來是 {h[\"distanceM\"]}m，預期約 30m'
assert h['pickable'] is True
"; then
  ok "POST /v1/notes/nearby → 200，NearbyHint 7 鍵、距離約 30m、pickable（PostGIS geography 可用）"
else
  bad "POST /v1/notes/nearby 回 ${c} 或形狀／距離不對" "$(head -c 300 "$BODY")"
fi

c=$(http -X POST "$API/v1/notes/$NOTE_ID/pickup" "${B[@]}" \
      -d "{\"coordinate\":{\"latitude\":$FAR,\"longitude\":$LNG}}")
if [ "$c" = "403" ] && [ "$(jget code)" = "too_far" ]; then
  d=$(python3 -c "import json;print(json.load(open('$BODY'))['details']['distanceM'])" 2>/dev/null)
  if [ -n "$d" ]; then ok "撿取距離閘門：130m 外 → 403 too_far，details.distanceM = ${d}（真物件，不需二次解析）"
  else bad "too_far 沒帶 details.distanceM" "$(head -c 300 "$BODY")"; fi
else
  bad "130m 外撿取回 ${c}/$(jget code)（預期 403/too_far）" "$(head -c 300 "$BODY")"
fi

c=$(http -X POST "$API/v1/notes/$NOTE_ID/pickup" "${B[@]}" \
      -d "{\"coordinate\":{\"latitude\":$NEAR,\"longitude\":$LNG}}")
if [ "$c" = "200" ] && [ -n "$(jget pickedUpAt)" ] && [ -n "$(jget content)" ]; then
  ok "POST /v1/notes/{id}/pickup → 200，30m 內撿得到、content 在此揭露"
else
  bad "30m 內撿取回 ${c}（預期 200 且帶 content／pickedUpAt）" "$(head -c 300 "$BODY")"
fi

c=$(http "$API/v1/me/notes?limit=1" "${A[@]}")
if [ "$c" = "200" ] && python3 -c "
import json
b = json.load(open('$BODY'))
assert sorted(b) == ['items','nextCursor'], sorted(b)
assert len(b['items']) == 1
"; then ok "GET /v1/me/notes → 200，{ items, nextCursor } envelope"
else bad "GET /v1/me/notes 回 ${c} 或 envelope 不對" "$(head -c 300 "$BODY")"; fi

c=$(http "$API/v1/me/collection" "${B[@]}")
if [ "$c" = "200" ] && python3 -c "
import json
b = json.load(open('$BODY'))
assert sorted(b) == ['items','nextCursor'], sorted(b)
assert any(n['id'] == '$NOTE_ID' for n in b['items']), '剛撿到的便條不在收藏裡'
"; then ok "GET /v1/me/collection → 200，剛撿到的便條在收藏裡"
else bad "GET /v1/me/collection 回 ${c} 或內容不對" "$(head -c 300 "$BODY")"; fi

echo
echo "③ Unicode 空白的 trim（全形空白 → content_empty）"
# ⚠️ v4 起 trim 在 Java（NoteService 自己的 White_Space 集），**不再取決於資料庫 locale**。
# 這條因此不再是「換一台機器才會壞」的事，留著是因為它是唯一驗到「部署的映像帶的是
# 對的那份字元集」的端到端斷言——C locale 那個舊風險已經隨 RPC 一起走掉。
c=$(http -X POST "$API/v1/notes" "${A[@]}" \
      -d "{\"content\":\"　　\",\"coordinate\":{\"latitude\":$LAT,\"longitude\":$LNG}}")
if [ "$c" = "400" ] && [ "$(jget code)" = "content_empty" ]; then
  ok "全形空白 → 400 content_empty"
else
  bad "全形空白應回 400/content_empty，實得 ${c}/$(jget code)" "$(head -c 300 "$BODY")"
fi

echo
echo "④ 無 token／壞 token 對服務全部 401（JWT fail-closed）"
for p in "v1/me/notes" "v1/me/collection"; do
  c=$(http "$API/$p")
  { [ "$c" = "401" ] && [ "$(jget code)" = "not_authenticated" ]; } \
    && ok "無 token GET /$p → 401 not_authenticated" \
    || bad "無 token GET /$p → ${c}/$(jget code)（預期 401/not_authenticated）" "$(head -c 200 "$BODY")"
done
for p in "v1/notes" "v1/notes/nearby"; do
  c=$(http -X POST "$API/$p" -H 'Content-Type: application/json' -d '{}')
  [ "$c" = "401" ] && ok "無 token POST /$p → 401" \
    || bad "無 token POST /$p → ${c}（預期 401）" "$(head -c 200 "$BODY")"
done
# 壞 token 五種變形同一個答案（notes.md §2）；這裡挑「簽章不符」那種——
# 它是唯一需要 JWKS 真的抓下來才判得出的，其餘光看格式就能拒。
c=$(http "$API/v1/me/notes" -H "Authorization: Bearer ${TOKEN_A%.*}.AAAAAAAA")
[ "$c" = "401" ] && ok "簽章被改過的 token → 401（JWKS 真的抓到了）" \
  || bad "簽章被改過的 token → ${c}（預期 401）" "$(head -c 200 "$BODY")"
# anon 身分：Supabase 的 publishable key 不是 JWT，服務不該認它。夥伴同時面對兩個
# base URL，最可能犯的錯就是把 apikey 原封不動當成 Bearer 送過來。
c=$(http "$API/v1/me/notes" -H "Authorization: Bearer $KEY")
[ "$c" = "401" ] && ok "把 Supabase publishable key 當 Bearer（anon）→ 401" \
  || bad "publishable key 當 Bearer → ${c}（預期 401）" "$(head -c 200 "$BODY")"
c=$(http "$API/v1/me/notes" -H "apikey: $KEY")
[ "$c" = "401" ] && ok "只帶 apikey header（v3 的習慣）→ 401" \
  || bad "只帶 apikey → ${c}（預期 401）" "$(head -c 200 "$BODY")"

echo
echo "⑤ /actuator/health 不需認證且只回健康狀態"
c=$(http "$API/actuator/health")
if [ "$c" = "200" ] && [ "$(jget status)" = "UP" ]; then
  ok "GET /actuator/health → 200 status=UP（不帶任何認證）"
else
  bad "GET /actuator/health → ${c}/$(jget status)（預期 200/UP）" "$(head -c 200 "$BODY")"
fi

echo
echo "⑥ Supabase 那一側：client 對資料表與內部 helper 零權限（ADR-0007）"
# 先驗「無論切不切換都必須不可達」的東西，再驗過渡期五支 RPC 的狀態。
SBAUTH=(-H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_A")
for q in "notes?select=*" "notes?select=id,picked_up_by"; do
  c=$(http "$SB/rest/v1/$q" "${SBAUTH[@]}")
  [ "$c" = "403" ] && ok "GET /rest/v1/$q → 403" \
    || bad "GET /rest/v1/$q → ${c}（預期 403）" "$(head -c 200 "$BODY")"
done
c=$(http -X POST "$SB/rest/v1/notes" "${SBAUTH[@]}" -H 'Content-Type: application/json' -d '{"content":"x"}')
{ [ "$c" = "403" ] || [ "$c" = "401" ]; } && ok "POST /rest/v1/notes（寫入面）→ ${c}" \
  || bad "POST /rest/v1/notes → ${c}（預期 401 或 403）" "$(head -c 200 "$BODY")"
for f in as_note_wire as_wire_ts as_cursor parse_cursor distance_m note_ttl; do
  c=$(http -X POST "$SB/rest/v1/rpc/$f" "${SBAUTH[@]}" -H 'Content-Type: application/json' -d '{}')
  # 404 = PostgREST 找不到符合簽名的函式（送 {} 本來就不符）；403 = 有函式但無權限。兩者都不算外洩
  { [ "$c" = "403" ] || [ "$c" = "404" ]; } && ok "rpc/$f → ${c}（不可用）" \
    || bad "rpc/$f → ${c}（預期 403 或 404）" "$(head -c 200 "$BODY")"
done
# ⚠️ 過渡期：v3.3 的五支契約 RPC 在切換日前仍然活著。這裡**正面斷言它們還在**——
# 提早被收掉要在這裡發現，不是等 iOS 回報。**票 13 把這一段整組翻面**：改成斷言
# 五支全部 401／403／404。
echo "  · 過渡期（票 13 翻面）：v3.3 五支契約 RPC 仍可達"
for spec in "my_notes:{\"p_limit\":1}" "my_collection:{\"p_limit\":1}" \
            "nearby_notes:{\"p_lat\":$LAT,\"p_lng\":$LNG}" \
            "drop_note:{\"p_content\":\"rpc smoke\",\"p_lat\":$LAT,\"p_lng\":$LNG}"; do
  f=${spec%%:*}
  c=$(http -X POST "$SB/rest/v1/rpc/$f" "${SBAUTH[@]}" -H 'Content-Type: application/json' -d "${spec#*:}")
  [ "$c" = "200" ] && ok "rpc/$f → 200（仍活著）" \
    || bad "rpc/$f → ${c}（切換前預期 200）" "$(head -c 200 "$BODY")"
done
# pickup_note 沒有無害的成功呼叫，改用「業務錯誤 ≠ 權限錯誤」判定：拿隨機 uuid 打，
# 回 400 P0001 note_not_found 就證明函式在、且呼叫者有 EXECUTE。
c=$(http -X POST "$SB/rest/v1/rpc/pickup_note" "${SBAUTH[@]}" -H 'Content-Type: application/json' \
      -d "{\"p_note_id\":\"00000000-0000-0000-0000-000000000000\",\"p_lat\":$LAT,\"p_lng\":$LNG}")
{ [ "$c" = "400" ] && [ "$(jget message)" = "note_not_found" ]; } \
  && ok "rpc/pickup_note → 400 note_not_found（仍活著；業務錯誤而非權限錯誤）" \
  || bad "rpc/pickup_note → ${c}/$(jget message)（切換前預期 400/note_not_found）" "$(head -c 200 "$BODY")"

for p in "rest/v1/notes" "rest/v1/rpc/my_notes"; do
  c=$(http "$SB/$p" -H "apikey: $KEY")
  [ "$c" = "401" ] && ok "anon（未登入）/$p → 401" \
    || bad "anon /$p → ${c}（預期 401）" "$(head -c 200 "$BODY")"
done

echo
echo "── 通過 $pass 項，失敗 $fail 項"
[ -n "$NOTE_ID" ] && echo "（此次建立的測試便條 id：${NOTE_ID}，已被旅人 B 撿走）"
exit $(( fail > 0 ))
