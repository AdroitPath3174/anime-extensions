package eu.kanade.tachiyomi.animeextension.en.toonstream

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ToonStream : AnimeHttpSource() {

    override val name = "ToonStream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; Redmi 5A) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        )
        .set("Referer", "$baseUrl/")

    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> url
    }

    // ================= Browse (Series + Movies) =================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/home/" else "$baseUrl/home/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.bodyString())
        val elements = doc.select("ul.post-lst li").filter { li ->
            val href = li.select("a.lnk-blk").first()?.attr("href") ?: ""
            href.contains("/series/") || href.contains("/movies/")
        }
        val animeList = elements.map { li ->
            val link = li.selectFirst("a.lnk-blk")!!
            val img = li.selectFirst("figure img")
            SAnime.create().apply {
                title = li.selectFirst("h2.entry-title")?.text() ?: ""
                thumbnail_url = fixUrl(img?.attr("src") ?: "")
                url = link.attr("href")
            }
        }
        val hasNext = doc.select("a.next").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
        return GET(url, headers)
    }
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = GET("$baseUrl/?s=$query&page=$page", headers)
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.bodyString())
        return SAnime.create().apply {
            genre = doc.select("span.genres a").joinToString(", ") { it.text() }
            description = doc.select("div.description p").first()?.text() ?: ""
            status = if (doc.text().contains("Ongoing")) SAnime.ONGOING else SAnime.COMPLETED
        }
    }

    // ================= Episodes (website names, no season prefix) =================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.bodyString())
        val allEpisodes = mutableListOf<SEpisode>()

        // Movie detection
        if (response.request.url.toString().contains("/movies/")) {
            val title = doc.selectFirst("h1.entry-title")?.text() ?: "Movie"
            allEpisodes.add(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = title
                    url = response.request.url.toString()
                },
            )
            return allEpisodes
        }

        // Extract episodes from the page – they already have the correct names like "Jujutsu Kaisen 1x1", "Jujutsu Kaisen 2x2"
        fun extractFromDocument(doc: Document) {
            doc.select("article.episodes a.lnk-blk").forEach { a ->
                val titleEl = a.parent()?.select("h2.entry-title")?.first()
                val name = titleEl?.text() ?: return@forEach
                allEpisodes.add(
                    SEpisode.create().apply {
                        episode_number = (allEpisodes.size + 1).toFloat()
                        this.name = name
                        url = a.attr("href")
                    },
                )
            }
        }

        // Initial season (1)
        extractFromDocument(doc)

        // Additional seasons via AJAX
        val seasonLinks = doc.select("div.choose-season ul.aa-cnt li.sel-temp a")
        if (seasonLinks.size > 1) {
            val postId = seasonLinks.first()?.attr("data-post") ?: return allEpisodes
            for (season in 2..seasonLinks.size) {
                try {
                    val body = "action=action_change_seas&season=$season&post=$postId"
                    val ajaxReq = Request.Builder()
                        .url("$baseUrl/wp-admin/admin-ajax.php")
                        .headers(headers)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(
                            okhttp3.RequestBody.create(
                                "application/x-www-form-urlencoded".toMediaType(),
                                body,
                            ),
                        )
                        .build()
                    val ajaxResponse = client.newCall(ajaxReq).execute()
                    val responseBody = ajaxResponse.bodyString()
                    var html: Document? = null
                    try {
                        val jsonObj = responseBody.parseAs<JsonObject>()
                        val htmlStr = jsonObj["html"]?.jsonPrimitive?.content
                        if (!htmlStr.isNullOrBlank()) html = Jsoup.parse(htmlStr)
                    } catch (_: Exception) {}
                    if (html == null) html = Jsoup.parse(responseBody)
                    extractFromDocument(html)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return allEpisodes
    }

    // ================= Video extraction – complete 3‑step script chain =================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val body = client.newCall(videoListRequest(episode)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body)

        // 1. First obfuscated script (litespeed JS) -> leads to 21wiz.com script
        val script1Src = doc.select("script[src*=\"litespeed/js/\"]").first()?.attr("src")
            ?: return emptyList()
        val script1 = client.newCall(GET(script1Src, headers)).awaitSuccess().bodyString()

        // Decode the _ml array to get the second script URL
        val mlRegex = Regex("""_ml\s*=\s*JSON\.parse\('(\[[^\]]*\])'\)""")
        val mlMatch1 = mlRegex.find(script1) ?: return emptyList()
        val mlArray1 = mlMatch1.groupValues[1].parseAs<JsonArray>()
        val joined1 = mlArray1.joinToString("") { it.jsonPrimitive.content }
        val secondScriptUrl = String(Base64.decode(joined1, Base64.DEFAULT))

        // 2. Fetch second script (e.g., from 21wiz.com)
        val script2 = client.newCall(GET(secondScriptUrl, headers)).awaitSuccess().bodyString()

        // 3. Second script contains its own _ml array – decode to get the third script URL
        val mlMatch2 = mlRegex.find(script2)
        if (mlMatch2 != null) {
            val mlArray2 = mlMatch2.groupValues[1].parseAs<JsonArray>()
            val joined2 = mlArray2.joinToString("") { it.jsonPrimitive.content }
            // The website appends a timestamp to the decoded URL; simulate that
            val timestamp = (System.currentTimeMillis() / 1000.0).toString()
            val thirdScriptUrl = String(Base64.decode(joined2, Base64.DEFAULT)) + timestamp

            // 4. Fetch the third script – this one sets up the player
            val script3 = client.newCall(GET(thirdScriptUrl, headers)).awaitSuccess().bodyString()

            // 5. Extract video URL from the third script
            // Look for direct .m3u8/.mp4
            val directVideo = Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""").find(script3)
            if (directVideo != null) {
                return listOf(Video(directVideo.value, "Auto", directVideo.value))
            }

            // Look for an iframe src
            val iframeSrc = Regex("""src\s*=\s*["'](https?://[^"']+)["']""").find(script3)?.groupValues?.get(1)
            if (iframeSrc != null) {
                val iframeBody = client.newCall(GET(iframeSrc, headers)).awaitSuccess().bodyString()
                val iframeDoc = Jsoup.parse(iframeBody)
                val videoSrc = iframeDoc.select("video source").first()?.attr("src")
                    ?: iframeDoc.select("iframe").first()?.attr("src")
                if (!videoSrc.isNullOrBlank()) {
                    return listOf(Video(videoSrc, "Auto", videoSrc))
                }
            }
        }

        // Fallback: try to find a video URL directly in the second script (some episodes might use a simpler player)
        val fallbackVideo = Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""").find(script2)
        if (fallbackVideo != null) {
            return listOf(Video(fallbackVideo.value, "Auto", fallbackVideo.value))
        }

        return emptyList()
    }
}
