package dramas.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object Text {
    /**
     * A synopsis the site writes as HTML, as plain text with its paragraphs kept.
     * Jsoup's own `text()` would run every paragraph into one line.
     */
    fun fromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val doc = Jsoup.parse(html)
        doc.outputSettings(Document.OutputSettings().prettyPrint(false))
        doc.select("br").before("\\n")
        doc.select("p, h1, h2, h3, h4, h5, h6, div, li").before("\\n\\n")
        return doc.text()
            .replace("\\n", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /** The URL slug both sites build from a title: lowercase words joined by '-'. */
    fun slug(title: String): String = title.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "x" }
}
