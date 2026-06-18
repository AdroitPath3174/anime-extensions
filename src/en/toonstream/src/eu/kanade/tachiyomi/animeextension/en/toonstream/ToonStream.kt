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
import kotlinx.serialization.json.JsonObject
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

    // ================= Episodes (with movie support) =================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.bodyString())
        val allEpisodes = mutableListOf<SEpisode>()

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

        doc.select("ul#episode_by_temp article.episodes a.lnk-blk").forEach { a ->
            allEpisodes.add(
                SEpisode.create().apply {
                    episode_number = (allEpisodes.size + 1).toFloat()
                    name = a.parent()?.select("h2.entry-title")?.text() ?: "Episode"
                    url = a.attr("href")
                },
            )
        }

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

                    html.select("article.post.episodes a.lnk-blk").forEach { a ->
                        allEpisodes.add(
                            SEpisode.create().apply {
                                episode_number = (allEpisodes.size + 1).toFloat()
                                name = a.parent()?.select("h2.entry-title")?.text() ?: "Episode"
                                url = a.attr("href")
                            },
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return allEpisodes
    }

    // ================= Video extraction (pure HTTP, self‑contained) =================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val body = client.newCall(videoListRequest(episode)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body)

        // 1. Find obfuscated script
        val scriptSrc = doc.select("script[src*=\"litespeed/js/\"]").first()?.attr("src")
            ?: return emptyList()
        val script1 = client.newCall(GET(scriptSrc, headers)).awaitSuccess().bodyString()

        // 2. Decode second script URL
        val mlRegex = Regex("""_ml\s*=\s*JSON\.parse\('(\[[^\]]*\])'\)""")
        val mlMatch = mlRegex.find(script1) ?: return emptyList()
        val mlArray = mlMatch.groupValues[1].parseAs<JsonArray>()
        val joined = mlArray.joinToString("") { it.jsonPrimitive.content }
        val secondScriptUrl = String(Base64.decode(joined, Base64.DEFAULT))

        // 3. Fetch second script
        val script2 = client.newCall(GET(secondScriptUrl, headers)).awaitSuccess().bodyString()

        // 4. Try to find direct .m3u8/.mp4
        val directRegex = Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""")
        val directMatch = directRegex.find(script2)
        if (directMatch != null) {
            return listOf(Video(directMatch.value, "Direct", directMatch.value))
        }

        // 5. Look for an iframe URL in the script
        val iframeRegex = Regex("""src\s*=\s*["'](https?://[^"']+)["']""")
        val iframeMatch = iframeRegex.find(script2)
        if (iframeMatch != null) {
            val iframeUrl = iframeMatch.groupValues[1]
            val iframeBody = client.newCall(GET(iframeUrl, headers)).awaitSuccess().bodyString()
            val iframeDoc = Jsoup.parse(iframeBody)

            // Try <video> src
            val videoTag = iframeDoc.select("video source").first()
            val src = videoTag?.attr("src")
            if (!src.isNullOrBlank() && (src.endsWith(".mp4") || src.endsWith(".m3u8"))) {
                return listOf(Video(src, "Iframe", src))
            }

            // Try JSON config in iframe's scripts
            val jsonRegex = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
            for (script in iframeDoc.select("script")) {
                val content = script.html()
                val jsonMatch = jsonRegex.find(content)
                if (jsonMatch != null) {
                    val videoUrl = jsonMatch.groupValues[1]
                    return listOf(Video(videoUrl, "Iframe", videoUrl))
                }
            }
        }

        // 6. Last resort: try to find any .m3u8/.mp4 in the episode page itself (some sites put it in a data attribute)
        val pageVideos = doc.select("video source")
        if (pageVideos.isNotEmpty()) {
            val vSrc = pageVideos.first()?.attr("src") ?: ""
            if (vSrc.isNotBlank()) {
                return listOf(Video(vSrc, "Page", vSrc))
            }
        }

        return emptyList()
    }
}
