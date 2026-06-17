package eu.kanade.tachiyomi.animeextension.en.toonstream

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import android.util.Base64

class ToonStream : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "ToonStream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "en"
    override val supportsLatest = true

    // ---------- Preferences ----------
    private val preferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Auto", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("Auto", "1080", "720", "480", "360")
            setDefaultValue("Auto")
            summary = "%s"
        }.let(screen::addPreference)
    }

    private val qualityPreference: String
        get() = preferences.getString("preferred_quality", "Auto")!!

    // ---------- Headers ----------
    override fun headersBuilder() = super.headersBuilder()
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; Redmi 5A) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        )
        .set("Referer", "$baseUrl/")

    // ---------- Extractors ----------
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ---------------------------------------------------------------
    // Custom ToonStream Extractor
    // ---------------------------------------------------------------
    private inner class ToonStreamExtractor {
        suspend fun getVideos(episodeUrl: String): List<Video> {
            // Step 1: Fetch episode page
            val episodeDoc = fetchAndParse(episodeUrl)

            // Step 2: Find the first obfuscated script (litespeed JS)
            val script1Url = episodeDoc.select("script[src*=\"litespeed/js/\"]").first()?.attr("src")
                ?: return emptyList()

            // Step 3: Download and decode the first script to obtain the second script URL
            val script1Content = fetch(script1Url)
            val secondScriptUrl = extractSecondScriptUrl(script1Content) ?: return emptyList()

            // Step 4: Fetch the second script (e.g., from 21wiz.com)
            val script2Content = fetch(secondScriptUrl)

            // Step 5: Try to extract a direct video URL or iframe URL from the second script
            val directVideoUrl = extractDirectVideoUrl(script2Content)
            if (directVideoUrl != null) {
                return listOf(Video(directVideoUrl, "Direct", directVideoUrl))
            }

            val iframeUrl = extractIframeUrl(script2Content)
            if (iframeUrl != null) {
                val iframeDoc = fetchAndParse(iframeUrl)
                val videoUrl = extractVideoFromIframe(iframeDoc)
                if (videoUrl != null) {
                    return if (videoUrl.endsWith(".m3u8")) {
                        playlistUtils.extractFromHls(
                            videoUrl,
                            masterHeaders = headers,
                            videoHeaders = headers,
                            videoNameGen = { quality -> quality },
                        )
                    } else {
                        listOf(Video(videoUrl, "SD", videoUrl))
                    }
                }
            }

            // Step 6: Try a deeper extraction – look for JSON config or API calls
            val jsonConfigUrl = extractJsonConfigUrl(script2Content)
            if (jsonConfigUrl != null) {
                val json = fetch(jsonConfigUrl).parseAs<JsonObject>()
                val streamUrl = json["file"]?.jsonPrimitive?.content
                if (streamUrl != null) {
                    return if (streamUrl.endsWith(".m3u8")) {
                        playlistUtils.extractFromHls(
                            streamUrl,
                            masterHeaders = headers,
                            videoHeaders = headers,
                            videoNameGen = { quality -> quality },
                        )
                    } else {
                        listOf(Video(streamUrl, "HD", streamUrl))
                    }
                }
            }

            return emptyList()
        }

        // ----- helpers -----
        private suspend fun fetch(url: String): String {
            return client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        }

        private suspend fun fetchAndParse(url: String): Document {
            return Jsoup.parse(fetch(url))
        }

        private fun extractSecondScriptUrl(scriptContent: String): String? {
            val mlRegex = Regex("""_ml\s*=\s*JSON\.parse\('(\[[^\]]*\])'\)""")
            val mlMatch = mlRegex.find(scriptContent) ?: return null
            val mlArray = mlMatch.groupValues[1].parseAs<JsonArray>()
            val joined = mlArray.joinToString("") { it.jsonPrimitive.content }
            return String(Base64.decode(joined, Base64.DEFAULT))
        }

        private fun extractDirectVideoUrl(script2: String): String? {
            // Search for m3u8 or mp4 directly in the script
            val regex = Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""")
            return regex.find(script2)?.value
        }

        private fun extractIframeUrl(script2: String): String? {
            // Search for an iframe src attribute
            val regex = Regex("""src\s*=\s*["'](https?://[^"']+)["']""")
            return regex.find(script2)?.groupValues?.get(1)
        }

        private fun extractVideoFromIframe(doc: Document): String? {
            // Try <video> src
            val videoTag = doc.select("video source").first()
            val src = videoTag?.attr("src")
            if (!src.isNullOrBlank() && (src.endsWith(".mp4") || src.endsWith(".m3u8"))) {
                return src
            }
            // Try a script that sets up the player
            val scripts = doc.select("script")
            for (script in scripts) {
                val content = script.html()
                val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
                val match = fileRegex.find(content)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            // Try another iframe inside (nested)
            val nestedIframe = doc.select("iframe").first()
            if (nestedIframe != null) {
                val nestedSrc = nestedIframe.attr("src")
                if (nestedSrc.isNotBlank()) return nestedSrc // might need further fetching, but we assume direct
            }
            return null
        }

        private fun extractJsonConfigUrl(script2: String): String? {
            // Some players use a JSON config endpoint
            val regex = Regex("""["'](https?://[^"']*\.json[^"']*)["']""")
            return regex.find(script2)?.groupValues?.get(1)
        }
    }

    // ---------------------------------------------------------------
    // Standard AnimeHttpSource methods
    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    // Episodes (with movie support)
    // ---------------------------------------------------------------
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

        // Series – get initial episodes
        doc.select("ul#episode_by_temp article.episodes a.lnk-blk").forEach { a ->
            val epTitle = a.parent()?.select("h2.entry-title")?.text() ?: "Episode"
            allEpisodes.add(
                SEpisode.create().apply {
                    episode_number = (allEpisodes.size + 1).toFloat()
                    name = epTitle
                    url = a.attr("href")
                },
            )
        }

        // Load additional seasons via AJAX
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
                        val epTitle = a.parent()?.select("h2.entry-title")?.text() ?: "Episode"
                        allEpisodes.add(
                            SEpisode.create().apply {
                                episode_number = (allEpisodes.size + 1).toFloat()
                                name = epTitle
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

    // ---------------------------------------------------------------
    // Video extraction – uses custom extractor (no WebView)
    // ---------------------------------------------------------------
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val extractor = ToonStreamExtractor()
        val videos = extractor.getVideos(episode.url)
        return applyQualityFilter(videos)
    }

    private fun applyQualityFilter(videos: List<Video>): List<Video> {
        val quality = qualityPreference
        if (quality == "Auto") return videos
        return videos.filter { it.quality.contains(quality, ignoreCase = true) }.ifEmpty { videos }
    }

    // ---------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------
    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> url
    }
}
