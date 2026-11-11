# The sources, request by request

What each extension asks its site for, what it reads from the answer, and what it
never touches. Surveyed and measured on 2026-11-11 with a handful of sequential
requests per site.

## What qualifies a site

A site is in this repository only if all of these hold:

1. A plain HTTP GET returns the stream's address in the clear (in the HTML or its
   embedded page data). No script runs, nothing is decoded but JSON.
2. The stream itself is plain: HLS without `EXT-X-KEY` and with ordinary MPEG-TS
   segments (first byte `0x47`), or a plain MP4. No DRM, no custom segment encryption.
3. **A locked episode comes back with no address.** The site enforces its own
   paywall, so an extension that reads only what is served cannot get past it.
   A site that hands out locked episodes' addresses anyway is not used: playing them
   would be getting past the paywall.
4. Its catalogue and search are plain pages too (no signed or encrypted API calls).

Both sites are Next.js pages-router apps: every page carries its data in
`<script id="__NEXT_DATA__" type="application/json">`, read through
`dramas.core.NextData.pageProps` (the `props.pageProps` object). The `/_next/data/<buildId>/…`
JSON routes would work too, but the build id changes with every deploy, so the
HTML is read.

No special headers are needed by either site or their CDNs. The extensions send
the host's default headers (its User-Agent).

## ReelShort (`https://www.reelshort.com`)

| What | Request | Read from `pageProps` |
| ---- | ------- | --------------------- |
| Popular | `GET /shelf/top-short-movies-dramas-51004673` | `list[]`: `book_id`, `book_title`, `book_pic` |
| Latest | `GET /shelf/new-release-short-movies-dramas-51004672` | the same |
| Search | `GET /search?keywords=<q>&page=<n>` | `books[]` (12 a page), `page`, `totalPage` |
| Details | `GET /movie/<slug>-<book_id>` | `data`: `book_title`, `book_pic`, `special_desc` (HTML → text), `tag_list[]` |
| Episodes | the same page | `data.online_base[]`: `chapter_id`, `serial_number`, `chapter_type`, `publish_at`; `data.is_paid`, `data.paid_start` |
| Video | `GET /episodes/episode-<n>-<slug>-<book_id>-<chapter_id>` | `data.video_url` (null when locked), `data.serial_number` |

- **A shelf is one page.** Page 1 carries the whole shelf (TOP: 200 items, New
  Release: 31). Its `totalPage` (17 for TOP) counts the site's client-side pages:
  `/shelf/…/2` answered the same 200 items in the same order (checked 2026-11-11,
  settling the survey's open question). So popular and latest return everything at
  once with no next page, and never ask for page 2.
- **Slugs are cosmetic.** The server redirects a wrong slug to the canonical one; the
  extension builds the site's own (`Text.slug`: lowercase, every run of other
  characters one `-`), so normally no redirect happens.
- **`tag_list` mixes genres and cast.** Categories `1001` and `1005` are the leads'
  names: they go to the series' artist field, the rest to genres. `update_status`
  is 1 on finished and new series alike, so the status stays unknown.
- **The trailer is not an episode.** `online_base` may start with serial 0,
  `chapter_type` 2 (the trailer); only `chapter_type` 1 is listed.
- **The free rule** (`ReelShortParser.isFree`): `is_paid == 0` means the whole
  series is free; otherwise episode `serial_number < paid_start` is free (the
  sample: `paid_start` 9, episodes 1 to 8). Unknown values count as locked, so the
  list never promises a stream. The episode page has the last word: a locked
  episode's page has no `video_url`, and that is the `LockedEpisodeException`.
- **The stream** is `https://v-mps.crazymaplestudios.com/…-sd.m3u8`: HLS v3, 5 s
  MPEG-TS segments with relative names, no `EXT-X-KEY`, unsigned, behind Cloudflare
  in front of Aliyun OSS. Only an SD rendition exists (`MultiBit: 0`; `-hd` 404s).
  Quality label: `ReelShort SD · HLS`.
- **Never read `start_play.play_info`.** Movie and shelf pages carry it: an
  encrypted base64 blob for the site's own player. The extension has no use for it
  and must not touch it; `scripts/check.sh` and `ReelShortParserTest` hold that line.

## DramaBox (`https://www.dramaboxdb.com`)

| What | Request | Read from `pageProps` |
| ---- | ------- | --------------------- |
| Popular | `GET /channel/trending/<n>` | `moreData.items[]`: `bookId`, `bookName`, `cover`, `bookNameLower`; `pageNo`, `pages` (4) |
| Latest | `GET /genres/0/<n>` | `bookList[]` (12 a page), `pageNo`, `pages` (167); genre 0 is every genre, newest first |
| Search | `GET /search/<n>?searchValue=<q>` | `bookList[]` (20 a page; cover in `coverWap`), `pageNo`, `totalPage` |
| Details | `GET /movie/<bookId>/<bookNameLower>` | `bookInfo`: `bookName`, `cover`, `introduction`, `typeTwoNames`, `tags` |
| Episodes | the same page | `chapterList[]`: `id`, `index` (0-based), `unlock`, `utime` |
| Video | the same page, **fetched again at play time** | the chapter's `mp4` and `m3u8Url` (absent when locked) |

- **One page has everything**: the series page lists every chapter, with stream
  links for the unlocked ones only. Episode names come from `index + 1` (the site's
  own `name` is Chinese, `第一集`).
- **The free rule** is DramaBox's own flag: `unlock == true` (the sample: 8 of 43).
  A locked chapter carries neither `mp4` nor `m3u8Url`; `unlock: false` counts as
  locked even if a link were present.
- **Links expire, so they are read at play time.** Both are CloudFront-signed
  (`Expires=<epoch>&Signature=…&Key-Pair-Id=…`), valid about 24 hours from when the
  page was rendered. So an episode's URL is the site's own episode page
  (`/ep/<bookId>_<slug>/<chapterId>_Episode-<n>`, which "open in browser" lands on),
  never a stream link; playing it fetches the series page again
  (`/movie/<bookId>/<slug>#<chapterId>`: the fragment names the chapter and is never
  sent) and reads that chapter's links from the fresh page. A link whose `Expires`
  is less than 60 s away is refused with "DramaBox served a link that has already
  expired; try again later", rather than handed to a player that would fail on it.
- **The streams**: a plain MP4 (`ftypisom`, about 7 MB an episode, range requests
  work) on `hwvideoseo.dramaboxdb.com`, listed first as `DramaBox 720p · MP4`, and
  720p HLS on `hwzthls.dramaboxdb.com` (no `EXT-X-KEY`, relative segments, TS sync
  byte `0x47`) as `DramaBox 720p · HLS`.

## Not here, and why

From the 2026-11-11 survey (and tanpen's earlier probe of DramaTip):

| Site | Why not |
| ---- | ------- |
| DramaTip | The stream is only in an encrypted `enc` blob the player decrypts |
| ShortTV / ShortMax | Playlist is plain, but each segment is custom-encrypted (a `shortmax0000…` header, then ciphertext) |
| NetShort | Streams are plain; search and paging go through an API whose requests are RSA-encrypted |
| FlexTV | Streams are plain; catalogue beyond a few SSR pages and search need a request-signing scheme |
| GoodShort | Streams are plain; `/search/<kw>` is an SEO tag page, not a search |
| MoboReels | Unsure: plain HLS with a `Referer`, but the site rate-limited the survey before episode lists and search were confirmed |
| ReelAll, ReelRee | Cloudflare challenge (403) |
| any-drama.com, DramaWave, Kalos TV | Unreachable, lapsed, or rendered only by script |
