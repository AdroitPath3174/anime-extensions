package eu.kanade.tachiyomi.animeextension.en.toonstream

import android.util.Base64
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

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

    // ================= Extractors =================
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    // ================= Popular =================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/home/" else "$baseUrl/home/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.bodyString())
        val elements = doc.select("article.item")
        val animeList = elements.map { el ->
            SAnime.create().apply {
                title = el.select("h3 a").text()
                thumbnail_url = el.select("img").first()?.attr("src") ?: ""
                url = el.select("a").first()?.attr("href") ?: ""
            }
        }
        val hasNext = doc.select("a.next").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    // ================= Latest Episodes =================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ================= Search =================
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
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET(anime.url, headers)).awaitSuccess()
        val doc = Jsoup.parse(response.bodyString())
        val seasonLinks = doc.select("div.choose-season ul.aa-cnt li.sel-temp a")
        if (seasonLinks.isEmpty()) return emptyList()
        val postId = seasonLinks.first()?.attr("data-post") ?: return emptyList()
        val maxSeason = seasonLinks.size

        return coroutineScope {
            (1..maxSeason).map { season ->
                async {
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
                    val ajaxResponse = client.newCall(ajaxReq).awaitSuccess()
                    val json = ajaxResponse.bodyString().parseAs<JsonObject>()
                    val html = json["html"]?.jsonPrimitive?.content ?: ""
                    val fragment = Jsoup.parse(html)
                    fragment.select("article.post.episodes a.lnk-blk").mapIndexed { idx, a ->
                        SEpisode.create().apply {
                            episode_number = (idx + 1).toFloat()
                            name = "S${season}E${idx + 1}"
                            url = a.attr("href")
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    // ================= Video Extraction (pure HTTP) =================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val body = client.newCall(videoListRequest(episode)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(body)

        // 1. Find the obfuscated script (the one with the _ml array)
        val scriptSrc = doc.select("script[src*=\"litespeed/js/\"]").first()?.attr("src")
            ?: return emptyList()
        val script1 = client.newCall(GET(scriptSrc, headers)).awaitSuccess().bodyString()

        // 2. Decode the _ml array to obtain the second script URL
        val mlRegex = Regex("""_ml\s*=\s*JSON\.parse\('(\[[^\]]*\])'\)""")
        val mlMatch = mlRegex.find(script1) ?: return emptyList()
        val mlArray = mlMatch.groupValues[1].parseAs<JsonArray>()
        val joined = mlArray.joinToString("") { it.jsonPrimitive.content }
        val secondScriptUrl = String(Base64.decode(joined, Base64.DEFAULT))

        // 3. Fetch the second script (e.g., from 21wiz.com)
        val script2 = client.newCall(GET(secondScriptUrl, headers)).awaitSuccess().bodyString()

        // 4. Extract all URLs from the second script and match them with known hosters
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
