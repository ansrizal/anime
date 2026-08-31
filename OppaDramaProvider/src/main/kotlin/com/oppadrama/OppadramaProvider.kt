package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class OppadramaProvider : MainAPI() {
    override var mainUrl = "http://oppa.biz/"
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

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "country/south-korea/" to "Drama Korea",
        "country/china/" to "Drama Chinese",
        "country/japan/" to "Drama Jepang",
        "country/thailand/" to "Drama Thailand",
        "movies/" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            page <= 1 -> if (path.isEmpty()) mainUrl else "$mainUrl$path/"
            else -> "$mainUrl$path/page/$page/"
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = app.get(url).document
        val items = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        // fallback jika selector utama gagal
        val finalItems = if (items.isEmpty()) {
            document.select("article.bs, div.bs, div.bsx, div.ml-item").mapNotNull { it.toSearchResult() }
        } else items

        return newHomePageResponse(HomePageList(request.name, finalItems), hasNext = finalItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))

        // Ambil judul dari atribut title, lalu dari div.tt atau div.tts, terakhir h2
        var title = link.attr("title").ifBlank {
            this.selectFirst("div.tt, div.tts")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            title = this.selectFirst("h2")?.text()?.trim()
        }
        if (title.isNullOrBlank()) return null

        val img = this.selectFirst("img")
        val poster = img?.let {
            it.attr("abs:data-src").ifBlank { 
                it.attr("abs:src").ifBlank { 
                    it.getImageAttr()
                }
            }
        }?.let { fixUrlNull(it) }

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
        val url = "$mainUrl?s=$query"
        val document = app.get(url, timeout = 50000L).document
        val items = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        return if (items.isNotEmpty()) items else {
            document.select("article.bs, div.bs, div.bsx").mapNotNull { it.toSearchResult() }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val title = this.selectFirst("div.tt")?.text()?.trim() ?: return null
        val href = fixUrl(link.attr("href"))
        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
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
