# Testing

## Unit tests (every commit)

```sh
scripts/check.sh                                       # what the pre-commit hook runs
scripts/gradle.sh :core:test testDebugUnitTest         # just the tests
scripts/gradle.sh :src:en:reelshort:testDebugUnitTest  # one extension
```

| Suite | Covers |
| ----- | ------ |
| `core/…/CoreTest` | `__NEXT_DATA__` extraction and its failures, the locked message, the lenient JSON readers, HTML-to-text, slugs |
| `ReelShortParserTest` | shelves (one page, no next), search and its paging, details (genres apart from cast), the episode list against a paid and a free series (trailer left out, newest first), **the free rule**, a free and a locked episode page, **nothing read from `play_info`**, quality labels, a changed site |
| `DramaBoxParserTest` | channel, genre and search pages and their paging, an empty search, details, episodes by `unlock`, **no stream link in the episode list**, **links read from the page fetched at play time**, locked chapters, **an expired link refused**, episode-path round trip, a changed site |
| `ExtensionInfoTest` (each) | the source's name, lang and base URL equal its `extension.json`, and the manifest's class exists |

The parsers are pure, so they are what is unit-tested. The source classes map the
parsers' values onto Aniyomi's models, which are stubs off the device (their
constructors throw), so they are proven in an engine instead (below).

## Fixtures

`src/<lang>/<name>/src/test/resources/fixtures/` holds pages saved from the sites on
2026-11-11 and trimmed by `scripts/make-fixtures.py`: only the `__NEXT_DATA__` is
kept, long lists are cut to a few items, the parts no parser reads are dropped, and
ReelShort's `play_info` is replaced by the marker `REDACTED-NEVER-READ` (a test
checks no output carries it). That keeps a few KB of each site's data in a public
repository instead of whole pages.

To refresh one, save the page with one plain request and trim it:

```sh
curl -sL -A '<a browser user agent>' -o /tmp/movie.html 'https://www.dramaboxdb.com/movie/42000028173'
python3 -I scripts/make-fixtures.py dramabox movie /tmp/movie.html \
  src/en/dramabox/src/test/resources/fixtures/movie.html
```

Kinds: ReelShort `shelf`, `search`, `movie`, `episode`; DramaBox `channel`,
`genre`, `search`, `movie`. A refreshed fixture changes the expected values in its
tests; update them deliberately, never to make a failure go away.

## End to end (each release)

`scripts/e2e-engine.py` loads an APK into a **private** tsundoku-engine and runs
tsundoku's calls against the real site: extensionInfo, popular, latest, search,
details and episodes of the first popular series, the videos of a free and of a
locked episode, then the free stream itself (the playlist answers 200 with no
`EXT-X-KEY`, a segment's first KB starts with the TS sync byte, or an MP4's with
`ftyp`). About ten requests per site, two seconds apart.

Never point it at the live engine. Start one of your own, from the jar
tsundoku-engine builds (`server/build/MExtensionServer-*.jar`), in a container so
no JVM runs on the host, on a free port, with its data on scratch disk:

```sh
D=/mnt/hdd2/scratch/dramas-ext/engine-proof   # any scratch folder
mkdir -p $D/apks $D/prefs $D/app
cp ~/projects/tsundoku-engine/server/build/MExtensionServer-*.jar $D/engine.jar
TOKEN=$(openssl rand -hex 24)
docker run -d --rm --name dramas-proof-engine --user "$(id -u):$(id -g)" -p 127.0.0.1:4597:8080 \
  -e ENGINE_TOKEN=$TOKEN -e ENGINE_APK_DIR=/data/apks -e JAVA_TOOL_OPTIONS=-Djava.util.prefs.userRoot=/data/prefs \
  -v $D:/data -w /data eclipse-temurin:21-jre java -jar /data/engine.jar 8080 /data/app

ENGINE_URL=http://127.0.0.1:4597 ENGINE_TOKEN=$TOKEN \
  python3 -I scripts/e2e-engine.py src/en/reelshort/build/outputs/apk/release/aniyomi-en.reelshort-v14.1-release.apk ceo

docker stop dramas-proof-engine
```

Any JDK 21 image will do; on the workstation, tsundoku-engine's `tsundoku-engine-test`
image is already there.

## The repository, as tsundoku reads it

After a release, check the published `repo` branch with tsundoku's own code: its
`previewRepo` (reads `repo.json`, `index.json`, else `index.min.json`) and
`signedBy` (APK Signature Scheme v2 against the repository's fingerprint), run
against the live URL. From a tsundoku checkout, with Node 24:

```sh
cd ~/projects/tsundoku
mise exec -- node --experimental-strip-types <script>
```

where the script imports `src/lib/server/engine/repo-index.ts` and
`apk-signature.ts`, previews
`https://raw.githubusercontent.com/masshirodev/dramas-extensions/repo/index.min.json`,
downloads each listed APK and checks `signedBy(apk, fingerprint)`. It touches no
database. The 2026-11-11 run is recorded on card c15 ("Build and publish the ReelShort and
DramaBox extensions (14.1)") of the dramas-extensions board.
