package com.cinemahome.movizland

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

// MovizLand (WordPress). Each episode is its own post; a series page (/series/<name>-الموسم-N/) lists one season's
// episodes, and every episode page links to its season pages. Videos sit on outside hosts listed on <post>/watch/.
class MovizLandProvider : MainAPI() {
    override var mainUrl = "https://www.moviz.land"
    override var name = "MovizLand"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        IRAQI to "مسلسلات عراقية",
        "series/مسلسلات-رمضان-2026/" to "رمضان 2026",
        "series/مسلسلات-عربية/" to "مسلسلات عربية",
        "مسلسلات-مصرية/" to "مسلسلات مصرية",
        "series/مسلسلات-تركية-مترجمة/" to "تركي مترجم",
        "series/مسلسلات-تركية-مدبلجة/" to "تركي مدبلج",
        "series/مسلسلات-كوري/" to "مسلسلات كورية",
        "series/مسلسلات-اسيوية/" to "مسلسلات آسيوية",
        "movies/افلام-عربي/" to "أفلام عربية",
        "movies/افلام-تركية/" to "أفلام تركية",
        "movies/أفلام-كوري/" to "أفلام كورية",
        "movies/افلام-اسيوي/" to "أفلام آسيوية",
        "movies/أفلام-أجنبي-مترجمه/" to "أفلام أجنبية",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == IRAQI) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val items = IRAQI_TITLES.amap { search(it).firstOrNull() }.filterNotNull().distinctBy { it.name }
            return newHomePageResponse(request.name, items, false)
        }
        val url = "$mainUrl/category/${request.data}" + if (page > 1) "page/$page/" else ""
        val items = app.get(url).document.select("article[data-url][data-title]").mapNotNull {
            card(it.attr("data-title"), it.attr("data-url"), it.attr("data-image"))
        }.distinctBy { it.name }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    // The site's own search finds little; WordPress's API searches every post.
    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get(
            "$mainUrl/wp-json/wp/v2/posts",
            params = mapOf("search" to query, "per_page" to "60", "_embed" to "wp:featuredmedia")
        ).text
        return parseJson<List<WpPost>>(json).mapNotNull { post ->
            val title = Jsoup.parse(post.title?.rendered ?: return@mapNotNull null).text()
            card(title, post.link ?: return@mapNotNull null, post.embedded?.media?.firstOrNull()?.sourceUrl)
        }.distinctBy { it.name }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val pageTitle = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { doc.title() }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.replace(Regex("^حصريا علي MovizLand مشاهدة وتحميل\\s*"), "")
        val seasonPages = if (url.contains("/series/")) listOf(url) else doc.select("a[href*=/series/]")
            .filter { it.text().contains("الموسم") }
            .map { it.attr("abs:href") }
            .filter { Regex("/series/[^/]+/?$").containsMatchIn(it) }
            .distinct()

        if (seasonPages.isEmpty() && !pageTitle.contains("الحلقة")) {
            return newMovieLoadResponse(cleanTitle(pageTitle), url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
            }
        }

        val episodes = mutableListOf<Episode>()
        if (seasonPages.isEmpty()) {
            episodes += newEpisode(url) { name = pageTitle; episode = episodeNumber(pageTitle); season = 1 }
        }
        seasonPages.amap { seasonUrl ->
            val season = Regex("الموسم-(\\d+)").find(URLDecoder.decode(seasonUrl, "UTF-8"))
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            app.get(seasonUrl).document.select("a.episode-card").map { a ->
                val number = a.selectFirst(".episode-number")?.text()?.trim()?.toIntOrNull()
                newEpisode(a.attr("abs:href")) {
                    this.season = season
                    episode = number
                    name = number?.let { "الحلقة $it" }
                }
            }
        }.forEach { episodes += it }

        return newTvSeriesLoadResponse(
            cleanTitle(pageTitle), url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watch = data.trimEnd('/') + "/watch/"
        val servers = app.get(watch, referer = data).document.select("button[data-src]")
            .map { it.attr("data-src").trim() }
            .filter { it.startsWith("http") }
            .distinct()
        servers.amap { src ->
            val known = runCatching { loadExtractor(src, watch, subtitleCallback, callback) }.getOrDefault(false)
            if (!known) runCatching { packedPlayer(src, watch, callback) }
        }
        return servers.isNotEmpty()
    }

    private fun card(title: String, url: String, image: String?): SearchResponse? {
        if (title.isBlank() || url.isBlank()) return null
        val name = cleanTitle(title)
        return if (title.contains("فيلم") && !title.contains("الحلقة")) {
            newMovieSearchResponse(name, url, TvType.Movie) { posterUrl = image }
        } else {
            newTvSeriesSearchResponse(name, url, TvType.TvSeries) { posterUrl = image }
        }
    }

    data class WpPost(
        @JsonProperty("title") val title: Rendered? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("_embedded") val embedded: Embedded? = null,
    )

    data class Rendered(@JsonProperty("rendered") val rendered: String? = null)
    data class Embedded(@JsonProperty("wp:featuredmedia") val media: List<Media>? = null)
    data class Media(@JsonProperty("source_url") val sourceUrl: String? = null)

    companion object {
        private const val IRAQI = "iraqi"

        // The site has no Iraqi section, so this row is made from names. Add titles here as they come out.
        val IRAQI_TITLES = listOf("ليل البنفسج")

        fun cleanTitle(title: String): String {
            var s = title.replace("مشاهدة", " ").trim()
            s = s.replace(Regex("\\s+الحلقة.*$"), "")
            s = s.replace(Regex("\\s+(مترجم|مترجمة|مدبلج|مدبلجة|كامل|اون لاين|أون لاين|HD|بجودة).*$"), "")
            s = s.replace(Regex("\\s+-?\\s*الموسم\\s+.*$"), "")
            s = s.replace(Regex("^(مسلسل|فيلم)\\s+"), "")
            return s.trim().ifEmpty { title.trim() }
        }

        fun episodeNumber(title: String): Int? = Regex("الحلقة\\s+(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Hosts built on the same player (hdup400, film77, 1vid, vidoba, mp4plus...) hide the file in a packed script.
        suspend fun packedPlayer(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
            val page = app.get(url, referer = referer).text
            val body = getAndUnpack(page)
            val media = Regex("""https?://[^"'\s]+?\.(m3u8|mp4)[^"'\s]*""").find(body)?.value
                ?: Regex("""https?://[^"'\s]+?\.(m3u8|mp4)[^"'\s]*""").find(page)?.value
                ?: return
            val host = URI(url).host.removePrefix("www.")
            callback(
                newExtractorLink(host, host, media, if (media.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                }
            )
        }
    }
}
