package eu.kanade.tachiyomi.animeextension.en.reelshort

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * extension.json is what the manifest and the repository index are built from
 * (doc/RELEASING.md); the source must say the same, or the source id the index lists
 * (derived from name and lang) is not the one the extension reports.
 */
class ExtensionInfoTest {
    // Unit tests run with the module directory as the working directory.
    private val info = Json.parseToJsonElement(File("extension.json").readText()).jsonObject

    private fun field(key: String) = info[key]!!.jsonPrimitive.content

    @Test
    fun `the source says what extension json says`() {
        assertEquals(ReelShortParser.SITE, field("name"))
        assertEquals(ReelShortParser.LANG, field("lang"))
        assertEquals(ReelShortParser.BASE_URL, field("baseUrl"))
        assertEquals("en.reelshort", field("pkg"))
    }

    @Test
    fun `the manifest's class exists in the package`() {
        val cls = field("class").removePrefix(".")
        val file = File("src/main/kotlin/eu/kanade/tachiyomi/animeextension/${field("pkg").replace('.', '/')}/$cls.kt")
        assertTrue("$file", file.readText().contains("class $cls : AnimeHttpSource()"))
    }
}
