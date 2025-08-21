package eu.kanade.tachiyomi.animeextension.ar.egybest

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.urlresolver.UrlResolver
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EgyBest : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EgyBest"

    // ⚠️ ملاحظة: دومين إيجي بست يتغير باستمرار، غيّر الرابط هنا إذا وقف
    override val baseUrl = "https://egybest.org"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular
    override fun popularAnimeSelector(): String = "div.pin-posts-list li.movieItem"

    override fun popularAnimeNextPageSelector(): String = "div.whatever"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text().let { editTitle(it, true) }
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // Episodes
    override fun episodeListSelector() = "div.EpsList li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        document.select(episodeListSelector()).forEach { element ->
            episodes.add(episodeFromElement(element))
        }
        return episodes
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.text()
        return episode
    }

    // Videos
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val urlResolver by lazy { UrlResolver(client) }

    override fun videoListSelector() = "ul.serversList li"

    override fun videoListParse(response: Response): List<Video> {
        val requestBody = FormBody.Builder().add("View", "1").build()
        val newHeaders = headers.newBuilder().add("referer", "$baseUrl/").build()
        val newResponse = client.newCall(
            POST(response.request.url.toString(), headers = newHeaders, body = requestBody)
        ).execute().asJsoup()
        return newResponse.select(videoListSelector()).parallelCatchingFlatMapBlocking(::extractVideos)
    }

    private fun extractVideos(link: Element): List<Video> {
        val url = link.attr("data-link")
        return when {
            "dood" in url -> doodExtractor.videosFromUrl(url)
            "mixdrop" in url -> mixDropExtractor.videosFromUrl(url)
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            else -> streamWishExtractor.videosFromUrl(url)
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedByDescending { it.quality.contains(quality) }
    }

    // Search
    override fun searchAnimeSelector(): String = "div.catHolder li.movieItem"
    override fun searchAnimeNextPageSelector(): String = "div.pagination-two a:contains(›)"
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else baseUrl
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun getFilterList() = AnimeFilterList()

    // Anime details
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.single-thumbnail img").attr("src")
        anime.title = document.select("div.infoBox div.singleTitle").text()
        anime.author = document.select("div.LeftBox li:contains(البلد) a").text()
        anime.artist = document.select("div.LeftBox li:contains(القسم) a").text()
        anime.genre = document.select("div.LeftBox li a").joinToString(", ") { it.text() }
        anime.description = document.select("div.infoBox div.extra-content p").text()
        anime.status =
            if (anime.title.contains("كامل") || anime.title.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
        return anime
    }

    // Latest
    override fun latestUpdatesSelector() = "section.main-section li.movieItem"
    override fun latestUpdatesNextPageSelector() = "div.pagination ul.page-numbers li a.next"
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page/")
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // Utils
    private fun editTitle(title: String, details: Boolean = false): String {
        val movieRegex = Regex("(?:فيلم|عرض)\\s(.*?)\\s*(?:\\d{4})*\\s*(مترجم|مدبلج)")
        val seriesRegex = Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)")
        return when {
            movieRegex.containsMatchIn(title) -> {
                val (movieName, type) = movieRegex.find(title)!!.destructured
                movieName + if (details) " ($type)" else ""
            }
            seriesRegex.containsMatchIn(title) -> {
                val (seriesName, epNum) = seriesRegex.find(title)!!.destructured
                if (details) "$seriesName (ep:$epNum)" else seriesName
            }
            else -> title
        }.trim()
    }

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "DoodStream", "Uqload")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "Dood", "Uqload")
            setDefaultValue("1080")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue.toString()).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
