package eu.kanade.tachiyomi.animeextension.en.toonstream

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

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

    // ================= Episodes =================
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

        // Extract episodes from the page – they already have correct names like "Jujutsu Kaisen 1x1"
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

    // ================= Video extraction =================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeBody = client.newCall(videoListRequest(episode)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(episodeBody)

        // 1. First obfuscated script (litespeed JS) -> gives second script URL
        val script1Src = doc.select("script[src*=\"litespeed/js/\"]").first()?.attr("src")
            ?: return emptyList()
        val script1 = client.newCall(GET(script1Src, headers)).awaitSuccess().bodyString()

        val mlRegex = Regex("""_ml\s*=\s*JSON\.parse\('(\[[^\]]*\])'\)""")
        val mlMatch1 = mlRegex.find(script1) ?: return emptyList()
        val mlArray1 = mlMatch1.groupValues[1].parseAs<JsonArray>()
        val joined1 = mlArray1.joinToString("") { it.jsonPrimitive.content }
        val secondScriptUrl = String(Base64.decode(joined1, Base64.DEFAULT))

        // Resolve second script URL relative to the episode page if it's not absolute
        val resolvedSecondUrl = try {
            URI(episode.url).resolve(secondScriptUrl).toString()
        } catch (_: Exception) {
            secondScriptUrl
        }

        // 2. Fetch second script (e.g. from 21wiz.com)
        val script2Headers = headersBuilder()
            .set("Referer", episode.url)
            .build()
        val script2 = client.newCall(GET(resolvedSecondUrl, script2Headers)).awaitSuccess().bodyString()

        // 3. Second script contains its own _ml array – decode to get the third script base URL
        val mlMatch2 = mlRegex.find(script2)
        if (mlMatch2 != null) {
            val mlArray2 = mlMatch2.groupValues[1].parseAs<JsonArray>()
            val joined2 = mlArray2.joinToString("") { it.jsonPrimitive.content }
            val decoded3 = String(Base64.decode(joined2, Base64.DEFAULT))

            // Replicate JavaScript timestamp: (new Date().getTime() + (Date.now() % 1000) / 1000).toString()
            val millis = System.currentTimeMillis()
            val frac = (millis % 1000).toString().padStart(3, '0')
            val timestamp = "$millis.$frac"

            // The JS code concatenates the decoded string and the timestamp – exactly as done in the browser
            val thirdScriptUrl = decoded3 + timestamp

            // 4. Fetch the third script (this one sets up the video player)
            val script3Headers = headersBuilder()
                .set("Referer", episode.url)
                .build()
            val script3 = client.newCall(GET(thirdScriptUrl, script3Headers)).awaitSuccess().bodyString()

            // 5. Try to extract a direct .m3u8 or .mp4 URL from the third script
            val directVideoUrl = Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""")
                .find(script3)?.value
            if (directVideoUrl != null) {
                return extractVideoFromUrl(directVideoUrl, episode.url)
            }

            // 6. Look for an iframe src
            val iframeSrc = Regex("""src\s*=\s*["'](https?://[^"']+)["']""")
                .find(script3)?.groupValues?.get(1)
            if (iframeSrc != null) {
                val iframeHeaders = headersBuilder()
                    .set("Referer", thirdScriptUrl)
                    .build()
                val iframeBody = client.newCall(GET(iframeSrc, iframeHeaders)).awaitSuccess().bodyString()
                val iframeDoc = Jsoup.parse(iframeBody)

                // Extract video source from iframe's video or another iframe
                val videoSrc = iframeDoc.select("video source").first()?.attr("src")
                    ?: iframeDoc.select("iframe").first()?.attr("src")
                if (!videoSrc.isNullOrBlank()) {
                    return extractVideoFromUrl(videoSrc, iframeSrc)
                }
            }
        }

        // Fallback: direct video URL in the second script (unlikely but safe)
        val fallbackVideo = Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""")
            .find(script2)?.value
        if (fallbackVideo != null) {
            return extractVideoFromUrl(fallbackVideo, episode.url)
        }

        return emptyList()
    }

    /**
     * Converts a raw video URL into a list of Aniyomi Video objects.
     * If it's an HLS stream (.m3u8), uses PlaylistUtils to extract quality options.
     */
    private suspend fun extractVideoFromUrl(videoUrl: String, referer: String): List<Video> {
        val videoHeaders = headersBuilder()
            .set("Referer", referer)
            .build()

        return try {
            if (videoUrl.endsWith(".m3u8")) {
                // Use PlaylistUtils to parse the master playlist and return quality-separated videos
                PlaylistUtils(client, videoHeaders).extractFromHls(videoUrl, referer)
            } else {
                listOf(Video(videoUrl, "Auto", videoUrl))
            }
        } catch (e: Exception) {
            // Fallback to a single entry if HLS parsing fails
            listOf(Video(videoUrl, "Auto", videoUrl))
        }
    }
}
