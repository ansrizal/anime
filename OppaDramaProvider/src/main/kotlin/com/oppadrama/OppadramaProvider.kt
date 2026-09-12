package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class OppadramaProvider : MainAPI() {

    // ====================================================================
    //  KONFIGURASI SERVER
    //  Cek IP terbaru dengan: curl -sSI "https://oppa.biz/" | findstr /I "Location"
    // ====================================================================
    private val SERVER_IP = "45.11.57.188"
    private val HOST_HEADER = "oppa.biz"
    // ====================================================================

    override var mainUrl = "http://$SERVER_IP/"
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Header untuk semua request (TANPA Host — Host di-set oleh interceptor)
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to "http://$HOST_HEADER/",
    )

    // OkHttpClient kita sendiri, dengan interceptor yang memaksa Host: oppa.biz
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val newReq = if (req.url.host == SERVER_IP) {
                req.newBuilder().header("Host", HOST_HEADER).build()
            } else {
                req
            }
            chain.proceed(newReq)
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Helper: fetch HTML via OkHttpClient kita sendiri dan parse dengan Jsoup. */
    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        defaultHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            println("[OppaDrama] ${response.code} len=${body.length} url=$url")
            Jsoup.parse(body, url)
        }
    }

    companion object {
        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "Drama Chinese",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Drama Thailand",
        "series/?type=Movie&order=update" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            path.isEmpty() -> if (page <= 1) mainUrl else "${mainUrl}page/$page/"
            path.contains('?') -> "$mainUrl$path&page=$page"
            else -> if (page <= 1) "$mainUrl$path/" else "$mainUrl$path/page/$page/"
        }

        val document = fetchDocument(url)
        var items = document.select(".listupd article.bs").mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) {
            items = document.select("article.bs").mapNotNull { it.toSearchResult() }
        }
        val hasNext = document.selectFirst("div.hpage a.r") != null
        println("[OppaDrama] getMainPage items=${items.size} hasNext=$hasNext")
        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        var title = link.attr("title").trim()
        if (title.isBlank()) {
            title = this.selectFirst(".tt")?.text()?.trim()
                ?: this.selectFirst(".tts")?.text()?.trim()
                ?: this.selectFirst("h2")?.text()?.trim() ?: return null
        }
        val img = this.selectFirst("img")
        val poster = img?.let {
            it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
        }?.let { fixUrlNull(it) }

        val typeElement = this.selectFirst(".typez")
        val isMovie = typeElement?.text()?.equals("Movie", ignoreCase = true) == true

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}?s=$query"
        val document = fetchDocument(url)
        var items = document.select(".listupd article.bs").mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) {
            items = document.select("article.bs").mapNotNull { it.toSearchResult() }
        }
        return items
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val title = this.selectFirst(".tt")?.text()?.trim() ?: return null
        val href = fixUrl(link.attr("href"))
        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url)

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }
        val description = document.select("div.entry-content p")
            .joinToString("\n") { it.text() }.trim()

        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (h * 60) + m
        }

        val tags = document.select("div.genxed a").map { it.text() }
        val actors = document.select("span:has(b:matchesOwn(Artis:)) a").map { it.text().trim() }
        val rating = document.selectFirst("div.rating strong")
            ?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val status = getStatus(
            document.selectFirst("div.info-content div.spe span")
                ?.ownText()?.replace(":", "")?.trim() ?: ""
        )
        val recommendations = document.select("article.bs").mapNotNull { it.toRecommendResult() }

        val episodeElements = document.select("div.eplister ul li a")
        val episodes = episodeElements.reversed().mapIndexed { index, aTag ->
            val href = fixUrl(aTag.attr("href"))
            newEpisode(href) {
                this.name = "Episode ${index + 1}"
                this.episode = index + 1
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                trailer?.takeIf { it.isNotBlank() }?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                trailer?.takeIf { it.isNotBlank() }?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(data)

        document.selectFirst("div.player-embed iframe")?.getIframeAttr()?.let { iframe ->
            loadExtractor(httpsify(iframe), data, subtitleCallback, callback)
        }

        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (opt in mirrorOptions) {
            val base64 = opt.attr("value")
            if (base64.isBlank()) continue
            try {
                val cleaned = base64.replace("\\s".toRegex(), "")
                val decodedHtml = base64Decode(cleaned)
                val iframeTag = Jsoup.parse(decodedHtml).selectFirst("iframe")
                val mirrorUrl = when {
                    iframeTag?.attr("src")?.isNotBlank() == true -> iframeTag.attr("src")
                    iframeTag?.attr("data-src")?.isNotBlank() == true -> iframeTag.attr("data-src")
                    else -> null
                }
                if (!mirrorUrl.isNullOrBlank()) {
                    loadExtractor(httpsify(mirrorUrl), data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        val downloadLinks = document.select("div.dlbox li span.e a[href]")
        for (a in downloadLinks) {
            val url = a.attr("href").trim()
            if (url.isNotBlank()) {
                loadExtractor(httpsify(url), data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }
}
