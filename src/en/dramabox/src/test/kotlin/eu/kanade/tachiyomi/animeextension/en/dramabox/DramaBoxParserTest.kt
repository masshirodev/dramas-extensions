package eu.kanade.tachiyomi.animeextension.en.dramabox

import dramas.core.SiteChangedException
import eu.kanade.tachiyomi.animeextension.en.dramabox.DramaBoxParser.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaBoxParserTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    /** When movie.html was saved (2026-11-11): its links expire at 1794517200, a day later. */
    private val savedAt = 1794430800L

    @Test
    fun `the trending channel is popular, four pages of it`() {
        val page = DramaBoxParser.channel(fixture("channel-trending.html"))
        assertEquals(5, page.books.size)
        val first = page.books.first()
        assertEquals("42000015757", first.bookId)
        assertEquals("Guess Who They Miss Now", first.title)
        assertEquals("/movie/42000015757/guess-who-they-miss-now", first.path)
        assertTrue(first.cover!!.startsWith("https://thwztchapter.dramaboxdb.com/"))
        assertTrue("page 1 of 4", page.hasNextPage)
        val last = fixture("channel-trending.html").replace("\"pageNo\": 1,", "\"pageNo\": 4,")
        assertFalse(DramaBoxParser.channel(last).hasNextPage)
    }

    @Test
    fun `every genre, newest first, is latest`() {
        val page = DramaBoxParser.genre(fixture("genres-all.html"))
        assertEquals(listOf("42000034399", "42000033413", "42000033786", "42000034364"), page.books.map { it.bookId })
        assertEquals("/movie/42000034399/hot-pot-not-heartbreak-dubbed-", page.books.first().path)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `search reads its own cover field and pages on`() {
        val page = DramaBoxParser.search(fixture("search-ceo-p2.html"))
        assertEquals(4, page.books.size)
        val first = page.books.first()
        assertEquals("Ex-Husband CEO, Meet Your Genius Son", first.title)
        assertTrue(first.cover!!.contains("42000015482.jpg"))
        assertTrue("page 2 of 8", page.hasNextPage)
    }

    @Test
    fun `a search with no results is an empty page, not an error`() {
        val empty = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"isEmpty":true,"totalNum":0}}}</script>"""
        val page = DramaBoxParser.search(empty)
        assertTrue(page.books.isEmpty())
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `details read title, cover, synopsis and genres`() {
        val d = DramaBoxParser.details(fixture("movie.html"))
        assertEquals("42000028173", d.bookId)
        assertEquals("Love Is A Dangerous Game", d.title)
        assertTrue(d.cover!!.endsWith("42000028173.jpg@w=360&h=640"))
        assertTrue(d.description!!.startsWith("Olivia is dragged"))
        assertEquals(listOf("Billionaire", "Forbidden Love", "Concealed Identity"), d.genres)
        assertEquals("love-is-a-dangerous-game", d.slug)
    }

    @Test
    fun `episodes follow DramaBox's unlock flag, newest first`() {
        val eps = DramaBoxParser.episodes(fixture("movie.html"))
        assertEquals(43, eps.size)
        assertEquals((43 downTo 1).toList(), eps.map { it.number })
        assertEquals((1..8).toSet(), eps.filter { it.free }.map { it.number }.toSet())
        assertEquals("Episode 9 · LOCKED", eps.single { it.number == 9 }.name)
        val first = eps.last()
        assertEquals("Episode 1", first.name)
        assertEquals("701673570", first.chapterId)
        assertEquals("/ep/42000028173_love-is-a-dangerous-game/701673570_Episode-1", first.path)
        assertEquals(1793836800000L, first.updatedAt) // 2026-11-05
    }

    @Test
    fun `the episode list never carries a stream link, because links expire`() {
        val eps = DramaBoxParser.episodes(fixture("movie.html"))
        val text = eps.toString()
        assertFalse(text.contains("Expires="))
        assertFalse(text.contains("Signature="))
        assertFalse(text.contains(".m3u8"))
        assertFalse(text.contains(".mp4"))
    }

    @Test
    fun `a free episode is read from the page fetched at play time`() {
        val s = DramaBoxParser.streams(fixture("movie.html"), "701673570", savedAt) as Streams.Free
        assertEquals(listOf("720p · MP4", "720p · HLS"), s.streams.map { it.quality })
        assertTrue(s.streams[0].url.startsWith("https://hwvideoseo.dramaboxdb.com/"))
        assertTrue(s.streams[0].url.contains(".720p.narrowv3.mp4?Expires=1794517200"))
        assertTrue(s.streams[1].url.startsWith("https://hwzthls.dramaboxdb.com/"))
        assertTrue(s.streams[1].url.contains(".720p.m3u8?Expires=1794517200"))
    }

    @Test
    fun `a locked episode has no link, and is locked`() {
        // Chapter index 8 is episode 9: unlock false, no mp4, no m3u8Url.
        assertEquals(Streams.Locked(9), DramaBoxParser.streams(fixture("movie.html"), "701673578", savedAt))
    }

    @Test
    fun `unlock false is locked even if a link were present`() {
        val tampered = fixture("movie.html").replaceFirst("\"unlock\": true", "\"unlock\": false")
        assertEquals(Streams.Locked(1), DramaBoxParser.streams(tampered, "701673570", savedAt))
    }

    @Test
    fun `a link that has expired is refused, not played`() {
        val e = assertThrows(SiteChangedException::class.java) {
            DramaBoxParser.streams(fixture("movie.html"), "701673570", 1794517200L)
        }
        assertEquals("DramaBox served a link that has already expired; try again later", e.message)
        // Within the margin counts as expired too: playback has to be able to start.
        assertThrows(SiteChangedException::class.java) {
            DramaBoxParser.streams(fixture("movie.html"), "701673570", 1794517200L - DramaBoxParser.EXPIRY_MARGIN_SECONDS)
        }
        DramaBoxParser.streams(fixture("movie.html"), "701673570", 1794517200L - DramaBoxParser.EXPIRY_MARGIN_SECONDS - 1)
    }

    @Test
    fun `an episode the page no longer lists says so`() {
        val e = assertThrows(SiteChangedException::class.java) {
            DramaBoxParser.streams(fixture("movie.html"), "1", savedAt)
        }
        assertEquals("DramaBox no longer lists this episode (1)", e.message)
    }

    @Test
    fun `an episode path round-trips to the page and chapter to read`() {
        val path = DramaBoxParser.episodePath("42000028173", "love-is-a-dangerous-game", "701673570", 1)
        assertEquals(
            DramaBoxParser.EpisodeRef("42000028173", "love-is-a-dangerous-game", "701673570"),
            DramaBoxParser.parseEpisodePath(path),
        )
        assertEquals(
            DramaBoxParser.EpisodeRef("42000028173", null, "701673570"),
            DramaBoxParser.parseEpisodePath(DramaBoxParser.episodePath("42000028173", null, "701673570", 1)),
        )
        assertEquals("/movie/42000028173", DramaBoxParser.moviePath("42000028173", null))
        assertThrows(IllegalArgumentException::class.java) { DramaBoxParser.parseEpisodePath("/movie/1") }
    }

    @Test
    fun `link helpers`() {
        assertEquals(1794517200L, DramaBoxParser.expiresOf("https://x/a.mp4?Expires=1794517200&Signature=s"))
        assertNull(DramaBoxParser.expiresOf("https://x/a.mp4"))
        assertEquals("1080p", DramaBoxParser.resolutionOf("https://x/7.1080p.m3u8"))
        assertNull(DramaBoxParser.resolutionOf("https://x/7.m3u8"))
    }

    @Test
    fun `a page that is not DramaBox's says the site changed`() {
        val bare = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{}}}</script>"""
        assertThrows(SiteChangedException::class.java) { DramaBoxParser.channel(bare) }
        assertThrows(SiteChangedException::class.java) { DramaBoxParser.genre(bare) }
        assertThrows(SiteChangedException::class.java) { DramaBoxParser.search(bare) }
        assertThrows(SiteChangedException::class.java) { DramaBoxParser.details(bare) }
        assertThrows(SiteChangedException::class.java) { DramaBoxParser.episodes(bare) }
        assertThrows(SiteChangedException::class.java) { DramaBoxParser.streams(bare, "1", savedAt) }
    }
}
