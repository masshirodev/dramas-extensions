# Architecture

## What this is

Two Aniyomi extensions, each an Android APK holding one `AnimeHttpSource`:
`eu.kanade.tachiyomi.animeextension.en.reelshort.ReelShort` and
`eu.kanade.tachiyomi.animeextension.en.dramabox.DramaBox`. A host loads the APK,
reads its manifest (`tachiyomi.animeextension` feature, the
`tachiyomi.animeextension.class` meta-data, the lib in `versionName` `14.<code>`),
and calls the source's methods. The hosts in mind:

- **tsundoku**, through tsundoku-engine (a fork of M-Extension-Server). The engine
  converts the APK's dex to JVM bytecode with dex2jar and runs it headless; tsundoku
  installs only an APK whose v2 signature verifies against the key its repository's
  `repo.json` lists (tsundoku's `src/lib/server/engine/apk-signature.ts`).
- **Aniyomi** on Android, which loads the same APK natively.

## One extension per site, not one with two sources

Chosen 2026-11-11:

- **The sites change independently.** A fix to DramaBox should not make every
  ReelShort user update, and a broken release of one should not take the other
  down. Separate APKs have separate versions and separate update prompts.
- **It is what both apps expect.** Aniyomi's repositories ship one site per
  extension; a factory is for one site's languages or mirrors. tsundoku lists
  and hides by package, so two packages give two independent rows.
- **What they share is small** (reading `__NEXT_DATA__`, lenient JSON readers, an
  HTML-to-text helper, the two exceptions) and lives in `core/`, a plain JVM module
  compiled into each APK. It has no Android or Aniyomi dependency, so its tests run
  on the JVM alone.

## Why extensions-lib 14

Aniyomi has two live extension APIs: lib 14 (one `getVideoList(episode)`) and
lib 16 (hosters, then videos per hoster). tsundoku-engine runs both: lib 16 by its
hoster path, lib 14 by `getVideoList` (KickAssAnime 14.63 is the lib 14 extension
its spike measured). Neither site has hosters: an episode has one or two direct
streams. Lib 14 is the smaller surface for the same result, `com.github.aniyomiorg:extensions-lib:14`
is the published artifact (JitPack), and Aniyomi still loads lib 14. If a future
host drops lib 14, moving is one class per site.

## Building against what the host provides

Everything the host supplies is `compileOnly`: extensions-lib, the Kotlin standard
library (`kotlin.stdlib.default.dependency=false`), kotlinx-serialization-json,
jsoup, OkHttp and RxJava. Versions are pinned at or below what the hosts ship
(tsundoku-engine: serialization 1.9.0, jsoup 1.21.2, OkHttp 5.3.2), and Kotlin is
compiled with `apiVersion` 2.0 so nothing calls a newer standard library than the
host may have. JSON is read as a tree (`JsonObject`), with no generated serializers:
a field that changes type reads as absent rather than throwing inside a class the
host has to load. The APK is not minified (R8 renames overrides a host matches by
name, a known trap with lib 14's video methods); it is about 80 KB anyway.

`java.time` is avoided (Android before API 26 lacks it); dates use `SimpleDateFormat`.

## Parsers and sources

Each extension is two files:

- `<Name>Parser.kt`: pure functions, HTML in, plain data classes out. Every rule
  (the free rule, locked naming, slugs, paging, expiry) is here, and here is what
  the unit tests cover against saved fixtures.
- `<Name>.kt`: the `AnimeHttpSource`. It builds requests and maps the parser's
  values onto `SAnime`, `SEpisode` and `Video`. Those models are stubs off the
  device (their constructors throw "Stub!"), so the source classes are proven
  end to end in a real engine (`doc/TESTING.md`, "End to end").

Episodes are returned newest first, as Aniyomi expects, with `episode_number` set.

## The locked contract

A locked episode is in the list, named `Episode N · LOCKED`. Its video list throws
`dramas.core.LockedEpisodeException`:

```
Episode 9 is locked on ReelShort. This extension plays only the episodes ReelShort serves for free, and ReelShort sends no stream for this one.
```

Throwing is how a lib 14 source shows a message: Aniyomi shows it as the error,
and tsundoku-engine returns it as `{"error": "...", "code": 500}`. An empty list
would say only "no videos". Which episodes are locked is decided at play time by
the site itself (the episode or series page has no stream), so the list's marking
can never unlock anything.

## The repository

`scripts/release.sh` builds, signs and publishes to the `repo` branch, which holds
`repo.json`, `index.json` (v2 JSON, read by tsundoku first), `index.min.json`
(Aniyomi's), `apk/` and `icon/`. Format and procedure: `doc/RELEASING.md`.
There is no `index.pb` (Mihon's protobuf index); neither tsundoku nor Aniyomi needs
it, and `repo.json` therefore names no `index_v2`.
