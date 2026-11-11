package dramas.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/**
 * Both sites are Next.js pages-router apps that render every page's data into
 * `<script id="__NEXT_DATA__" type="application/json">`. This reads that, and only
 * that: no script runs, nothing is decoded but JSON (doc/SOURCES.md).
 */
object NextData {
    private val json = Json { ignoreUnknownKeys = true }

    /** The page's `props.pageProps`. Throws [SiteChangedException] when it is not there. */
    fun pageProps(html: String, site: String): JsonObject {
        val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")
            ?: throw SiteChangedException("$site sent a page without its data (__NEXT_DATA__); the site may have changed")
        val root = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() as? JsonObject
            ?: throw SiteChangedException("$site's page data is not JSON; the site may have changed")
        return root.obj("props")?.obj("pageProps")
            ?: throw SiteChangedException("$site's page data has no pageProps; the site may have changed")
    }

    internal fun parse(text: String): JsonElement = json.parseToJsonElement(text)
}

/** The site answered, but not in the shape this extension reads. */
class SiteChangedException(message: String) : Exception(message)

/**
 * An episode the site does not serve for free. The extension never looks for its
 * stream any other way: the message says so, and the video list stays empty.
 */
class LockedEpisodeException(site: String, episode: String) : Exception(
    "$episode is locked on $site. This extension plays only the episodes $site " +
        "serves for free, and $site sends no stream for this one.",
)
