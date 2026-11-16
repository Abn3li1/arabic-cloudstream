package com.cinemahome.brstej

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

// Brstej (برستيج). Episodes are video.php?vid=<id>; a series page (series1.php?id=<id>) lists every season's episodes;
// play.php?vid=<id> lists the servers as buttons with data-embed-url.
class BrstejProvider : MainAPI() {
    override var mainUrl = "https://brstej1.com"
    override var name = "برستيج"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        IRAQI to "مسلسلات عراقية",
        ALL_SERIES to "كل المسلسلات",
        "ramdan2026" to "رمضان 2026",
        "arab8-2025" to "مسلسلات عربية",
        "5a7-2024" to "مسلسلات خليجية",
        "syy5-2025" to "مسلسلات شامية",
        "eg8-2025" to "مسلسلات مصرية",
        "ty9-2025" to "مسلسلات تركية",
        "asia" to "مسلسلات آسيوية وكورية",
        "prss7-2025" to "مسلسلات برستيج",
        "tv4-2024" to "برامج",
        "aflam02-2024" to "أفلام عربية",
        "turkish3-movies2024" to "أفلام تركية",
        "aflamajnby3-2024" to "أفلام أجنبية",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == IRAQI) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val items = IRAQI_TITLES.amap { search(it).firstOrNull() }.filterNotNull().distinctBy { it.name }
            return newHomePageResponse(request.name, items, false)
        }
        if (request.data == ALL_SERIES) {
            val items = seriesCards(app.get("$mainUrl/moslslat.php?page=$page").document)
            return newHomePageResponse(request.name, items, items.isNotEmpty())
        }
        // A section page holds ~38 episodes of only 5-7 series, so each row step reads three pages.
        val items = (3 * page - 2..3 * page).toList().amap { p ->
            runCatching { cards(app.get("$mainUrl/cat03.php?cat=${request.data}&page=$p").document.select("a[href*=video.php]")) }
                .getOrDefault(emptyList())
        }.flatten().distinctBy { it.name }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    // Series first, from the series list's own search (moslslat.php?q=, which matches the full name). Then films and
    // episodes from search.php; that one wants the exact words it stored ("ليل البنفسج" finds nothing, "البنفسج" does),
    // so when the whole phrase finds nothing, each word is tried from the longest down.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val series = runCatching { seriesCards(app.get("$mainUrl/moslslat.php", params = mapOf("q" to q)).document) }
            .getOrDefault(emptyList())
        var others = emptyList<SearchResponse>()
        val tries = listOf(q) + q.split(Regex("\\s+")).filter { it.length > 2 }.sortedByDescending { it.length }
        for (t in tries.distinct()) {
            others = runCatching { cards(app.get("$mainUrl/search.php", params = mapOf("keywords" to t)).document.select("a[href*=video.php]")) }
                .getOrDefault(emptyList())
            if (others.isNotEmpty()) break
        }
        val words = q.split(Regex("\\s+"))
        val seriesNames = series.map { it.name }.toSet()
        return series + others.filter { it.name !in seriesNames }.sortedByDescending { r -> words.count { r.name.contains(it) } }
    }

    private fun seriesCards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.psd-card").mapNotNull { card ->
            val a = card.selectFirst("h3 a[href*=series1.php]") ?: return@mapNotNull null
            val name = cleanTitle(a.text())
            newTvSeriesSearchResponse(name, a.attr("abs:href"), TvType.TvSeries) {
                posterUrl = card.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.name }

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl: String?
        val episodeDoc = if (url.contains("series1.php")) null else app.get(url).document
        if (episodeDoc == null) {
            seriesUrl = url
        } else {
            val title = episodeDoc.selectFirst("h1")?.text().orEmpty().ifEmpty { episodeDoc.title() }
            seriesUrl = episodeDoc.select("a[href*=series1.php]")
                .firstOrNull { it.text().isNotBlank() && title.contains(it.text().trim()) }
                ?.attr("abs:href")
            if (seriesUrl == null) {
                val poster = episodeDoc.selectFirst("meta[property=og:image]")?.attr("content")
                val plot = episodeDoc.selectFirst("meta[name=description]")?.attr("content")
                if (!title.contains("الحلقة")) {
                    return newMovieLoadResponse(cleanTitle(title), url, TvType.Movie, url) {
                        posterUrl = poster
                        this.plot = plot
                    }
                }
                return newTvSeriesLoadResponse(cleanTitle(title), url, TvType.TvSeries,
                    listOf(newEpisode(url) { name = title; episode = episodeNumber(title); season = 1 })) {
                    posterUrl = poster
                    this.plot = plot
                }
            }
        }

        val doc = app.get(seriesUrl).document
        val name = cleanTitle(doc.selectFirst("h1")?.text().orEmpty().ifEmpty { doc.title() })
        val episodes = doc.select(".pds-caption").mapNotNull { caption ->
            val a = caption.selectFirst("a[href*=video.php]") ?: return@mapNotNull null
            val label = a.attr("title").ifEmpty { a.text() }
            val season = caption.selectFirst("span")?.text()?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() } ?: 1
            val number = Regex("(\\d+)").find(a.text())?.value?.toIntOrNull() ?: episodeNumber(label)
            newEpisode(a.attr("abs:href")) {
                this.season = season
                episode = number
                this.name = number?.let { "الحلقة $it" } ?: label
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(name, seriesUrl, TvType.TvSeries, episodes) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val play = data.replace("video.php", "play.php")
        val servers = app.get(play, referer = data).document.select("[data-embed-url]")
            .map { it.attr("data-embed-url").trim() }
            .filter { it.startsWith("http") }
            .distinct()
        servers.amap { src ->
            val known = runCatching { loadExtractor(src, play, subtitleCallback, callback) }.getOrDefault(false)
            if (!known) runCatching { packedPlayer(src, play, callback) }
        }
        return servers.isNotEmpty()
    }

    // Section pages put the name in the link's title; search results put it in the link text, with the picture in a
    // separate link of the same card.
    private fun cards(links: List<Element>): List<SearchResponse> = links.mapNotNull { a ->
        val title = a.attr("title").trim().ifEmpty { a.text().trim() }
        if (title.isEmpty() || !title.contains(Regex("[\\p{L}]{2}"))) return@mapNotNull null
        val image = (a.selectFirst("img") ?: a.closest("article, .thumbnail, li")?.selectFirst("img"))?.attr("abs:src")
        val name = cleanTitle(title)
        if (title.contains("فيلم") && !title.contains("الحلقة")) {
            newMovieSearchResponse(name, a.attr("abs:href"), TvType.Movie) { posterUrl = image }
        } else {
            newTvSeriesSearchResponse(name, a.attr("abs:href"), TvType.TvSeries) { posterUrl = image }
        }
    }.distinctBy { it.name }

    companion object {
        private const val IRAQI = "iraqi"
        private const val ALL_SERIES = "all-series"

        // The site has no Iraqi section, so this row is made from names. Add titles here as they come out.
        val IRAQI_TITLES = listOf("ليل البنفسج")

        fun cleanTitle(title: String): String {
            var s = title.replace("مشاهدة", " ").trim()
            s = s.replace(Regex("\\s+الحلقة.*$"), "")
            s = s.replace(Regex("\\s+(مترجم|مترجمة|مدبلج|مدبلجة|كامل|اون لاين|أون لاين|HD|بجودة).*$"), "")
            s = s.replace(Regex("^(مسلسل|فيلم|برنامج)\\s+"), "")
            return s.trim().ifEmpty { title.trim() }
        }

        fun episodeNumber(title: String): Int? = Regex("الحلقة\\s+(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Hosts built on the same player (hdup400, film77, hd-vk...) hide the file in a packed script.
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
