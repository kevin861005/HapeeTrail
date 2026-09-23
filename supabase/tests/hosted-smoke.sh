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
#   * 服務讀得到 hosted 真正的 auth.sessions（經 hapeetrail_private view）：GoTrue 登出刪列 ⇒ 立即 401
#   * 註銷用的 secret key 設對了、hosted gateway 認它（sb_secret 換 JWT 是文件描述，只有這裡驗得到）
#
# 匿名註冊 30 次/時/IP：這支用 2 次，與 newman（每輪 3 次）同一小時共用額度。
#
# 用法：
#   supabase/tests/hosted-smoke.sh <project-ref> <publishable-key> <service-base-url>
# 例：
#   supabase/tests/hosted-smoke.sh iwkuywlrggxolyoiyrui sb_publishable_xxx \
#     https://hapeetrail-api-134868178961.asia-northeast1.run.app
#   supabase/tests/hosted-smoke.sh http://127.0.0.1:54321 sb_publishable_xxx http://127.0.0.1:8080   # 本機乾跑
#
# publishable key 在 dashboard 的 Project Settings → API Keys。
# ⚠️ 不要傳 service_role key——這支只該用 client 憑證驗，那正是重點。
set -uo pipefail

U="用法：$0 <project-ref> <publishable-key> <service-base-url>"
REF="${1:?$U}"
KEY="${2:?$U}"
# 位址必填：留預設值就會跟著服務搬家而過時（T26 換址漏改過一次），
# 而過時的預設不會報錯，只會整片 000，看起來像服務掛了。
API="${3:?$U}"
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

