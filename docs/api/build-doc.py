#!/usr/bin/env python3
"""從 openapi.yaml 產出一份可分享的單檔 HTML 契約文件。

**這是產生器，不是第四份契約產出。** 產物完全由 openapi.yaml 推導，
手改產物一定會被下次執行蓋掉——要改內容請改 openapi.yaml。

用法：
    docs/api/build-doc.py [輸出路徑]        # 預設輸出到 /tmp/trailstamp-api.html

需要 npx（用 redocly 把 YAML 併成 JSON，順帶做 lint 之外的 $ref 展開）。
"""
import html
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/trailstamp-api.html")


def load_spec() -> dict:
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        tmp = f.name
    r = subprocess.run(
        ["npx", "-y", "@redocly/cli@latest", "bundle", str(ROOT / "openapi.yaml"),
         "-o", tmp, "--ext", "json"],
        capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"redocly bundle 失敗：\n{r.stderr}")
    return json.loads(Path(tmp).read_text(encoding="utf-8"))


def e(s) -> str:
    return html.escape(str(s if s is not None else ""))


def md_inline(s: str) -> str:
    """openapi 的 description 是給人讀的 Markdown 片段，只處理最常用的三種。"""
    out = e(s)
    for a, b in (("**", "strong"), ("`", "code")):
        parts = out.split(a)
        if len(parts) > 1 and len(parts) % 2 == 1:
            out = "".join(p if i % 2 == 0 else f"<{b}>{p}</{b}>"
                          for i, p in enumerate(parts))
    return out.replace("\n", "<br>")


def type_of(schema: dict) -> str:
    if not schema:
        return "—"
    if "allOf" in schema:
        merged = {}
        for s in schema["allOf"]:
            merged.update(s)
        merged.update({k: v for k, v in schema.items() if k != "allOf"})
        schema = merged
    t = schema.get("type", "")
    if t == "array":
        return f"{type_of(schema.get('items', {}))}[]"
    if schema.get("enum"):
        return " ｜ ".join(f"<code>{e(v)}</code>" for v in schema["enum"])
    fmt = schema.get("format")
    label = {"integer": "integer", "number": "number", "string": "string",
             "boolean": "boolean", "object": "object"}.get(t, t or "—")
    if fmt:
        label += f" ({fmt})"
    if schema.get("nullable"):
        label += " ｜ null"
    return label


def resolve(schema: dict) -> dict:
    """把 allOf 攤平，方便直接讀 properties/required。"""
    if "allOf" in schema:
        merged = {}
        for s in schema["allOf"]:
            merged.update(resolve(s))
        merged.update({k: v for k, v in schema.items() if k != "allOf"})
        return merged
    return schema


def props_table(schema: dict, required: set) -> str:
    schema = resolve(schema)
    rows = []
    for name, p in (schema.get("properties") or {}).items():
        p = resolve(p)
        req = ' <span class="req">必填</span>' if name in required else ""
        desc = md_inline(p.get("description", ""))
        rows.append(
            f'<tr><td class="k"><code>{e(name)}</code>{req}</td>'
            f'<td class="t">{type_of(p)}</td>'
            f'<td class="d">{desc}</td></tr>')
    if not rows:
        return ""
    return ('<div class="tw"><table><thead><tr><th>欄位</th><th>型別</th>'
            '<th>說明</th></tr></thead><tbody>' + "".join(rows) + "</tbody></table></div>")


