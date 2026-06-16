package eu.kanade.tachiyomi.animeextension.en.toonstream

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume

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

    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

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

    // ================= Details =================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.bodyString())
        return SAnime.create().apply {
            genre = doc.select("span.genres a").joinToString(", ") { it.text() }
            description = doc.select("div.description p").first()?.text() ?: ""
            status = when {
                doc.text().contains("Ongoing") -> SAnime.ONGOING
                else -> SAnime.COMPLETED
            }
        }
    }

    // ================= Episodes (all seasons) =================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.bodyString())
        val allEpisodes = mutableListOf<SEpisode>()

        val initialEpisodes = doc.select("ul#episode_by_temp article.episodes a.lnk-blk")
        initialEpisodes.forEachIndexed { idx, a ->
            allEpisodes.add(
                SEpisode.create().apply {
                    episode_number = (idx + 1).toFloat()
                    name = a.parent()?.select("h2.entry-title")?.text() ?: "Episode ${idx + 1}"
                    url = a.attr("href")
                },
            )
        }

        val seasonLinks = doc.select("div.choose-season ul.aa-cnt li.sel-temp a")
        if (seasonLinks.isEmpty() || seasonLinks.size == 1) {
            return allEpisodes
        }

        val postId = seasonLinks.first()?.attr("data-post") ?: return allEpisodes
        val maxSeason = seasonLinks.size

        for (season in 2..maxSeason) {
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
                    if (!htmlStr.isNullOrBlank()) {
                        html = Jsoup.parse(htmlStr)
                    }
                } catch (_: Exception) { }

                if (html == null) {
                    html = Jsoup.parse(responseBody)
                }

                val episodeLinks = html.select("article.post.episodes a.lnk-blk")
                episodeLinks.forEachIndexed { idx, a ->
                    allEpisodes.add(
                        SEpisode.create().apply {
                            episode_number = (idx + 1).toFloat()
                            name = a.parent()?.select("h2.entry-title")?.text()
                                ?: "Season $season Episode ${idx + 1}"
                            url = a.attr("href")
                        },
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return allEpisodes
    }

    // ================= Video Extraction (HTTP + WebView fallback) =================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // Try pure HTTP extraction first
        val httpVideos = try {
            getHttpVideoList(episode)
        } catch (_: Exception) {
            emptyList()
        }
        if (httpVideos.isNotEmpty()) return httpVideos

        // Fallback: use WebView to capture the token‑protected stream
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = WebView(context()).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "(function(){" +
                                        "var v=document.querySelector('video');" +
                                        "if(v&&v.src)return v.src;" +
                                        "var ifr=document.querySelector('iframe');" +
                                        "if(ifr)return ifr.src;" +
                                        "return '';" +
                                        "})();",
                                ) { result ->
                                    val videoUrl = result.trim('"')
                                    if (videoUrl.isNotEmpty() &&
                                        (videoUrl.endsWith(".m3u8") || videoUrl.endsWith(".mp4"))
                                    ) {
                                        val video = Video(videoUrl, "Auto", videoUrl)
                                        if (cont.isActive) cont.resume(listOf(video))
                                    } else {
                                        view.postDelayed({
                                            view.evaluateJavascript(
                                                "(function(){ var v=document.querySelector('video'); return v?v.src:''; })();",
                                            ) { retryResult ->
                                                val retryUrl = retryResult.trim('"')
                                                if (retryUrl.isNotEmpty()) {
                                                    val video = Video(retryUrl, "Auto", retryUrl)
                                                    if (cont.isActive) cont.resume(listOf(video))
                                                } else {
                                                    if (cont.isActive) cont.resume(emptyList())
                                                }
                                            }
                                        }, 5000)
                                    }
                                }
                            }, 8000)
                        }
                    }
                    loadUrl(episode.url)
                }
                cont.invokeOnCancellation { webView.destroy() }
            }
        }
    }

    private suspend fun getHttpVideoList(episode: SEpisode): List<Video> {
        val body = client.newCall(GET(episode.url, headers)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body)

        val scriptSrc = doc.select("script[src*=\"litespeed/js/\"]").first()?.attr("src")
            ?: return emptyList()
        val script1 = client.newCall(GET(scriptSrc, headers)).awaitSuccess().bodyString()

        val mlRegex = Regex("""_ml\s*=\s*JSON\.parse\('(\[[^\]]*\])'\)""")
        val mlMatch = mlRegex.find(script1) ?: return emptyList()
        val mlArray = mlMatch.groupValues[1].parseAs<JsonArray>()
        val joined = mlArray.joinToString("") { it.jsonPrimitive.content }
        val secondScriptUrl = String(Base64.decode(joined, Base64.DEFAULT))

        val script2 = client.newCall(GET(secondScriptUrl, headers)).awaitSuccess().bodyString()

        val hosterPatterns = listOf(
            "vidstream" to "gogo",
            "gogo" to "gogo",
            "dood" to "dood",
            "ok.ru" to "okru",
            "okru" to "okru",
            "mp4upload" to "mp4upload",
            "streamlare" to "streamlare",
            "filemoon" to "filemoon",
            "moonplayer" to "filemoon",
            "streamwish" to "streamwish",
            "wish" to "streamwish",
        )

        val urlRegex = Regex("""(?:"|')((?:https?:)?//[^"'\s]+)(?:"|')""")
        val allUrls = urlRegex.findAll(script2).map { it.groupValues[1] }.toList()

        val candidates = allUrls.mapNotNull { url ->
            val lower = url.lowercase()
            val match = hosterPatterns.firstOrNull { lower.contains(it.first) }
            if (match != null) Pair(url, match.second) else null
        }

        return candidates.parallelCatchingFlatMap { (url, hoster) ->
            when (hoster) {
                "gogo" -> gogoStreamExtractor.videosFromUrl(url)
                "dood" -> doodExtractor.videosFromUrl(url)
                "okru" -> okruExtractor.videosFromUrl(url)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers)
                "streamlare" -> streamlareExtractor.videosFromUrl(url)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
                "streamwish" -> streamwishExtractor.videosFromUrl(
                    url,
                    videoNameGen = { quality: String -> "StreamWish:$quality" },
                )
                else -> emptyList()
            }
        }
    }
}
