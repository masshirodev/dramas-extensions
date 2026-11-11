package eu.kanade.tachiyomi.animeextension.en.reelshort

import dramas.core.LockedEpisodeException
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * ReelShort (reelshort.com): vertical short dramas. Plays the free episodes only,
 * from the plain HLS link each free episode's page serves (doc/SOURCES.md).
 */
class ReelShort : AnimeHttpSource() {
    override val name = ReelShortParser.SITE
    override val baseUrl = ReelShortParser.BASE_URL
    override val lang = ReelShortParser.LANG
    override val supportsLatest = true

    // Popular and latest are ReelShort's own "TOP" and "New Release" shelves.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl$POPULAR_SHELF", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = page(ReelShortParser.shelf(response.html()))

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl$LATEST_SHELF", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = page(ReelShortParser.shelf(response.html()))

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query.trim())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val html = response.html()
        val isShelf = response.request.url.encodedPath.startsWith("/shelf/")
        return page(if (isShelf) ReelShortParser.shelf(html) else ReelShortParser.search(html))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val d = ReelShortParser.details(response.html())
        return SAnime.create().apply {
            url = ReelShortParser.moviePath(d.title, d.bookId)
            title = d.title
            thumbnail_url = d.cover
            description = d.description
            genre = d.genres.joinToString().ifEmpty { null }
            artist = d.cast.joinToString().ifEmpty { null }
            // `update_status` is 1 on finished and new series alike, so it says nothing usable.
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        ReelShortParser.episodes(response.html()).map { e ->
            SEpisode.create().apply {
                url = e.path
                name = e.name
                episode_number = e.serial.toFloat()
                date_upload = e.publishedAt
            }
        }

    // The default videoListRequest fetches the episode's own page, which is the right one.
    override fun videoListParse(response: Response): List<Video> {
        val stream = ReelShortParser.stream(response.html())
        val videoUrl = stream.videoUrl
            ?: throw LockedEpisodeException(name, stream.serial?.let { "Episode $it" } ?: "This episode")
        return listOf(Video(videoUrl, "$name ${stream.quality}", videoUrl, headers))
    }

    // okhttp 4's body is nullable in Kotlin; a response handed to a parse method always has one.
    private fun Response.html(): String = body!!.string()

    private fun page(p: ReelShortParser.Page) = AnimesPage(
        p.books.map { b ->
            SAnime.create().apply {
                url = b.path
                title = b.title
                thumbnail_url = b.cover
            }
        },
        p.hasNextPage,
    )

    companion object {
        const val POPULAR_SHELF = "/shelf/top-short-movies-dramas-51004673"
        const val LATEST_SHELF = "/shelf/new-release-short-movies-dramas-51004672"
    }
}
