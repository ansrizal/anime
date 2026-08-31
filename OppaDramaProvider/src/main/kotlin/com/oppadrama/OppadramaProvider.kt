package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class OppadramaProvider : MainAPI() {
    // Gunakan IP atau domain tanpa trailing slash, sama seperti pembanding
    override var mainUrl = "http://45.11.57.188"  // bisa ganti dengan "http://oppa.biz" jika berhasil
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    // Path menggunakan query parameter seperti pembanding
    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "Drama Chinese",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Drama Thailand",
        "series/?country%5B%5D=usa&type=Drama&order=update" to "Drama Western",
        "series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "series/?country%5B%5D=china&status=&type=Movie&order=update" to "Chinese Movie",
        "series/?country%5B%5D=japan&type=Movie&order=update" to "Japan Movie",
        "series/?country%5B%5D=thailand&status=&type=Movie&order=update" to "Thailand Movie",
        "series/?country%5B%5D=united-states&status=&type=Movie&order=update" to "Western Movie",
        "series/?country%5B%5D=philippines&status=&type=Movie&order=update" to "Philippines Movie",
        "series/?country%5B%5D=taiwan&status=&type=Movie&order=update" to "Taiwan Movie",
        "series/?country%5B%5D=hong-kong&status=&type=Movie&order=update" to "Hong Kong Movie",
        "series/?country%5B%5D=india&status=&type=Movie&order=update" to "India Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Bangun URL dengan parameter page seperti pembanding
        val url = "$mainUrl/${request.data}".plus("&page=$page")
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
                            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        } ?: return null
        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        // Tentukan tipe dari elemen .typez (lebih akurat)
        val typeElement = this.selectFirst("div.typez")
        val isMovie = typeElement?.text()?.equals("Movie", ignoreCase = true) == true

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50000L).document
        val results = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
        return results
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("div.tt")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }

        val description = document.select("div.entry-content p")
            .joinToString("\n") { it.text() }
            .trim()

        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (h * 60) + m
        }

        val tags = document.select("div.genxed a").map { it.text() }

        val actors = document.select("span:has(b:matchesOwn(Artis:)) a")
            .map { it.text().trim() }

        val rating = document.selectFirst("div.rating strong")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()
            ?.toDoubleOrNull()

        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")

        val status = getStatus(
            document.selectFirst("div.info-content div.spe span")
                ?.ownText()
                ?.replace(":", "")
                ?.trim()
                ?: ""
        )

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendResult() }

        val episodeElements = document.select("div.eplister ul li a")

        val episodes = episodeElements
            .reversed()
            .mapIndexed { index, aTag ->
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
                addTrailer(trailer)
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
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.selectFirst("div.player-embed iframe")
            ?.getIframeAttr()
            ?.let { iframe ->
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
            } catch (_: Exception) {
                // ignore broken mirrors
            }
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
