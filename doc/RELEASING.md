# Releasing

Releases are built and published from a workstation with `scripts/release.sh`.
GitHub Actions are not used (they may be metered or refused for this account, and
the key never leaves the workstation).

## The procedure

1. Bump `versionCode` in each changed extension's `extension.json`. The version
   name follows (`14.<versionCode>`); an extension whose code did not change keeps
   the APK already published, because release builds are not byte-identical and an
   installed APK must not change under an unchanged version.
2. Commit on `main` (the hook runs `scripts/check.sh`) and push.
3. Run `scripts/release.sh`. It refuses unless the tree is clean and `main` equals
   `origin/main`, so the `repo` branch can always be rebuilt from published source. It:
   - reads the key's passwords (`doc/SIGNING.md`);
   - runs `clean :core:test testDebugUnitTest assembleRelease` through `scripts/gradle.sh`;
   - checks every release APK with `apksigner`: signed with v2, by the certificate
     whose SHA-256 is `repo-meta.json`'s `signingKeyFingerprint`;
   - checks out `repo` in a temporary worktree (an orphan branch the first time),
     runs `scripts/make-index.py`, commits (the hook checks the index) and pushes.

`scripts/release.sh --dry-run` does everything but commit and push, and leaves the
branch's would-be contents in a folder it names.

Then check the release from outside: `doc/TESTING.md`, "End to end".

## The `repo` branch

Written only by `make-index.py`. Its address, for tsundoku and Aniyomi:

```
https://raw.githubusercontent.com/masshirodev/dramas-extensions/repo/index.min.json
```

tsundoku takes the folder from whatever file of it is pasted, then reads
`repo.json` (required: the key), `index.json`, and `index.min.json` only if
`index.json` fails. Aniyomi reads `repo.json` and `index.min.json`.

| File | What |
| ---- | ---- |
| `repo.json` | `{"meta": {name, shortName, website, signingKeyFingerprint}}` |
| `index.json` | v2 JSON, as Keiyoushi and Yuzono publish it: `name`, `badgeLabel`, `signingKey` (must equal repo.json's), `contact`, `extensionList.extensions[]` with `name`, `packageName`, `resources.apkUrl`/`iconUrl` (absolute), `extensionLib`, `versionCode` (string), `versionName`, `contentWarning`, `sources[]` (`id`, `name`, `language`, `homeUrl`) |
| `index.min.json` | Aniyomi's list: `name` ("Aniyomi: ReelShort"), `pkg`, `apk`, `lang`, `code`, `version`, `nsfw`, `torrent`, `sources[]` (`name`, `lang`, `id`, `baseUrl`); files relative, under `apk/` and `icon/` |
| `apk/aniyomi-<lang>.<name>-v14.<code>.apk` | the current version only |
| `icon/<package>.png` | 512 px |
| `README.md` | the address and the fingerprint |

A source's `id` is what Aniyomi's `AnimeHttpSource` derives: the first 8 bytes of
MD5 of `"<name lowercased>/<lang>/1"`, as a positive long (`make-index.py`,
`source_id`; it reproduces Yuzono's published ids). ReelShort is
`6410229240054931522`, DramaBox `5740943650230045274`. The package must contain
`.animeextension.`, which is how tsundoku tells an anime extension from a manga one.

No `index.pb` is published (see `doc/ARCHITECTURE.md`).

## Reusing the tooling

Another repository can build on this one as a git submodule: `scripts/gradle.sh`
builds the project named by `GRADLE_ROOT` instead of this one, and `make-index.py`
reads the repository metadata (name, key fingerprint, served URL) from `REPO_META`
instead of `repo-meta.json`. Both default to this repository.

## When a step refuses

| Message | Meaning |
| ------- | ------- |
| `the working tree is not clean` / `main is not the same as origin/main` | commit and push first |
| `no keystore at …` / `… are not set` | the key is not where `doc/SIGNING.md` says; restore it from the backup, never make a new one casually (a new key breaks every install) |
| `built no signed APK` | Gradle ran without the passwords, and made an unsigned APK |
| `is signed by X, not the repository key Y` | the wrong keystore; see `doc/SIGNING.md` |
| `versionCode N is lower than the published M` | a code only goes up |
