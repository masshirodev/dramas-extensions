package eu.kanade.tachiyomi.animeextension.en.dramabox

import dramas.core.NextData
import dramas.core.SiteChangedException
import dramas.core.arr
import dramas.core.bool
import dramas.core.int
import dramas.core.obj
import dramas.core.objects
import dramas.core.str
import dramas.core.strings
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * DramaBox's pages, read from their `__NEXT_DATA__` (doc/SOURCES.md, "DramaBox").
 * Pure: HTML in, plain values out.
 *
 * A series page lists every episode, and carries stream links for the free ones
 * only. Those links are CloudFront-signed and expire about a day after the page was
 * rendered, so an episode never stores one: the series page is fetched again when
 * the episode is played, and [streams] reads the link from that fresh page.
 */
object DramaBoxParser {
    // The source's identity. extension.json says the same (ExtensionInfoTest).
    const val SITE = "DramaBox"
    const val LANG = "en"
    const val BASE_URL = "https://www.dramaboxdb.com"

    data class Book(val bookId: String, val title: String, val cover: String?, val slug: String?) {
        val path get() = moviePath(bookId, slug)
    }

    data class Page(val books: List<Book>, val hasNextPage: Boolean)

    data class Details(
        val bookId: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val genres: List<String>,
        val slug: String?,
    )

    /** An episode as listed: deliberately no stream link (it would expire). */
    data class Episode(
        val number: Int,
        val chapterId: String,
        val free: Boolean,
        /** Epoch milliseconds (the day DramaBox last updated it), or 0. */
        val updatedAt: Long,
        val path: String,
    ) {
        val name get() = if (free) "Episode $number" else "Episode $number · LOCKED"
    }

    data class Stream(val url: String, val quality: String)

    /** What a free episode plays, read from a series page fetched just now. */
    sealed interface Streams {
        data class Free(val streams: List<Stream>) : Streams
        data class Locked(val number: Int?) : Streams
    }

    /** `/channel/<name>/<page>`: trending, must-sees, hidden gems. */
    fun channel(html: String): Page {
        val props = NextData.pageProps(html, SITE)
        val items = props.obj("moreData")?.arr("items")
            ?: throw SiteChangedException("$SITE's channel has no items; the site may have changed")
        return Page(items.objects().mapNotNull { book(it, "cover") }, hasNext(props, "pages"))
    }

    /** `/genres/<typeTwoId>/<page>`; type 0 is every genre, newest first. */
    fun genre(html: String): Page {
        val props = NextData.pageProps(html, SITE)
        val list = props.arr("bookList") ?: throw SiteChangedException("$SITE's genre page has no list; the site may have changed")
        return Page(list.objects().mapNotNull { book(it, "cover") }, hasNext(props, "pages"))
    }

    /** `/search/<page>?searchValue=…`. */
    fun search(html: String): Page {
        val props = NextData.pageProps(html, SITE)
        // An empty result may come without its list; that is no results, not a changed site.
        val list = props.arr("bookList")
        if (list == null && props.bool("isEmpty") != true && props["totalNum"] == null) {
            throw SiteChangedException("$SITE's search has no results list; the site may have changed")
        }
        return Page(list?.objects()?.mapNotNull { book(it, "coverWap") }.orEmpty(), hasNext(props, "totalPage"))
    }

    fun details(html: String): Details {
        val info = bookInfo(html)
        val bookId = info.str("bookId") ?: throw SiteChangedException("$SITE's series page has no bookId")
        return Details(
            bookId = bookId,
            title = info.str("bookName") ?: throw SiteChangedException("$SITE's series page has no title"),
            cover = info.str("cover"),
            description = info.str("introduction")?.trim(),
            genres = (info["typeTwoNames"].strings() + info["tags"].strings()).distinct(),
            slug = info.str("bookNameLower"),
        )
    }

