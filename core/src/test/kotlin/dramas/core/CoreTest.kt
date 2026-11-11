package dramas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun page(data: String) =
        """<html><body><script id="__NEXT_DATA__" type="application/json">$data</script></body></html>"""

    @Test
    fun `pageProps reads props pageProps`() {
        val props = NextData.pageProps(page("""{"props":{"pageProps":{"a":1}},"page":"/x"}"""), "Site")
        assertEquals(1, props.int("a"))
    }

    @Test
    fun `a page without its data says the site changed`() {
        val e = assertThrows(SiteChangedException::class.java) { NextData.pageProps("<html></html>", "Site") }
        assertTrue(e.message!!.startsWith("Site sent a page without its data"))
    }

    @Test
    fun `broken JSON and a missing pageProps are both a changed site`() {
        assertThrows(SiteChangedException::class.java) { NextData.pageProps(page("{not json"), "Site") }
        assertThrows(SiteChangedException::class.java) { NextData.pageProps(page("""{"props":{}}"""), "Site") }
    }

    @Test
    fun `the locked message names the episode and the site, and promises nothing else`() {
        val e = LockedEpisodeException("ReelShort", "Episode 9")
        assertEquals(
            "Episode 9 is locked on ReelShort. This extension plays only the episodes ReelShort " +
                "serves for free, and ReelShort sends no stream for this one.",
            e.message,
        )
    }

    @Test
    fun `lenient readers treat null, empty and mistyped values as absent`() {
        val o = NextData.parse("""{"s":"x","e":"","n":null,"i":"12","b":1,"f":false,"o":{},"a":[1,"y",null," "]}""") as kotlinx.serialization.json.JsonObject
        assertEquals("x", o.str("s"))
        assertNull(o.str("e"))
        assertNull(o.str("n"))
        assertNull(o.str("missing"))
        assertEquals(12, o.int("i"))
        assertNull(o.long("s"))
        assertEquals(true, o.bool("b"))
        assertEquals(false, o.bool("f"))
        assertNull(o.bool("s"))
        assertTrue(o.obj("o") != null)
        assertNull(o.obj("a"))
        assertEquals(listOf("1", "y"), o["a"].strings())
        assertEquals(emptyList<String>(), o["o"].strings())
    }

    @Test
    fun `html synopsis keeps its paragraphs`() {
        val text = Text.fromHtml("<h4><span>The Story:</span></h4>\n<p><br />In it, &mdash; a girl.</p><p>Second.</p>")
        assertEquals("The Story:\n\nIn it, — a girl.\n\nSecond.", text)
        assertNull(Text.fromHtml("  "))
        assertNull(Text.fromHtml(null))
        assertEquals("Plain words", Text.fromHtml("Plain words"))
    }

    @Test
    fun `slug matches the sites' own`() {
        assertEquals("back-off-i-m-the-real-mrs-ceo", Text.slug("Back Off! I'm the Real Mrs. CEO"))
        assertEquals("the-triplets-final-regret", Text.slug("The Triplets' Final Regret"))
        assertEquals("x", Text.slug("!!!"))
    }
}
