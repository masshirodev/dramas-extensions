package eu.kanade.tachiyomi.animeextension.en.reelshort

import dramas.core.SiteChangedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelShortParserTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun `the top shelf lists its books, as one page`() {
        val page = ReelShortParser.shelf(fixture("shelf-top.html"))
        assertEquals(5, page.books.size)
        val first = page.books.first()
        assertEquals("6abfeb8375b6b07d830d6b99", first.bookId)
        assertEquals("Agent of Shadows", first.title)
        assertTrue(first.cover!!.startsWith("https://v-img.crazymaplestudios.com/"))
        assertEquals("/movie/agent-of-shadows-6abfeb8375b6b07d830d6b99", first.path)
        // totalPage says 17, but /2 repeats page 1: there is no next page to ask for.
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `the new-release shelf is latest`() {
        val page = ReelShortParser.shelf(fixture("shelf-new.html"))
        assertEquals("I Accidentally Seduced the Siren King", page.books.first().title)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `search reads its books and pages on`() {
        val page = ReelShortParser.search(fixture("search-ceo.html"))
        assertEquals(
            listOf("66e252be905b22a9500d6cd7", "66274872810a13671300adc5", "66077f8d7103a8108101a2ad", "661e0368210e27b946008409"),
            page.books.map { it.bookId },
        )
        assertEquals("/movie/back-off-i-m-the-real-mrs-ceo-66e252be905b22a9500d6cd7", page.books.first().path)
        assertTrue("page 1 of 54", page.hasNextPage)
    }

    @Test
    fun `search on its last page has no next`() {
        val last = fixture("search-ceo.html").replace("\"page\": 1,", "\"page\": 54,")
        assertFalse(ReelShortParser.search(last).hasNextPage)
    }

    @Test
    fun `details read title, cover, synopsis as text, genres and cast`() {
        val d = ReelShortParser.details(fixture("movie-free.html"))
        assertEquals("66e252be905b22a9500d6cd7", d.bookId)
        assertEquals("Back Off! I'm the Real Mrs. CEO", d.title)
        assertTrue(d.cover!!.startsWith("https://v-img.crazymaplestudios.com/"))
        assertFalse("synopsis is text, not HTML", d.description!!.contains("<"))
        assertTrue(d.genres.containsAll(listOf("Drama", "Billionaire", "CEO", "Hidden Identity")))
        assertEquals(listOf("Stephen Krahel", "David Eves", "Tiffany Alvord", "Courtney Daives"), d.cast)
        assertTrue("cast is not a genre", d.genres.none { it in d.cast })
    }

    @Test
    fun `a paid book's episodes before paid_start are free and the rest are locked`() {
        val eps = ReelShortParser.episodes(fixture("movie-paid.html"))
        assertEquals(51, eps.size)
        assertEquals("newest first", (51 downTo 1).toList(), eps.map { it.serial })
        assertEquals((1..8).toSet(), eps.filter { it.free }.map { it.serial }.toSet())
        val nine = eps.single { it.serial == 9 }
        assertEquals("Episode 9 · LOCKED", nine.name)
        assertEquals("Episode 1", eps.last().name)
        assertEquals("/episodes/episode-1-the-triplets-final-regret-6aafa7fc530d47585a054f74-aqthox3d16", eps.last().path)
        assertEquals(1790233208000L, eps.last().publishedAt)
    }

    @Test
    fun `a free book lists every episode free, and leaves its trailer out`() {
        val eps = ReelShortParser.episodes(fixture("movie-free.html"))
        assertEquals(71, eps.size)
        assertTrue(eps.all { it.free })
        assertTrue(eps.none { it.serial == 0 })
        assertTrue(eps.none { it.name.contains("LOCKED") })
    }

    @Test
    fun `the free rule`() {
        assertTrue(ReelShortParser.isFree(isPaid = 0, paidStart = 0, serial = 71))
        assertTrue(ReelShortParser.isFree(isPaid = 0, paidStart = null, serial = 3))
        assertTrue(ReelShortParser.isFree(isPaid = 1, paidStart = 9, serial = 8))
        assertFalse(ReelShortParser.isFree(isPaid = 1, paidStart = 9, serial = 9))
        assertFalse(ReelShortParser.isFree(isPaid = 1, paidStart = 0, serial = 1))
        // Unknown never reads as free: the list must not promise a stream the site won't serve.
        assertFalse(ReelShortParser.isFree(isPaid = null, paidStart = 9, serial = 1))
        assertFalse(ReelShortParser.isFree(isPaid = 1, paidStart = null, serial = 1))
    }

    @Test
    fun `a free episode's page serves its plain HLS link`() {
        val s = ReelShortParser.stream(fixture("episode-free.html"))
        assertEquals(1, s.serial)
        assertTrue(s.videoUrl!!.startsWith("https://v-mps.crazymaplestudios.com/"))
        assertTrue(s.videoUrl!!.endsWith("-sd.m3u8"))
        assertEquals("SD · HLS", s.quality)
        assertEquals(71, ReelShortParser.stream(fixture("episode-all-free-book.html")).serial)
    }

    @Test
    fun `a locked episode's page serves no link`() {
        val s = ReelShortParser.stream(fixture("episode-locked.html"))
        assertEquals(9, s.serial)
        assertNull(s.videoUrl)
    }

    @Test
    fun `nothing is read from start_play's encrypted play_info`() {
        // The fixtures carry a marker where play_info was; no parser output may contain it.
        val marker = "REDACTED-NEVER-READ"
        for (name in listOf("shelf-top.html", "shelf-new.html", "search-ceo.html", "movie-paid.html", "movie-free.html")) {
            assertTrue("$name keeps the marker", fixture(name).contains(marker))
        }
        val outputs = listOf(
            ReelShortParser.shelf(fixture("shelf-top.html")),
            ReelShortParser.search(fixture("search-ceo.html")),
            ReelShortParser.details(fixture("movie-paid.html")),
            ReelShortParser.episodes(fixture("movie-paid.html")),
            ReelShortParser.stream(fixture("episode-locked.html")),
        )
        assertFalse(outputs.toString().contains(marker))
        // A locked episode stays locked even when a play_info is sitting right there.
        val withBlob = fixture("episode-locked.html").replace("\"data\": {", "\"data\": {\"start_play\": {\"play_info\": \"$marker\"},")
        assertNull(ReelShortParser.stream(withBlob).videoUrl)
    }

    @Test
    fun `quality labels`() {
        assertEquals("HD · HLS", ReelShortParser.qualityOf("https://x/a-hd.m3u8"))
        assertEquals("HLS", ReelShortParser.qualityOf("https://x/a.m3u8"))
        assertEquals("video", ReelShortParser.qualityOf("https://x/a.mp4"))
    }

    @Test
    fun `a page that is not ReelShort's says the site changed`() {
        assertThrows(SiteChangedException::class.java) { ReelShortParser.shelf("<html></html>") }
        val noList = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{}}}</script>"""
        assertThrows(SiteChangedException::class.java) { ReelShortParser.shelf(noList) }
        assertThrows(SiteChangedException::class.java) { ReelShortParser.search(noList) }
        assertThrows(SiteChangedException::class.java) { ReelShortParser.details(noList) }
        assertThrows(SiteChangedException::class.java) { ReelShortParser.episodes(noList) }
        assertThrows(SiteChangedException::class.java) { ReelShortParser.stream(noList) }
    }
}
