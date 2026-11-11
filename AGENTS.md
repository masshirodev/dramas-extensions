# AGENTS.md

Aniyomi extensions (Android APKs) for two short-drama sites, ReelShort and
DramaBox, published as a public extension repository on the `repo` branch. Built
for tsundoku, which runs Aniyomi APKs headless through tsundoku-engine; usable in
Aniyomi too. `CLAUDE.md` is a link to this file. Read `AGENTS.local.md` too when it
exists: it says what is true of one machine's checkout (gitignored).

## The rules (the owner's; not negotiable)

- **Free episodes only, by what the site plainly serves.** No decrypting, no getting
  around encryption, DRM, paywalls or logins, no running the sites' JavaScript.
  A locked episode is listed as `Episode N · LOCKED`, and its video list throws
  `LockedEpisodeException` (the clear message) instead of returning anything.
  It never tries another way.
- **ReelShort's `start_play.play_info` is an encrypted blob: never read it.** The
  plain `video_url` of the episode page is the only stream source. `scripts/check.sh`
  fails on the string `"play_info"` in code, and a test proves no parser output
  contains it.
- **DramaBox's stream links are signed and expire (~24 h).** Never store or cache
  one: the episode list carries none, and the series page is fetched again at play
  time. A link already expired is refused, not handed to the player.
- **Be gentle with the sites.** Sequential requests, a handful at a time. Develop
  against the fixtures (`doc/TESTING.md`), not against the live sites.
- **Never write the owner's legal name anywhere.** Author, copyright, contact and
  git identity are `masshirodev` (https://github.com/masshirodev), email
  `masshiro.iro.kk@gmail.com`. No `Co-Authored-By` or any co-author line in commits.
- **Hooks must pass.** Never `--no-verify` or any skip flag.
- **No JVM left behind, and nothing killed by name.** Build through
  `scripts/gradle.sh`: no daemon, Kotlin in-process, its own session, and whatever
  it leaves running is killed by pid. Never `pkill java`: other JVMs on the box
  (tsundoku's live engine) are not ours. Not `./gradlew --stop` either: it stops
  every daemon of that Gradle version, other builds' included.
- Push to `main`; the built repository goes to `repo` only through `scripts/release.sh`.

## Layout

```
core/                    shared JVM code compiled into each APK (NextData, Json, Text, exceptions)
src/<lang>/<name>/       one extension per folder, one APK
  extension.json         name, package, class, lang, baseUrl, lib, versionCode: the one source
  src/main/kotlin/...    <Name>.kt (the AnimeHttpSource) and <Name>Parser.kt (pure parsing)
  src/test/...           parser tests against src/test/resources/fixtures/
  icon/                  the 512 px icon the repository serves
buildSrc/                the `dramas.extension` convention plugin (manifest, version, signing)
common/AndroidManifest.xml
scripts/                 gradle.sh, check.sh, release.sh, make-index.py, make-fixtures.py,
                         make-icons.sh, install-hooks.sh, hooks/pre-commit
repo-meta.json           the repository's name, website, key fingerprint and branch URL
```

Why two extensions instead of one with two sources, and why extensions-lib 14:
`doc/ARCHITECTURE.md`.

## Build and test

JDK 17 (SDKMAN `17.0.18-amzn` by default; `DRAMAS_JAVA_HOME` overrides), Android SDK
with platform 34 and build-tools 36.1.0, `local.properties` with `sdk.dir`.

```sh
scripts/install-hooks.sh                 # once per clone
scripts/check.sh                         # guards + :core:test testDebugUnitTest assembleDebug
scripts/gradle.sh :src:en:dramabox:testDebugUnitTest   # one module's tests
```

Every parser is pure (HTML in, values out) and tested against saved fixtures; the
source classes only map those values onto Aniyomi's models, which are stubs off the
device, so they are proven end to end in an engine instead (`doc/TESTING.md`).

## Changing an extension

1. Change the parser and its tests first; refresh a fixture only with
   `scripts/make-fixtures.py` (`doc/TESTING.md`).
2. Bump `versionCode` in its `extension.json` for any change that should reach users.
   The release keeps the published APK for an unchanged code.
3. Update `doc/SOURCES.md` when what is read, or from where, changes.

## Release

`scripts/release.sh` (`doc/RELEASING.md`): clean main in sync with origin, the key
from outside the repository (`doc/SIGNING.md`), tests, signed release APKs checked
for v2 and the repository's fingerprint, then `make-index.py` writes the `repo`
branch and it is pushed. GitHub Actions are not used.

## Adding a site

Only a site that passes the bar in `doc/SOURCES.md` ("What qualifies a site"): the
stream in the clear in a plain response, no key or custom encryption, and locked
episodes served with no address. Add `src/<lang>/<name>/` with its
`extension.json`, a pure parser with fixture tests, and a section in
`doc/SOURCES.md`.