def endpoint_block(path: str, op: dict, spec: dict) -> str:
    fn = path.rsplit("/", 1)[-1]
    body = (op.get("requestBody", {}).get("content", {})
              .get("application/json", {}).get("schema", {}))
    body = resolve(body)
    req = set(body.get("required", []))

    # 回傳 schema 的名稱（從 $ref 展開後拿不到名字，改用 200 回應的 description）
    resp = op.get("responses", {}).get("200", {})
    resp_schema = (resp.get("content", {}).get("application/json", {}).get("schema", {}))

    parts = [f'<section id="{e(fn)}">',
             f'<h3><span class="method">POST</span> <code>{e(path)}</code></h3>',
             f'<p class="summary">{md_inline(op.get("summary", ""))}</p>',
             f'<div class="desc">{md_inline(op.get("description", "").strip())}</div>']

    if body.get("properties"):
        parts.append('<h4>請求 body</h4>')
        parts.append(props_table(body, req))
    else:
        parts.append('<h4>請求 body</h4><p class="note">無必填參數，可送 <code>{}</code>。</p>')

    ex = None
    for src in (body.get("example"),
                op.get("requestBody", {}).get("content", {})
                  .get("application/json", {}).get("example")):
        if src:
            ex = src
            break
    if ex:
        parts.append('<h4>curl</h4>')
        parts.append(curl_block(path, ex, spec))

    parts.append(f'<h4>回應 200 <span class="muted">— {md_inline(resp.get("description",""))}</span></h4>')
    parts.append(props_table(resp_schema, set(resolve(resp_schema).get("required", []))))
    parts.append("</section>")
    return "\n".join(parts)


def curl_block(path: str, example: dict, spec: dict) -> str:
    base = spec["servers"][0]["url"]
    payload = json.dumps(example, ensure_ascii=False)
    cmd = (f'curl -X POST "{base}{path}" \\\n'
           f'  -H "apikey: $KEY" \\\n'
           f'  -H "Authorization: Bearer $TOKEN" \\\n'
           f'  -H "Content-Type: application/json" \\\n'
           f"  -d '{payload}'")
    return f'<pre class="curl"><code>{e(cmd)}</code></pre>'


