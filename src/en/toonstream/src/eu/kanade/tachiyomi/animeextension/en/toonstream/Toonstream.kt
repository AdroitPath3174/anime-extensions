package eu.kanade.tachiyomi.animeextension.en.toonstream

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Toonstream : ParsedAnimeHttpSource() {

    override val name = "Toonstream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "en"
    override val supportsLatest = true

    // 1. POPULAR ANIME
    override fun popularAnimeSelector() = "article.item"
    override fun popularAnimeNextPageSelector() = "div.pagination a.next"
    
    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h3").text()
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    // 2. SEARCH
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/page/$page/?s=$query", headers)

    // 3. LATEST EPISODES
    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodes/page/$page/", headers)

    // 4. ANIME DETAILS
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1").text()
        anime.description = document.select("div.wp-content p").text()
        return anime
    }

    // 5. EPISODES
    override fun episodeListSelector() = "ul.episodios li"
    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.name = element.select("div.episodiotitle a").text()
        return episode
    }

    // 6. VIDEO PLAYER (Placeholder)
    override fun videoListParse(response: okhttp3.Response): List<Video> {
        return listOf(Video("https://example.com/video.mp4", "Toonstream Video", "https://example.com/video.mp4"))
    }
}
