package eu.kanade.tachiyomi.animeextension.en.toonstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Toonstream : ParsedAnimeHttpSource() {

    override val name = "Toonstream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeSelector() = "article.item"
    override fun popularAnimeNextPageSelector() = "div.pagination a.next"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h3").text()
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/page/$page/?s=$query", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodes/page/$page/", headers)

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1").text()
        anime.description = document.select("div.wp-content p").text()
        return anime
    }

    override fun episodeListSelector() = "ul.episodios li"
    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.name = element.select("div.episodiotitle a").text()
        return episode
    }

    override fun videoListParse(response: okhttp3.Response): List<Video> {
        return listOf(Video("https://example.com/video.mp4", "Toonstream Video", "https://example.com/video.mp4"))
    }
}
