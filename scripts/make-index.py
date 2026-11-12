#!/usr/bin/env python3
"""Write the `repo` branch: APKs, icons, repo.json, index.json and index.min.json.

Usage: [REPO_META=<repo-meta.json>] make-index.py <repo-dir> <module-dir>=<signed-apk> [...]

<repo-dir> is a checkout of the `repo` branch (empty on the first release). Each
<module-dir> is an extension (src/<lang>/<name>) and <signed-apk> the release APK
built from it. The index is what Aniyomi and tsundoku read (doc/RELEASING.md):

  repo.json        meta.signingKeyFingerprint: the key every APK must be signed by
  index.min.json   Aniyomi's index (a list), with files under apk/ and icon/
  index.json       the v2 JSON index Keiyoushi and Yuzono publish; tsundoku reads it first

An extension whose versionCode the index already lists keeps the APK published
then: release builds are not byte-for-byte reproducible, and an installed APK
must not change under an unchanged version. A lower versionCode is refused.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAPT2 = os.environ.get("AAPT2") or os.path.expanduser("~/Android/Sdk/build-tools/36.1.0/aapt2")


def source_id(name: str, lang: str, version_id: int = 1) -> str:
    """AnimeHttpSource.generateId: the first 8 bytes of MD5("name/lang/versionId"), as a positive long."""
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    return str(int.from_bytes(digest[:8], "big") & 0x7FFF_FFFF_FFFF_FFFF)


def badging(apk: str) -> dict:
    out = subprocess.run([AAPT2, "dump", "badging", apk], check=True, capture_output=True, text=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    if not m:
        sys.exit(f"{apk}: aapt2 could not read its package")
    return {"pkg": m.group(1), "code": int(m.group(2)), "version": m.group(3)}


def main(repo: str, pairs: list[str]) -> None:
    # REPO_META names another repository's metadata (its name, key and served URL), so a
    # repository built on this one's tooling writes its own index (doc/RELEASING.md).
    meta = json.load(open(os.environ.get("REPO_META") or os.path.join(ROOT, "repo-meta.json")))
    base = meta["repoBranchUrl"]
    os.makedirs(os.path.join(repo, "apk"), exist_ok=True)
    os.makedirs(os.path.join(repo, "icon"), exist_ok=True)

    previous = {}
    old_index = os.path.join(repo, "index.min.json")
    if os.path.exists(old_index):
        previous = {e["pkg"]: e for e in json.load(open(old_index))}

    legacy, v2, keep = [], [], set()
    for pair in pairs:
        module, apk = pair.split("=", 1)
        info = json.load(open(os.path.join(module, "extension.json")))
        b = badging(apk)
        pkg = f"eu.kanade.tachiyomi.animeextension.{info['pkg']}"
        version = f"{info['lib']}.{info['versionCode']}"
        if (b["pkg"], b["code"], b["version"]) != (pkg, info["versionCode"], version):
            sys.exit(f"{apk} is {b}, but {module}/extension.json says {pkg} {info['versionCode']} {version}")

        apk_name = f"aniyomi-{info['pkg']}-v{version}.apk"
        old = previous.get(pkg)
        if old and info["versionCode"] < old["code"]:
            sys.exit(f"{pkg}: versionCode {info['versionCode']} is lower than the published {old['code']}")
        if old and info["versionCode"] == old["code"] and os.path.exists(os.path.join(repo, "apk", old["apk"])):
            print(f"{pkg} {version}: unchanged, keeping the published APK")
            apk_name = old["apk"]
        else:
            shutil.copyfile(apk, os.path.join(repo, "apk", apk_name))
            print(f"{pkg} {version}: published {apk_name}")
        keep.add(apk_name)
        shutil.copyfile(os.path.join(module, "icon", "ic_launcher-512.png"), os.path.join(repo, "icon", f"{pkg}.png"))

        sid = source_id(info["name"], info["lang"])
        legacy.append({
            "name": f"Aniyomi: {info['name']}",
            "pkg": pkg,
            "apk": apk_name,
            "lang": info["lang"],
            "code": info["versionCode"],
            "version": version,
            "nsfw": info["nsfw"],
            "torrent": 0,
            "sources": [{"name": info["name"], "lang": info["lang"], "id": sid, "baseUrl": info["baseUrl"]}],
        })
        v2.append({
            "name": info["name"],
            "packageName": pkg,
            "resources": {"apkUrl": f"{base}apk/{apk_name}", "iconUrl": f"{base}icon/{pkg}.png"},
            "extensionLib": info["lib"],
            "versionCode": str(info["versionCode"]),
            "versionName": version,
            "contentWarning": "CONTENT_WARNING_NSFW" if info["nsfw"] else "CONTENT_WARNING_SAFE",
            "sources": [{"id": sid, "name": info["name"], "language": info["lang"], "homeUrl": info["baseUrl"]}],
        })

    # Only the current version of each extension stays on the branch.
    for name in os.listdir(os.path.join(repo, "apk")):
        if name not in keep:
            os.remove(os.path.join(repo, "apk", name))
    live_icons = {f"{e['pkg']}.png" for e in legacy}
    for name in os.listdir(os.path.join(repo, "icon")):
        if name not in live_icons:
            os.remove(os.path.join(repo, "icon", name))

    legacy.sort(key=lambda e: e["pkg"])
    v2.sort(key=lambda e: e["packageName"])
    fingerprint = meta["signingKeyFingerprint"]
    write(repo, "index.min.json", json.dumps(legacy, separators=(",", ":"), ensure_ascii=False))
    write(repo, "index.json", json.dumps({
        "name": meta["name"],
        "badgeLabel": meta["shortName"],
        "signingKey": fingerprint,
        "contact": {"website": meta["website"]},
        "extensionList": {"extensions": v2},
    }, indent=2, ensure_ascii=False))
    write(repo, "repo.json", json.dumps({
        "meta": {
            "name": meta["name"],
            "shortName": meta["shortName"],
            "website": meta["website"],
            "signingKeyFingerprint": fingerprint,
        }
    }, indent=2, ensure_ascii=False))
    write(repo, "README.md", (
        "# dramas-extensions: the built repository\n\n"
        "This branch is written by `scripts/release.sh` on `main`; nothing here is edited by hand.\n\n"
        f"Add it to Aniyomi or tsundoku with:\n\n```\n{base}index.min.json\n```\n\n"
        f"Every APK here is signed by the key whose SHA-256 fingerprint is `{fingerprint}`.\n"
        f"Source, documentation and the rules the extensions follow: {meta['website']}\n"
    ))


def write(repo: str, name: str, text: str) -> None:
    with open(os.path.join(repo, name), "w", encoding="utf-8") as f:
        f.write(text + ("" if text.endswith("\n") else "\n"))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