    /** Every episode, newest first, the locked ones marked by DramaBox's own `unlock`. */
    fun episodes(html: String): List<Episode> {
        val props = NextData.pageProps(html, SITE)
        val info = props.obj("bookInfo") ?: throw SiteChangedException("$SITE's series page has no bookInfo")
        val bookId = info.str("bookId") ?: throw SiteChangedException("$SITE's series page has no bookId")
        val slug = info.str("bookNameLower")
        val chapters = props.arr("chapterList") ?: throw SiteChangedException("$SITE's series page has no episode list")
        return chapters.objects()
            .mapNotNull { c ->
                val chapterId = c.str("id") ?: return@mapNotNull null
                val number = (c.int("index") ?: return@mapNotNull null) + 1
                Episode(
                    number = number,
                    chapterId = chapterId,
                    free = c.bool("unlock") == true,
                    updatedAt = c.str("utime")?.let(::dayToMillis) ?: 0L,
                    path = episodePath(bookId, slug, chapterId, number),
                )
            }
            .sortedByDescending { it.number }
    }

    /**
     * The streams of [chapterId] on a series page fetched at play time. A chapter
     * DramaBox locks carries no link, and that is the answer: [Streams.Locked].
     * A link that has already expired (a stale cached page) is refused rather
     * than handed to the player.
     */
    fun streams(html: String, chapterId: String, nowEpochSeconds: Long): Streams {
        val chapters = NextData.pageProps(html, SITE).arr("chapterList")
            ?: throw SiteChangedException("$SITE's series page has no episode list")
        val chapter = chapters.objects().firstOrNull { it.str("id") == chapterId }
            ?: throw SiteChangedException("$SITE no longer lists this episode ($chapterId)")
        val number = chapter.int("index")?.plus(1)
        val mp4 = chapter.str("mp4")
        val hls = chapter.str("m3u8Url")
        if (chapter.bool("unlock") != true || (mp4 == null && hls == null)) return Streams.Locked(number)

        val links = listOfNotNull(mp4?.let { it to "MP4" }, hls?.let { it to "HLS" })
        links.forEach { (url, _) ->
            val expires = expiresOf(url)
            if (expires != null && expires <= nowEpochSeconds + EXPIRY_MARGIN_SECONDS) {
                throw SiteChangedException("$SITE served a link that has already expired; try again later")
            }
        }
        return Streams.Free(links.map { (url, format) -> Stream(url, listOfNotNull(resolutionOf(url), format).joinToString(" · ")) })
    }

    /** A link must outlive the start of playback by at least this much. */
    const val EXPIRY_MARGIN_SECONDS = 60L

    /** CloudFront's signed-URL `Expires` (epoch seconds), when the link has one. */
    fun expiresOf(url: String): Long? = Regex("[?&]Expires=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    fun resolutionOf(url: String): String? = Regex("\\.(\\d{3,4}p)\\.").find(url)?.groupValues?.get(1)

    fun moviePath(bookId: String, slug: String?) =
        if (slug.isNullOrBlank()) "/movie/$bookId" else "/movie/$bookId/$slug"

    /** The site's own episode page, so "open in browser" lands on it. */
    fun episodePath(bookId: String, slug: String?, chapterId: String, number: Int) =
        "/ep/${bookId}_${slug.orEmpty()}/${chapterId}_Episode-$number"

    data class EpisodeRef(val bookId: String, val slug: String?, val chapterId: String)

    /** Undoes [episodePath]: which series page to fetch at play time, and which chapter to read. */
    fun parseEpisodePath(path: String): EpisodeRef {
        val m = Regex("/ep/(\\d+)_([^/]*)/(\\d+)_").find(path)
            ?: throw IllegalArgumentException("Not a $SITE episode: $path")
        return EpisodeRef(m.groupValues[1], m.groupValues[2].ifBlank { null }, m.groupValues[3])
    }

    private fun bookInfo(html: String): JsonObject =
        NextData.pageProps(html, SITE).obj("bookInfo")
            ?: throw SiteChangedException("$SITE's series page has no bookInfo; the site may have changed")

    private fun hasNext(props: JsonObject, lastKey: String): Boolean {
        val page = props.int("pageNo") ?: return false
        val last = props.int(lastKey) ?: return false
        return page < last
    }

    private fun book(o: JsonObject, coverKey: String): Book? {
        val id = o.str("bookId") ?: return null
        val title = o.str("bookName") ?: return null
        return Book(id, title, o.str(coverKey) ?: o.str("cover"), o.str("bookNameLower"))
    }

    // SimpleDateFormat, not java.time: Android before API 26 has no java.time.
    private fun dayToMillis(day: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(day)?.time
    }.getOrNull()
}
