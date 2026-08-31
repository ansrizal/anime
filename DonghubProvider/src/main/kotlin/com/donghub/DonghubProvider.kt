package com.donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class DonghubProvider : MainAPI() {
    override var mainUrl = "https://donghub.vip/"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/" to "Latest Releases",
        "status/ongoing/" to "Series Ongoing",
        "status/completed/" to "Series Completed",
        "type/movie/" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = if (page <= 1) {
            "$mainUrl$path/"
        } else {
            // Jika path mengandung '?' berarti sudah ada parameter query
            if (path.contains('?')) {
                "$mainUrl$path&page=$page"
            } else {
                "$mainUrl$path/page/$page/"
            }
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")).document
        // Selector mencakup article.bs dan article.styleegg (sesuai HTML)
        val items = document.select("article.bs, article.styleegg").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty(),
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("a[title]")?.attr("title")?.trim()
            ?: selectFirst(".tt, h2, h3, .title")?.text()?.trim()
            ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("img")?.getsrcAttribute())

        // Tentukan tipe dari elemen .typez
        val typeEl = selectFirst(".typez")
        val isMovie = typeEl?.text()?.contains("Movie", ignoreCase = true) == true

        return newAnimeSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val url = "$mainUrl/page/$i/?s=$query"
            val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")).document
            val result = document.select("article.bs, article.styleegg").mapNotNull { it.toSearchResult() }
            if (result.isEmpty()) break
            list.addAll(result)
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")).document
        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", ignoreCase = true)

        val poster = document.select("div.ime > img").first()?.getsrcAttribute()
            ?: document.select("meta[property=og:image]").attr("content")

        val epBlocks =
            document.select(".eplister li").ifEmpty {
                document.select("div.list-episode .episode-item")
            }.ifEmpty {
                document.select("#episodes a")
            }

        return if (!isMovie && epBlocks.isNotEmpty()) {
            val episodes = epBlocks.map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text() ?: ep.text()
                newEpisode(link) {
                    this.name = epTitle.trim()
                    this.posterUrl = fixUrlNull(poster)
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            // Movie atau tidak ada episode
            val movieLink = document.selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieLink, TvType.Movie, movieLink) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")).document

        // Ambil dari option yang di-base64
        document.select(".mobius option").asIterable().forEach { item ->
            val base64 = item.attr("value")
            if (base64.isNotBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val doc = Jsoup.parse(decoded)
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(fixUrl(iframe), subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
        }

        // Fallback: iframe langsung di player
        document.select("div.player-embed iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getsrcAttribute(): String {
        val src = this.attr("src")
        val dataSrc = this.attr("data-src")
        return when {
            dataSrc.startsWith("http") -> dataSrc
            src.startsWith("http") -> src
            else -> ""
        }
    }
}