# 每次跑換一個隨機地點：旅人 A 最後註銷會帶走他的便條，但中途失敗就會留下，固定座標跑久了會累積，
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
echo "⑥ Supabase 那一側：client 角色對資料表與 public schema 的函式零權限（ADR-0007 在 ADR-0011 下延續）"
# 切換（票 13，migration 20260827000000）之後：五支契約 RPC 與六支 helper 全數 drop、
# 資料表對 client 早已零權限 ⇒ `/rest/v1/*` 對 client 只剩 401／403／404。
# 這一段是**正面斷言**：哪天有人為了救火把 RPC 復活、或 default privileges 靜默把新物件
# 授權回 anon／authenticated，要在這裡當場紅，而不是等外洩。
#
# ⚠️ **每條都斷言「恰好那一個碼」，不收 401／403／404 任一皆可**。理由是這一段最貴的
# 失敗模式是假綠：
#   * 收 401 ⇒ TOKEN_A 哪天失效（過期、apikey 與 Bearer 搞混、GoTrue 改行為），整段會
#     因為「每一條都 401」而全綠，卻一次都沒真的碰到角色權限。
#   * 函式那組收 403 ⇒ 「函式被重建、只是把 EXECUTE revoke 掉」也會過，而那正是
#     migration 明講不接受的狀態（「為什麼是 drop 而不是 revoke」）。**只有 404 證明它不在了。**
expect() {  # expect <期望碼> <實得碼> <說明>
  if [ "$1" = "$2" ]; then ok "$3 → $2"
  else bad "$3 → $2（預期 $1）" "$(head -c 200 "$BODY")"; fi
}
SBAUTH=(-H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_A")
PROBE_ID="${NOTE_ID:-00000000-0000-0000-0000-000000000000}"
# 表還在（schema 不動），所以走得到認證 ⇒ 有效 token ＋ 無權限 ＝ 恰好 403
for q in "notes" "notes?select=*" "notes?select=id,picked_up_by" "notes?id=eq.$PROBE_ID"; do
  expect 403 "$(http "$SB/rest/v1/$q" "${SBAUTH[@]}")" "GET /rest/v1/${q}（表不可達）"
done
# ⚠️ PATCH／DELETE 一定要帶 filter：PostgREST 對無 WHERE 的整表寫入先回 400
# 「DELETE requires a WHERE clause」——那是**請求形狀**被擋，權限檢查根本沒跑到，
# 拿它當「不可達」的證據是假綠。帶了 filter 才會走到 42501 permission denied。
expect 403 "$(http -X POST "$SB/rest/v1/notes" "${SBAUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"content":"x"}')" "POST /rest/v1/notes（寫入面）"
for m in PATCH DELETE; do
  expect 403 "$(http -X "$m" "$SB/rest/v1/notes?id=eq.$PROBE_ID" "${SBAUTH[@]}" \
    -H 'Content-Type: application/json' -d '{"content":"x"}')" "$m /rest/v1/notes?id=eq.…（寫入面）"
done
# 11 支函式：**恰好 404**（PGRST202，schema cache 裡找不到）。送 {} 而簽名不符也會是 404，
# 所以這一條不區分「函式在但參數不對」與「函式已消失」——下一條的根路徑清單負責那一半。
for f in drop_note nearby_notes pickup_note my_notes my_collection \
         as_note_wire as_wire_ts as_cursor parse_cursor distance_m note_ttl; do
  expect 404 "$(http -X POST "$SB/rest/v1/rpc/$f" "${SBAUTH[@]}" \
    -H 'Content-Type: application/json' -d '{}')" "POST rpc/${f}（函式已不存在）"
done
# 根路徑清單：PostgREST 只列出呼叫者權限看得到的東西。
# ⚠️ **這一條在 hosted 上證得比看起來少**：實測 Supabase 對 client 直接回 401，
# 連 OpenAPI 清單都不給 ⇒ 它擋掉了「清單洩漏」，但**沒有**證明 authenticated 碰不到
# 任何物件。「沒有我沒想到的東西露得出來」那半由 `SmokeTest.clientRolesOwnNothingInPublic`
# 以 pg 目錄逐物件斷言（每次 `mvn test` 都跑，且是精確的），不靠這裡。
c=$(http "$SB/rest/v1/" "${SBAUTH[@]}")
if [ "$c" = "200" ]; then
  if leftover=$(python3 -c "
import json
d = json.load(open('$BODY'))
print(','.join(sorted(k for k in d.get('paths', {}) if k != '/')))
" 2>/dev/null) && [ -z "$leftover" ]; then
    ok "GET /rest/v1/ → 200，清單為空"
  else
    bad "GET /rest/v1/ 的清單不是空的" "仍列出：${leftover:-<解析失敗>}"
  fi
else
  expect 401 "$c" "GET /rest/v1/（清單對 client 不開放）"
fi
# anon（未登入，只帶 apikey）。**用 POST 打 rpc**：PostgREST 對 VOLATILE 函式本來就拒絕 GET，
# 拿 GET 的 404 當證據等於在驗 PostgREST 的方法限制，不是在驗函式已消失。
expect 401 "$(http "$SB/rest/v1/notes" -H "apikey: $KEY")" "anon（未登入）GET /rest/v1/notes"
for f in my_notes drop_note; do
  expect 404 "$(http -X POST "$SB/rest/v1/rpc/$f" -H "apikey: $KEY" \
    -H 'Content-Type: application/json' -d '{}')" "anon（未登入）POST rpc/$f"
done

echo
echo "⑦ 註銷（DELETE /v1/me）：secret key 過得了 hosted gateway、硬刪、cascade 真的跑"
# 放在 ⑥ 之後：⑥ 需要 TOKEN_A 活著。A 先留一張沒人撿的便條——「從探索消失」要先看得到才算數。
UNAUTH=$(http "$API/v1/me/notes"; printf ' '; cat "$BODY")   # 401 的凍結形狀，下面逐字比對
# 基準沒拿到就別往下比：連不上服務時基準與實測都是「000＋空 body」，兩條 401 斷言會一起假綠。
case "$UNAUTH" in
  401\ *) ;;
  *) bad "取不到 401 基準（無 token 打 ${API} 得到「${UNAUTH}」）" "服務連不上或 401 形狀變了；下面兩條 401 斷言一律算失敗"
     UNAUTH='<基準取得失敗>' ;;
esac
c=$(http -X POST "$API/v1/notes" "${A[@]}" \
      -d "{\"content\":\"hosted smoke 2\",\"coordinate\":{\"latitude\":$LAT,\"longitude\":$LNG}}")