def build(spec: dict) -> str:
    info = spec["info"]
    base = spec["servers"][0]["url"]
    schemas = spec["components"]["schemas"]
    tokens = schemas["ApiError"]["properties"]["message"]["enum"]

    rpcs = [(p, o["post"]) for p, o in spec["paths"].items()
            if p.startswith("/rest/v1/rpc/")]
    nav = "".join(
        f'<a href="#{e(p.rsplit("/",1)[-1])}"><code>{e(p.rsplit("/",1)[-1])}</code></a>'
        for p, _ in rpcs)

    shapes = "".join(
        f'<section id="schema-{e(n)}"><h3>{e(n)}</h3>'
        f'<div class="desc">{md_inline(resolve(schemas[n]).get("description","").strip())}</div>'
        f'{props_table(schemas[n], set(resolve(schemas[n]).get("required", [])))}</section>'
        for n in ("Note", "NearbyHint", "NotePage", "NearbyResult", "ApiError")
        if n in schemas)

    token_rows = "".join(f"<li><code>{e(t)}</code></li>" for t in tokens)

    signup = ('curl -X POST "%s/auth/v1/signup" \\\n'
              '  -H "apikey: $KEY" \\\n'
              '  -H "Content-Type: application/json" -d \'{}\'') % base

    return f"""<title>Trailstamp Notes API — 契約 v{e(info['version'])}</title>
<style>
:root {{
  --ground:#FBFBF9; --raise:#FFFFFF; --ink:#1B1F24; --muted:#6B7269;
  --rule:#E4E4DE; --accent:#1F5F5B; --accent-soft:#E8F1EF; --danger:#9C3328;
  --code-bg:#F3F3F0;
}}
@media (prefers-color-scheme: dark) {{
  :root {{
    --ground:#14171A; --raise:#1B1F23; --ink:#E7EAE5; --muted:#9AA39A;
    --rule:#282D31; --accent:#6FC3B6; --accent-soft:#172E2C; --danger:#E58C7F;
    --code-bg:#1F2428;
  }}
}}
:root[data-theme="dark"] {{
  --ground:#14171A; --raise:#1B1F23; --ink:#E7EAE5; --muted:#9AA39A;
  --rule:#282D31; --accent:#6FC3B6; --accent-soft:#172E2C; --danger:#E58C7F;
  --code-bg:#1F2428;
}}
:root[data-theme="light"] {{
  --ground:#FBFBF9; --raise:#FFFFFF; --ink:#1B1F24; --muted:#6B7269;
  --rule:#E4E4DE; --accent:#1F5F5B; --accent-soft:#E8F1EF; --danger:#9C3328;
  --code-bg:#F3F3F0;
}}
* {{ box-sizing:border-box; }}
body {{
  margin:0; background:var(--ground); color:var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans TC",
               "PingFang TC", "Microsoft JhengHei", sans-serif;
  font-size:15px; line-height:1.7;
}}
code, pre, .t {{ font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace; }}
.wrap {{ display:grid; grid-template-columns:minmax(0,1fr); max-width:1180px;
        margin:0 auto; padding:0 24px 96px; gap:48px; }}
@media (min-width:960px) {{ .wrap {{ grid-template-columns:210px minmax(0,1fr); }} }}
header {{ grid-column:1/-1; border-bottom:1px solid var(--rule); padding:56px 0 32px;
          display:flex; flex-wrap:wrap; gap:24px; align-items:baseline;
          justify-content:space-between; }}
h1 {{ font-family: ui-serif, "Songti TC", Georgia, serif; font-weight:600;
      font-size:2rem; margin:0; letter-spacing:-.01em; text-wrap:balance; }}
h1 .v {{ color:var(--accent); font-size:1rem; font-family:ui-monospace,monospace;
         margin-left:.6em; vertical-align:middle; }}
.tag {{ font-size:.7rem; letter-spacing:.14em; text-transform:uppercase;
        color:var(--muted); display:block; margin-bottom:.5rem; }}
.base {{ background:var(--accent-soft); border:1px solid var(--rule); border-radius:4px;
         padding:10px 14px; font-family:ui-monospace,monospace; font-size:.82rem;
         word-break:break-all; max-width:100%; }}
nav {{ align-self:start; }}
@media (min-width:960px) {{ nav {{ position:sticky; top:28px; }} }}
nav .tag {{ margin-bottom:.75rem; }}
nav a {{ display:block; color:var(--ink); text-decoration:none; padding:5px 0;
         font-size:.85rem; border-bottom:1px solid transparent; }}
nav a:hover, nav a:focus-visible {{ color:var(--accent); border-bottom-color:var(--accent); }}
main {{ min-width:0; }}
section {{ padding:36px 0; border-top:1px solid var(--rule); }}
section:first-of-type {{ border-top:none; }}
h2 {{ font-family: ui-serif,"Songti TC",Georgia,serif; font-size:1.35rem;
      margin:0 0 .3em; font-weight:600; }}
h3 {{ font-size:1.05rem; margin:0 0 .4em; font-weight:600; display:flex;
      flex-wrap:wrap; gap:.5em; align-items:center; }}
h3 code {{ font-size:.9rem; }}
h4 {{ font-size:.72rem; letter-spacing:.12em; text-transform:uppercase;
      color:var(--muted); margin:28px 0 10px; font-weight:600; }}
.method {{ background:var(--accent); color:var(--ground); font-size:.62rem;
           letter-spacing:.1em; padding:3px 7px; border-radius:3px; font-weight:700;
           font-family:ui-monospace,monospace; }}
.summary {{ margin:.2em 0 .8em; color:var(--ink); }}
.desc {{ color:var(--muted); font-size:.9rem; max-width:68ch; }}
.note {{ color:var(--muted); font-size:.9rem; }}
p {{ margin:.5em 0; max-width:68ch; }}
code {{ background:var(--code-bg); padding:.12em .38em; border-radius:3px; font-size:.86em; }}
pre {{ background:var(--code-bg); border:1px solid var(--rule); border-radius:5px;
       padding:14px 16px; overflow-x:auto; font-size:.8rem; line-height:1.6; margin:0; }}
pre code {{ background:none; padding:0; }}
.tw {{ overflow-x:auto; }}
table {{ width:100%; border-collapse:collapse; font-size:.86rem; }}
th {{ text-align:left; font-size:.68rem; letter-spacing:.1em; text-transform:uppercase;
      color:var(--muted); font-weight:600; padding:0 14px 8px 0;
      border-bottom:1px solid var(--rule); white-space:nowrap; }}
td {{ padding:10px 14px 10px 0; border-bottom:1px solid var(--rule);
      vertical-align:top; }}
td.k {{ white-space:nowrap; }} td.t {{ color:var(--muted); font-size:.78rem; white-space:nowrap; }}
td.d {{ color:var(--muted); }}
.req {{ color:var(--danger); font-size:.62rem; letter-spacing:.08em; margin-left:.5em;
        vertical-align:middle; }}
.tokens {{ list-style:none; padding:0; margin:0; display:flex; flex-wrap:wrap; gap:8px; }}
.tokens code {{ background:var(--accent-soft); border:1px solid var(--rule); }}
.muted {{ color:var(--muted); font-weight:400; text-transform:none; letter-spacing:0; }}
footer {{ grid-column:1/-1; border-top:1px solid var(--rule); padding-top:24px;
          color:var(--muted); font-size:.8rem; }}
a {{ color:var(--accent); }}
</style>

<div class="wrap">
<header>
  <div>
    <span class="tag">Trailstamp · 第一階段便條 API</span>
    <h1>Notes API 契約<span class="v">v{e(info['version'])}</span></h1>
  </div>
  <div class="base">{e(base)}</div>
</header>

<nav>
  <span class="tag">Endpoints</span>
  {nav}
  <span class="tag" style="margin-top:20px">Shapes</span>
  <a href="#shapes"><code>資料形狀</code></a>
  <a href="#errors"><code>錯誤 token</code></a>
</nav>

<main>
<section id="start">
  <h2>開始之前</h2>
  <p>所有業務 endpoint 都是 <code>POST</code> 到 PostgREST 的 RPC，body 為 JSON，
     <strong>鍵名完全等於參數名</strong>。每個請求帶 <code>apikey</code>；除 signup 外另帶
     <code>Authorization: Bearer &lt;access_token&gt;</code>。</p>
  <h4>取得 session（匿名登入）</h4>
  <pre><code>{e(signup)}</code></pre>
  <p class="note">⚠️ 只在沒有既存 session 時呼叫——重複呼叫會鑄出新的 user id，舊資料成為孤兒。</p>
</section>

{"".join(endpoint_block(p, o, spec) for p, o in rpcs)}

<section id="shapes">
  <h2>資料形狀</h2>
  {shapes}
</section>

<section id="errors">
  <h2>錯誤 token</h2>
  <p>業務錯誤一律 HTTP 400 ＋ <code>code == "P0001"</code>，<code>message</code> 是凍結的
     機器碼，是唯一的判斷依據。其他一切（401、<code>PGRST3xx</code>、<code>42501</code>、
     網路錯誤）不屬本契約，走 session 刷新／通用重試，<strong>不得</strong>比對 message 字串。</p>
  <ul class="tokens">{token_rows}</ul>
  <p class="note">token 字串永久凍結：新增為非破壞性變更（未知 token 走通用錯誤路徑即可），
     改名或刪除為破壞性變更並須取得 iOS 簽核。</p>
</section>
</main>

<footer>
  由 <code>docs/api/openapi.yaml</code> 自動產生（<code>docs/api/build-doc.py</code>）——
  請勿手改本頁，契約有變更時重新產生即可。<br>
  語意規則與 curl 範例的完整版見 repo 的 <code>docs/api/notes.md</code>；
  <code>color</code>／<code>style</code> 對照表見 <code>docs/api/style-codes.md</code>（由 iOS 維護）。
</footer>
</div>
"""


if __name__ == "__main__":
    spec = load_spec()
    OUT.write_text(build(spec), encoding="utf-8")
    print(f"已產生 {OUT}（規格版本 {spec['info']['version']}，"
          f"{len([p for p in spec['paths'] if '/rpc/' in p])} 支 RPC）")
