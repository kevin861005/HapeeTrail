#!/usr/bin/env python3
"""三份契約產出的交叉比對：token 清單、狀態碼、鍵名，三方逐字一致才過。

三份產出是 openapi.yaml（wire format 的權威）、notes.md（語意）、
postman/（可執行範例）。notes.md 開頭寫著「任何介面變更三份必須同步更新」——
這支就是那句話的可執行版本：人會忘記改其中一份，`diff` 不會。

用法：
    docs/api/check-contract.py          # 全綠 exit 0，任何一項不一致 exit 1

需要 PyYAML（`pip install pyyaml`）。刻意不走（已移除的 build-doc.py 曾用的）
`npx redocly bundle`：這支只讀 schema 與 description，`$ref` 只有兩個且都在
components/responses，自己解比多開一個 node 行程便宜。

刻意只比對**凍結**的東西（token 字串、token→狀態碼、資料形狀的鍵名）。
描述文字、範例值、順序都不比——那些是非破壞性變更，凍結它們只會讓這支變成阻力。
"""
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent
SPEC = yaml.safe_load((ROOT / "openapi.yaml").read_text(encoding="utf-8"))
NOTES = (ROOT / "notes.md").read_text(encoding="utf-8")
COLLECTION = json.loads((ROOT / "postman" / "hapeetrail.postman_collection.json").read_text(encoding="utf-8"))

fails: list[str] = []


def check(label: str, got, want) -> None:
    if got == want:
        print(f"  ✅ {label}")
    else:
        print(f"  ❌ {label}\n     預期：{want}\n     實得：{got}")
        fails.append(label)


# ── token → 狀態碼 ────────────────────────────────────────────────────────────
# openapi：token 只列在 Problem.code 的 enum 裡，狀態碼則散在各 response 的
# description（`token` 以反引號標示）。把兩者接起來就是權威的對照表。
TOKENS = set(SPEC["components"]["schemas"]["Problem"]["properties"]["code"]["enum"])

spec_status: dict[str, set[str]] = {t: set() for t in TOKENS}
SHARED = SPEC["components"]["responses"]
for path in SPEC["paths"].values():
    for method in path.values():
        if not isinstance(method, dict):
            continue  # path 層的 servers 之類
        for status, response in method.get("responses", {}).items():
            # 401 與兩支列表的 400 走 components/responses 的共用定義，要跟著 $ref 走
            if "$ref" in response:
                response = SHARED[response["$ref"].rsplit("/", 1)[1]]
            for token in re.findall(r"`([a-z_]+)`", response.get("description", "")):
                if token in TOKENS:
                    spec_status[token].add(status)

# notes.md §8 的表：| `token` | HTTP | … |（一格可能有兩個 token，如 content_empty / content_too_long）
notes_status: dict[str, set[str]] = {}
for cell, status in re.findall(r"^\|\s*(`[a-z_]+`(?:\s*/\s*`[a-z_]+`)*)\s*\|\s*(\d{3})\s*\|", NOTES, re.M):
    for token in re.findall(r"`([a-z_]+)`", cell):
        notes_status.setdefault(token, set()).add(status)

print("① token 清單（openapi enum ↔ notes.md §8 錯誤碼表）")
check("token 集合逐字一致", sorted(notes_status), sorted(TOKENS))
print("② token → 狀態碼")
check("對照表逐項一致", {k: sorted(v) for k, v in sorted(notes_status.items())},
      {k: sorted(v) for k, v in sorted(spec_status.items())})

# ── 鍵名 ─────────────────────────────────────────────────────────────────────
# notes.md 的形狀寫成 JSON 範例；把 `…`／`/* Note */` 這些給人看的省略記號換掉才 parse 得動。
def notes_json_keys(anchor: str) -> list[str]:
    block = re.search(rf"{anchor}.*?```json\n(.*?)```", NOTES, re.S).group(1)
    block = re.sub(r"/\*.*?\*/", '""', block)         # [ /* Note */ ] → [""]
    block = re.sub(r'"[^"]*…[^"]*"', '""', block)      # "5f8f1c1e-…" → ""
    return sorted(json.loads(block))


def spec_keys(schema: str) -> list[str]:
    return sorted(SPEC["components"]["schemas"][schema]["properties"])


print("③ 資料形狀的鍵名（openapi schema ↔ notes.md JSON 範例）")
for schema, anchor in [("Note", r"\*\*Note\*\*"),
                       ("NearbyHint", r"\*\*NearbyHint\*\*"),
                       ("NotePage", r"兩支都回傳 envelope：")]:
    check(f"{schema} 鍵名", notes_json_keys(anchor), spec_keys(schema))

# ── postman collection ───────────────────────────────────────────────────────
# 它只走 happy path ＋ 四個錯誤示範，本來就是子集；要驗的是「有斷言到的那些，
# 狀態碼與 token 的配對和權威一致」——配錯了它會綠得很有說服力。
print("④ postman collection 的斷言（子集，但配對必須與 openapi 一致）")
collection_pairs: dict[str, set[str]] = {}


def walk(items):
    for item in items:
        if "item" in item:
            walk(item["item"])
            continue
        script = "\n".join("\n".join(e["script"]["exec"]) for e in item.get("event", [])
                           if e["listen"] == "test")
        statuses = re.findall(r"to\.have\.status\((\d{3})\)", script)
        # 刻意不在這裡濾掉 enum 外的 token——濾掉的話下面那條檢查就恆為真了
        tokens = re.findall(r"code[^\n]*?to\.eql\('([a-z_]+)'\)", script)
        for token in tokens:
            collection_pairs.setdefault(token, set()).update(statuses)


walk(COLLECTION["item"])
check("collection 斷言的 token 都在 enum 裡", sorted(set(collection_pairs) - TOKENS), [])
for token, statuses in sorted(collection_pairs.items()):
    if token in TOKENS:   # enum 外的已由上一條報掉，這裡不再 KeyError
        check(f"{token} → {sorted(statuses)}", sorted(statuses), sorted(spec_status[token]))

# collection 的形狀斷言（have.all.keys）也不該與 schema 漂移。
collection_keysets = [sorted(re.findall(r"'([A-Za-z]+)'", m))
                      for m in re.findall(r"have\.all\.keys\(([^)]*)\)",
                                          json.dumps(COLLECTION, ensure_ascii=False))]
for schema in ("Note", "NearbyHint", "NotePage"):
    want = spec_keys(schema)
    check(f"collection 有一組 have.all.keys 等於 {schema}",
          want if want in collection_keysets else collection_keysets, want)

print()
if fails:
    sys.exit(f"❌ {len(fails)} 項不一致：{fails}")
print("✅ 三份契約產出一致")
