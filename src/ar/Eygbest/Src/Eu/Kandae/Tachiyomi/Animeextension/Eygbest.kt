package eu.kanade.tachiyomi.animeextension.ar.egybest

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
    override val baseUrl = "https://www.egymom.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val id: Long = 987654321098765432

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    // Popular
    override fun popularAnimeSelector(): String = "div.movieItem"
    override fun popularAnimeNextPageSelector(): String = "a.next"
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")
    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a img").attr("alt")
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // Episodes
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("ul.episodes-list li a").map {
            SEpisode.create().apply {
                name = it.text()
                setUrlWithoutDomain(it.attr("href"))
            }
        }
    }
    override fun episodeListSelector() = "ul.episodes-list li a"
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create()

    // Videos
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val urlResolver by lazy { UrlResolver(client) }

    override fun videoListParse(response: Response): List<Video> {
        val requestBody = FormBody.Builder().add("View", "1").build()
        val newHeaders = headers.newBuilder().add("referer", "$baseUrl/").build()
        val newResponse = client.newCall(
            POST(response.request.url.toString(), headers = newHeaders, body = requestBody)
        ).execute().asJsoup()
        return newResponse.select("ul.servers-list li").parallelCatchingFlatMapBlocking { element ->
            val url = element.attr("data-link")
            when {
                "dood" in url -> doodExtractor.videosFromUrl(url)
                "mixdrop" in url -> mixDropExtractor.videosFromUrl(url)
                "uqload" in url -> uqloadExtractor.videosFromUrl(url)
                else -> streamWishExtractor.videosFromUrl(url)
            }
        }
    }
    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): Video = throw UnsupportedOperationException()
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search/?q=$query&page=$page", headers)
    }
    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // Details
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.thumb img").attr("src")
        anime.title = document.select("div.details h1").text()
        anime.genre = document.select("div.details li:contains(Genre) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.description").text()
        anime.status = if ("Completed" in anime.title) SAnime.COMPLETED else SAnime.ONGOING
        return anime
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest?page=$page")
    override fun latestUpdatesSelector(): String = "div.latest-movie"
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = "a.next"

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = entries.map { it.removeSuffix("p") }.toTypedArray()
            setDefaultValue("1080")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }
}
