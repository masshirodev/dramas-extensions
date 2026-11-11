#!/usr/bin/env python3
"""Turn pages saved from the sites into the small test fixtures under src/*/*/src/test/resources.

A fixture keeps only the page's __NEXT_DATA__ (the one thing the extensions read),
with long lists cut down and the parts no parser reads dropped, so the public
repository carries a few kilobytes of each site's data instead of its whole page.
ReelShort's `start_play.play_info` (an encrypted blob the extension must never touch,
doc/SOURCES.md) is replaced by a marker, so a test can prove nothing reads it.

Usage: make-fixtures.py <site> <kind> <saved-page.html> <out.html>
  site: reelshort | dramabox; kind: one of the keys of SPECS[site].
Run it with `python3 -I` on pages saved from the sites; it only reads them.
"""
import json
import re
import sys

PLAY_INFO = "REDACTED-NEVER-READ"

# Per kind: lists to cut (dotted path under pageProps -> items kept) and keys to drop.
SPECS = {
    "reelshort": {
        "shelf": {"cut": {"list": 5}, "drop": ["__namespaces"]},
        "search": {"cut": {"books": 4}, "drop": ["__namespaces", "recommend", "categories_list"]},
        "movie": {"cut": {}, "drop": ["__namespaces", "recommendBooks", "newReleaseBooks"]},
        "episode": {"cut": {}, "drop": ["__namespaces", "recommendBooks", "newReleaseBooks", "relatedBooks", "chapterList"]},
    },
    "dramabox": {
        "channel": {"cut": {"moreData.items": 5}, "drop": ["_nextI18Next"]},
        "genre": {"cut": {"bookList": 4, "types": 4}, "drop": ["_nextI18Next"]},
        "search": {"cut": {"bookList": 4}, "drop": ["_nextI18Next", "similarList", "actorList"]},
        "movie": {"cut": {}, "drop": ["_nextI18Next", "recommends", "articleList"]},
    },
}


def redact(node):
    if isinstance(node, dict):
        return {k: (PLAY_INFO if k == "play_info" else redact(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [redact(v) for v in node]
    return node


def main(site, kind, src, out):
    spec = SPECS[site][kind]
    html = open(src, encoding="utf-8").read()
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', html, re.S)
    if not m:
        sys.exit(f"{src}: no __NEXT_DATA__")
    data = json.loads(m.group(1))
    props = data["props"]["pageProps"]
    for key in spec["drop"]:
        props.pop(key, None)
    for path, keep in spec["cut"].items():
        *parents, last = path.split(".")
        node = props
        for p in parents:
            node = node[p]
        node[last] = node[last][:keep]
    slim = {"props": {"pageProps": redact(props)}, "page": data.get("page"), "query": data.get("query", {})}
    text = json.dumps(slim, ensure_ascii=False, indent=1).replace("</", "<\\/")
    with open(out, "w", encoding="utf-8") as f:
        f.write(
            f"<!-- {site} {kind} fixture: __NEXT_DATA__ only, trimmed by scripts/make-fixtures.py -->\n"
            f'<html><body><script id="__NEXT_DATA__" type="application/json">{text}</script></body></html>\n'
        )


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
