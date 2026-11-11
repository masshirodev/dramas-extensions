package eu.kanade.tachiyomi.animeextension.en.dramabox

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
 * DramaBox (dramaboxdb.com): vertical short dramas. Plays the free episodes only,
 * from the MP4 and HLS links DramaBox's series page serves for them; those expire,
 * so they are read again every time an episode is played (doc/SOURCES.md).
 */
class DramaBox : AnimeHttpSource() {
    override val name = DramaBoxParser.SITE
    override val baseUrl = DramaBoxParser.BASE_URL
    override val lang = DramaBoxParser.LANG
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/channel/trending/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = page(DramaBoxParser.channel(response.html()))

    // Genre 0 is every genre, newest first: the closest DramaBox has to "latest".
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/genres/0/$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = page(DramaBoxParser.genre(response.html()))

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val url = "$baseUrl/search/$page".toHttpUrl().newBuilder()
            .addQueryParameter("searchValue", query.trim())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val html = response.html()
        val isChannel = response.request.url.encodedPath.startsWith("/channel/")
        return page(if (isChannel) DramaBoxParser.channel(html) else DramaBoxParser.search(html))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val d = DramaBoxParser.details(response.html())
        return SAnime.create().apply {
            url = DramaBoxParser.moviePath(d.bookId, d.slug)
            title = d.title
            thumbnail_url = d.cover
            description = d.description
            genre = d.genres.joinToString().ifEmpty { null }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        DramaBoxParser.episodes(response.html()).map { e ->
            SEpisode.create().apply {
                url = e.path
                name = e.name
                episode_number = e.number.toFloat()
                date_upload = e.updatedAt
            }
        }

    /**
     * The series page, fetched now: its links are signed for about a day, so none is
     * ever kept from the episode list. The chapter to read rides in the fragment,
     * which OkHttp never sends.
     */
    override fun videoListRequest(episode: SEpisode): Request {
        val ref = DramaBoxParser.parseEpisodePath(episode.url)
        return GET("$baseUrl${DramaBoxParser.moviePath(ref.bookId, ref.slug)}#${ref.chapterId}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val chapterId = generateSequence(response) { it.priorResponse }.last().request.url.fragment
            ?: throw IllegalStateException("$name: no episode named in the request")
        val nowSeconds = System.currentTimeMillis() / 1000
        return when (val s = DramaBoxParser.streams(response.html(), chapterId, nowSeconds)) {
            is DramaBoxParser.Streams.Locked ->
                throw LockedEpisodeException(name, s.number?.let { "Episode $it" } ?: "This episode")
            is DramaBoxParser.Streams.Free ->
                s.streams.map { Video(it.url, "$name ${it.quality}", it.url, headers) }
        }
    }

    // okhttp 4's body is nullable in Kotlin; a response handed to a parse method always has one.
    private fun Response.html(): String = body!!.string()

    private fun page(p: DramaBoxParser.Page) = AnimesPage(
        p.books.map { b ->
            SAnime.create().apply {
                url = b.path
                title = b.title
                thumbnail_url = b.cover
            }
        },
        p.hasNextPage,
    )
}
