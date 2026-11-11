package eu.kanade.tachiyomi.animeextension.en.reelshort

import dramas.core.NextData
import dramas.core.SiteChangedException
import dramas.core.Text
import dramas.core.arr
import dramas.core.int
import dramas.core.long
import dramas.core.obj
import dramas.core.objects
import dramas.core.str
import kotlinx.serialization.json.JsonObject

/**
 * ReelShort's pages, read from their `__NEXT_DATA__` (doc/SOURCES.md, "ReelShort").
 * Pure: HTML in, plain values out, so every rule here is tested against saved pages.
 *
 * Never read `start_play.play_info`: it is an encrypted blob, and the extension plays
 * only the plain `video_url` an episode page serves for a free episode.
 */
object ReelShortParser {
    // The source's identity. extension.json says the same (ExtensionInfoTest), and the
    // source id Aniyomi derives from name and lang is what the repository index lists.
    const val SITE = "ReelShort"
    const val LANG = "en"
    const val BASE_URL = "https://www.reelshort.com"

    data class Book(val bookId: String, val title: String, val cover: String?) {
        val path get() = moviePath(title, bookId)
    }

    data class Page(val books: List<Book>, val hasNextPage: Boolean)

    data class Details(
        val bookId: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val genres: List<String>,
        val cast: List<String>,
    )

    data class Episode(
        val serial: Int,
        val chapterId: String,
        val free: Boolean,
        /** Epoch milliseconds, or 0 when the site gives none. */
        val publishedAt: Long,
        val path: String,
    ) {
        val name get() = if (free) "Episode $serial" else "Episode $serial · LOCKED"
    }

    /** What an episode page serves: the plain stream of a free episode, or nothing. */
    data class Stream(val serial: Int?, val videoUrl: String?) {
        val quality get() = qualityOf(videoUrl)
    }

    /** A shelf (`/shelf/<slug>-<id>`) renders the whole shelf on its first page. */
    fun shelf(html: String): Page {
        val props = NextData.pageProps(html, SITE)
        val list = props.arr("list") ?: throw SiteChangedException("$SITE's shelf has no list; the site may have changed")
        // `/shelf/.../2` answers the same items in the same order (checked 2026-11-11):
        // the shelf's totalPage counts pages of its client-side view, not of the data.
        return Page(list.objects().mapNotNull(::book), hasNextPage = false)
    }

    fun search(html: String): Page {
        val props = NextData.pageProps(html, SITE)
        val books = props.arr("books") ?: throw SiteChangedException("$SITE's search has no results list; the site may have changed")
        val page = props.int("page") ?: 1
        val last = props.int("totalPage") ?: page
        return Page(books.objects().mapNotNull(::book), hasNextPage = page < last)
    }

    fun details(html: String): Details {
        val data = movieData(html)
        val bookId = data.str("book_id") ?: throw SiteChangedException("$SITE's series page has no book_id")
        return Details(
            bookId = bookId,
            title = data.str("book_title") ?: throw SiteChangedException("$SITE's series page has no title"),
            cover = data.str("book_pic"),
            description = Text.fromHtml(data.str("special_desc")),
            genres = tags(data) { it !in CAST_CATEGORIES },
            cast = tags(data) { it in CAST_CATEGORIES },
        )
    }

    // tag_list mixes genres with the cast: categories 1001 and 1005 are the leads' names.
    private val CAST_CATEGORIES = setOf("1001", "1005")

    private fun tags(data: JsonObject, category: (String?) -> Boolean): List<String> =
        data.arr("tag_list")?.objects()
            ?.filter { category(it.str("category_id")) }
            ?.mapNotNull { it.str("text") }
            ?.distinct()
            .orEmpty()

    /** Every episode, newest first (Aniyomi's order), with the locked ones marked. */
    fun episodes(html: String): List<Episode> {
        val data = movieData(html)
        val bookId = data.str("book_id") ?: throw SiteChangedException("$SITE's series page has no book_id")
        val title = data.str("book_title") ?: ""
        val isPaid = data.int("is_paid")
        val paidStart = data.int("paid_start")
        val chapters = data.arr("online_base") ?: throw SiteChangedException("$SITE's series page has no episode list")
        return chapters.objects()
            // chapter_type 2 is the trailer (serial 0); only episodes are listed.
            .filter { it.int("chapter_type") == 1 }
            .mapNotNull { c ->
                val serial = c.int("serial_number") ?: return@mapNotNull null
                val chapterId = c.str("chapter_id") ?: return@mapNotNull null
                Episode(
                    serial = serial,
                    chapterId = chapterId,
                    free = isFree(isPaid, paidStart, serial),
                    publishedAt = (c.long("publish_at") ?: 0L) * 1000L,
                    path = episodePath(serial, title, bookId, chapterId),
                )
            }
            .sortedByDescending { it.serial }
    }

    /**
     * ReelShort's own rule: a book with `is_paid` 0 is free throughout; otherwise
     * episodes before `paid_start` are. Unknown values count as locked, so the list
     * never promises an episode the site will not serve. The episode page has the
     * last word either way ([stream]).
     */
    fun isFree(isPaid: Int?, paidStart: Int?, serial: Int): Boolean = when {
        isPaid == 0 -> true
        isPaid == null || paidStart == null -> false
        else -> serial < paidStart
    }

    /** The plain `video_url` an episode page carries; null when the episode is locked. */
    fun stream(html: String): Stream {
        val data = NextData.pageProps(html, SITE).obj("data")
            ?: throw SiteChangedException("$SITE's episode page has no data; the site may have changed")
        return Stream(serial = data.int("serial_number"), videoUrl = data.str("video_url"))
    }

    /** ReelShort names its renditions `-sd.m3u8` / `-hd.m3u8`; it serves only SD today. */
    fun qualityOf(url: String?): String {
        val tier = url?.let { Regex("-(sd|hd|fhd|uhd)\\.m3u8", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)?.uppercase()
        val format = if (url?.contains(".m3u8") == true) "HLS" else "video"
        return listOfNotNull(tier, format).joinToString(" · ")
    }

    fun moviePath(title: String, bookId: String) = "/movie/${Text.slug(title)}-$bookId"

    fun episodePath(serial: Int, title: String, bookId: String, chapterId: String) =
        "/episodes/episode-$serial-${Text.slug(title)}-$bookId-$chapterId"

    private fun movieData(html: String): JsonObject =
        NextData.pageProps(html, SITE).obj("data")
            ?: throw SiteChangedException("$SITE's series page has no data; the site may have changed")

    private fun book(o: JsonObject): Book? {
        val id = o.str("book_id") ?: return null
        val title = o.str("book_title") ?: return null
        return Book(id, title, o.str("book_pic"))
    }
}
