package eu.kanade.tachiyomi.animeextension.en.toonstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Toonstream : AnimeHttpSource() {

    override val name = "Toonstream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "en"
    override val supportsLatest = true

    // --- Popular Anime ---
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val elements = document.select("article.item")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("a").attr("href"))
                title = element.select("h3").text()
                thumbnail_url = element.select("img").attr("src")
            }
        }
        val hasNextPage = document.select("div.pagination a.next").first() != null
        return AnimesPage(animeList, hasNextPage)
    }

    // --- Search Anime ---
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/page/$page/?s=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // --- Latest Updates ---
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodes/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // --- Anime Details ---
    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        val anime = SAnime.create()
        anime.title = document.select("h1").text()
        anime.description = document.select("div.wp-content p").text()
        return anime
    }

    // --- Episode List ---
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string())
        return document.select("ul.episodios li").map { element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.select("a").attr("href"))
                name = element.select("div.episodiotitle a").text()
            }
        }
    }

    // --- Video Extraction ---
    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        return document.select("iframe").mapNotNull { element ->
            val url = if (element.hasAttr("data-src")) element.attr("data-src") else element.attr("src")
            if (url.isNotEmpty()) {
                Video(url, "Server Player", url)
            } else {
                null
            }
        }
    }
}
