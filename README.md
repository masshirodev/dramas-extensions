# dramas-extensions

[Aniyomi](https://github.com/aniyomiorg/aniyomi) extensions for vertical short
dramas, made for [tsundoku](https://github.com/masshirodev/tsundoku) and usable in
Aniyomi itself:

| Extension | Site | Popular | Latest | Search | Plays |
| --------- | ---- | ------- | ------ | ------ | ----- |
| ReelShort | reelshort.com | the TOP shelf | the New Release shelf | yes | free episodes, HLS |
| DramaBox | dramaboxdb.com | Trending | every genre, newest first | yes | free episodes, MP4 and HLS (720p) |

**Free episodes only.** Each extension plays what its site plainly serves for free
and nothing else. A locked episode is listed, named `Episode 9 · LOCKED`, and has
no video: asking for one answers that it is locked. Nothing is decrypted, no
paywall, login or DRM is worked around, and no site script is run.
[`doc/SOURCES.md`](doc/SOURCES.md) says exactly what each extension reads.

## Add the repository

Paste this address into tsundoku (BROWSE → EXTENSIONS → REPOSITORIES) or Aniyomi
(More → Settings → Browse → Extension repos):

```
https://raw.githubusercontent.com/masshirodev/dramas-extensions/repo/index.min.json
```

Every APK is signed by the key with this SHA-256 fingerprint, which the
repository's `repo.json` publishes and both apps check:

```
63:16:F4:46:AF:B5:96:E5:4E:E5:00:77:40:70:72:D2:51:0E:30:1F:CC:32:A8:FF:00:95:D6:D1:23:A5:B3:BA
```

## Why these two sites

Short-drama sites were surveyed on 2026-11-11 for one property: a plain HTTP request
returns the stream address in the clear, the stream has no `EXT-X-KEY` or custom
encryption, and a locked episode comes back with no address at all (so the paywall
is the site's, enforced by the site). ReelShort and DramaBox both qualify, and both
render everything an extension needs into the page's `__NEXT_DATA__`. Others were
left out:

- DramaTip, ShortTV/ShortMax: the stream is in an encrypted blob, or its segments
  are encrypted. Out of bounds.
- NetShort, FlexTV: streams are plain, but search and paging go through an API that
  needs encrypted or signed requests. Out of bounds.
- GoodShort: plain streams, but no real free-text search.
- MoboReels: promising, not finished (it rate-limited the survey).
- ReelAll, ReelRee: behind a Cloudflare challenge.

[`doc/ARCHITECTURE.md`](doc/ARCHITECTURE.md) has the longer version.

## Building

JDK 17 and the Android SDK (platform 34, build-tools 36.1.0).

```sh
scripts/install-hooks.sh   # once: the pre-commit hook
scripts/check.sh           # what the hook runs: guards, unit tests, debug build
scripts/release.sh         # build, sign, write and push the repo branch (doc/RELEASING.md)
```

`scripts/gradle.sh <tasks>` runs Gradle without leaving a JVM behind.

## Documentation

- [`doc/ARCHITECTURE.md`](doc/ARCHITECTURE.md): layout, why two extensions, why lib 14
- [`doc/SOURCES.md`](doc/SOURCES.md): how each source works, request by request
- [`doc/TESTING.md`](doc/TESTING.md): the tests, the fixtures, and how to refresh them
- [`doc/RELEASING.md`](doc/RELEASING.md): releasing, and the repo branch's format
- [`doc/SIGNING.md`](doc/SIGNING.md): the signing key and how it is kept

## License

Apache-2.0, copyright masshirodev. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
ReelShort and DramaBox are third-party services; this project is not affiliated
with them.