NOTE2_ID=$(jget id)
# 探索本身失敗要回非零——否則「查不到」會被當成「已消失」而假綠。
nearby() {  # B 在 30m 外探索，印出結果裡的便條 id
  local q="{\"coordinate\":{\"latitude\":$NEAR,\"longitude\":$LNG}}" c
  c=$(http -X POST "$API/v1/notes/nearby" "${B[@]}" -d "$q")
  [ "$c" = "200" ] && python3 -c "import json;print(*(x['id'] for x in json.load(open('$BODY'))['items']))"
}
if [ "$c" = "200" ] && ids=$(nearby) && [[ " $ids " == *" $NOTE2_ID "* ]]; then ok "註銷前：A 的第二張便條在 B 的探索結果裡"
else bad "註銷前的基準不成立（留便條 ${c}，或 B 探索不到）" "$(head -c 300 "$BODY")"; fi

c=$(http -X DELETE "$API/v1/me" -H "Authorization: Bearer $TOKEN_A")
if [ "$c" = "204" ] && [ ! -s "$BODY" ]; then ok "DELETE /v1/me → 204 無 body"
else bad "DELETE /v1/me → ${c}（預期 204 無 body；500 多半是 HAPEETRAIL_GOTRUE_SECRET_KEY 不對）" "$(head -c 300 "$BODY")"; fi
c=$(http "$API/v1/me/notes" -H "Authorization: Bearer $TOKEN_A")
[ "$c $(cat "$BODY")" = "$UNAUTH" ] && ok "註銷後舊 token → 401，與無 token 逐字相同" \
  || bad "註銷後舊 token → ${c}（預期與無 token 逐字相同的 401）" "$(head -c 300 "$BODY")"
c=$(http -X DELETE "$API/v1/me" -H "Authorization: Bearer $TOKEN_A")
[ "$c" = "401" ] && ok "重試 DELETE /v1/me → 401（session 已隨帳號消失）" \
  || bad "重試 DELETE /v1/me → ${c}（預期 401）" "$(head -c 300 "$BODY")"
if ids=$(nearby) && [[ " $ids " != *" $NOTE2_ID "* ]]; then ok "A 沒被撿的便條從探索消失"
else bad "探索失敗，或 A 註銷後他沒被撿的便條還在" "$(head -c 300 "$BODY")"; fi
c=$(http "$API/v1/me/collection" "${B[@]}")
if [ "$c" = "200" ] && python3 -c "
import json,sys
sys.exit(0 if all(n['id'] != '$NOTE_ID' for n in json.load(open('$BODY'))['items']) else 1)"
then ok "A 被 B 撿走的便條也從 B 的收藏消失（cascade，不是空殼）"
else bad "B 的收藏回 ${c} 或仍含 A 的便條" "$(head -c 300 "$BODY")"; fi

echo
echo "⑧ 登出（Supabase /auth/v1/logout，不帶 scope＝global，同 supabase-swift signOut() 預設）→ 立即 401"
# ⑦ 最後一條剛證了 TOKEN_B 活著。重登入同一個帳號在這裡做不到：匿名帳號沒有憑證，
# refresh token 隨 session 一起 cascade——匿名旅人登出＝永遠離開，可逆的登出要等 T25 綁定帳號。
c=$(http -X POST "$SB/auth/v1/logout" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_B")
[ "$c" = "204" ] && ok "B 登出 → Supabase 204" || bad "B 登出 → ${c}（預期 204）" "$(head -c 300 "$BODY")"
c=$(http "$API/v1/me/notes" "${B[@]}")
[ "$c $(cat "$BODY")" = "$UNAUTH" ] && ok "登出後舊 token 打服務 → 401，與無 token 逐字相同（不必等 token 過期）" \
  || bad "登出後舊 token → ${c}（預期與無 token 逐字相同的 401）" "$(head -c 300 "$BODY")"

echo
echo "── 通過 $pass 項，失敗 $fail 項"
[ -n "$NOTE_ID" ] && echo "（測試便條 ${NOTE_ID}、${NOTE2_ID:-?} 應已隨旅人 A 註銷刪除；旅人 B 已登出）"
exit $(( fail > 0 ))
