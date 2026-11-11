#!/usr/bin/env python3
"""Prove an extension end to end in a tsundoku-engine, against the real site.

Usage: ENGINE_URL=http://127.0.0.1:4597 ENGINE_TOKEN=... e2e-engine.py <apk> <search term>

Loads the APK into the engine and runs, through its HTTP API, the calls tsundoku
makes: extensionInfo, popular, latest, search, details and episodes of the first
popular series, then the videos of one free episode and of one locked episode.
Then it fetches the free episode's stream directly: the playlist (which must answer
200 and carry no EXT-X-KEY) and the first KB of one segment (MPEG-TS sync byte), or
the first KB of an MP4 (`ftyp`). About ten requests reach the site, one after
another with a pause between: be gentle, run it once per release, not in a loop.

Start a private engine for it (never the live one): doc/TESTING.md, "End to end".
"""
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENGINE = os.environ.get("ENGINE_URL", "http://127.0.0.1:4597").rstrip("/")
TOKEN = os.environ["ENGINE_TOKEN"]
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
PAUSE = 2.0


def call(body: dict, handle: str | None, apk: bytes) -> tuple[object, str]:
    payload = dict(body)
    if handle:
        payload["extensionId"] = handle
    else:
        payload["data"] = base64.b64encode(apk).decode()
    req = urllib.request.Request(
        f"{ENGINE}/dalvik",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json", "User-Agent": UA},
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as res:
            return json.loads(res.read() or b"null"), res.headers.get("X-Mangayomi-Extension-Id") or handle or ""
    except urllib.error.HTTPError as e:
        return {"error": e.read().decode(errors="replace"), "status": e.code}, handle or ""


def fetch(url: str, rng: str | None = None) -> tuple[int, bytes, str]:
    headers = {"User-Agent": UA}
    if rng:
        headers["Range"] = f"bytes={rng}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as res:
        return res.status, res.read(4096 if rng else 2_000_000), res.headers.get("Content-Type", "")


def main(apk_path: str, term: str) -> int:
    apk = open(apk_path, "rb").read()
    ok = True

    def check(cond: bool, what: str) -> None:
        nonlocal ok
        cond = bool(cond)
        ok &= cond
        print(f"  {'PASS' if cond else 'FAIL'}  {what}")

    info, handle = call({"method": "extensionInfo"}, None, apk)
    print(f"extensionInfo: {json.dumps(info)}")
    check(bool(handle) and isinstance(info, dict) and info.get("sources"), f"loaded, handle {handle[:12]}…")
    source = info["sources"][0]
    sid = str(source["id"])

    def anime(method: str, **extra) -> object:
        time.sleep(PAUSE)
        result, _ = call({"method": method, "sourceId": sid, **extra}, handle, apk)
        return result

    pop = anime("getPopularAnime", page=1)
    items = (pop.get("animes") or []) if isinstance(pop, dict) else []
    print(f"popular: {len(items)} items, hasNextPage={pop.get('hasNextPage') if isinstance(pop, dict) else '?'}; first: {items[0]['title'] if items else pop}")
    check(len(items) > 0, "popular lists series")

    lat = anime("getLatestAnime", page=1)
    litems = (lat.get("animes") or []) if isinstance(lat, dict) else []
    print(f"latest: {len(litems)} items; first: {litems[0]['title'] if litems else lat}")
    check(len(litems) > 0, "latest lists series")

    srch = anime("getSearchAnime", page=1, search=term)
    sitems = (srch.get("animes") or []) if isinstance(srch, dict) else []
    print(f"search {term!r}: {len(sitems)} items, hasNextPage={srch.get('hasNextPage') if isinstance(srch, dict) else '?'}; first: {sitems[0]['title'] if sitems else srch}")
    check(len(sitems) > 0, "search finds series")

    series = items[0]
    det = anime("getDetailsAnime", animeData={"url": series["url"], "title": series["title"]})
    print(f"details of {series['url']}: {json.dumps({k: (v[:80] + '…' if isinstance(v, str) and len(v) > 80 else v) for k, v in det.items()}, ensure_ascii=False) if isinstance(det, dict) else det}")
    check(isinstance(det, dict) and det.get("title") and det.get("description"), "details carry a title and a synopsis")

    eps = anime("getEpisodeList", animeData={"url": series["url"], "title": series["title"]})
    eps = eps if isinstance(eps, list) else []
    locked = [e for e in eps if e["name"].endswith("· LOCKED")]
    free = [e for e in eps if not e["name"].endswith("· LOCKED")]
    print(f"episodes: {len(eps)} ({len(free)} free, {len(locked)} locked); newest first: {[e['name'] for e in eps[:2]]} … {[e['name'] for e in eps[-2:]]}")
    check(len(eps) > 0 and eps[0]["episode_number"] >= eps[-1]["episode_number"], "episodes listed, newest first")
    check(len(free) > 0, "at least one free episode")

    first_free = min(free, key=lambda e: e["episode_number"])
    vids = anime("getVideoList", episodeData={"url": first_free["url"], "name": first_free["name"]})
    vids = vids if isinstance(vids, list) else []
    print(f"videos of {first_free['name']}: {[(v.get('quality'), v.get('url', '')[:90]) for v in vids]}")
    check(len(vids) > 0, "a free episode has videos")

    if locked:
        lk = min(locked, key=lambda e: e["episode_number"])
        res = anime("getVideoList", episodeData={"url": lk["url"], "name": lk["name"]})
        print(f"videos of {lk['name']}: {res}")
        check(isinstance(res, dict) and "locked" in json.dumps(res).lower(), "a locked episode answers that it is locked, with no video")
    else:
        print("  NOTE  this series has no locked episode; the locked path was not exercised")

    for v in vids:
        url = v.get("url") or v.get("videoUrl")
        time.sleep(PAUSE)
        if ".m3u8" in url:
            status, body, ctype = fetch(url)
            text = body.decode(errors="replace")
            print(f"playlist {v['quality']}: HTTP {status}, {ctype}, {len(text.splitlines())} lines")
            check(status == 200 and text.startswith("#EXTM3U"), "the playlist answers 200")
            check("#EXT-X-KEY" not in text, "the playlist has no EXT-X-KEY")
            seg = next((l for l in text.splitlines() if l and not l.startswith("#")), None)
            if seg:
                time.sleep(PAUSE)
                status, head, ctype = fetch(urllib.parse.urljoin(url, seg), "0-1023")
                print(f"segment {seg[:60]}: HTTP {status}, {ctype}, first bytes {head[:4].hex()}, sync at 188: {head[188:189].hex()}")
                check(status in (200, 206) and head[:1] == b"\x47" and head[188:189] == b"\x47", "the segment is plain MPEG-TS")
        else:
            status, head, ctype = fetch(url, "0-1023")
            print(f"mp4 {v['quality']}: HTTP {status}, {ctype}, bytes 4-8 {head[4:8]!r}")
            check(status in (200, 206) and head[4:8] == b"ftyp", "the MP4 answers, a plain MP4")
        expires = re.search(r"[?&]Expires=(\d+)", url)
        if expires:
            print(f"  link expires {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime(int(expires.group(1))))} (resolved just now)")

    print("ALL PASS" if ok else "SOME CHECKS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
