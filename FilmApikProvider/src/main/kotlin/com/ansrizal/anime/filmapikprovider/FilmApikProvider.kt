package com.ansrizal.anime.filmapikprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse

class FilmApikProvider : MainAPI() {
    override var mainUrl = "https://filmapik.college"
    override var name = "FilmApik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val turnstileInterceptor = TurnstileInterceptor("cf_clearance")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend fun request(url: String): NiceResponse {
        return app.get(
            url,
            headers = headers,
            interceptor = turnstileInterceptor,
            timeout = 60
        )
    }

    override val mainPage = mainPageOf(
        "" to "Film Terbaru",
        "genre/action/" to "Action",
        "genre/comedy/" to "Comedy",
        "genre/drama/" to "Drama",
        "genre/horror/" to "Horror",
        "genre/science-fiction/" to "Sci-Fi",
        "trending/" to "Trending",
        "ratings/" to "Rating Terbaik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            val data = request.data.removeSuffix("/")
            if (data.isEmpty()) {
                "$mainUrl/page/$page/"
            } else {
                "$mainUrl/$data/page/$page/"
            }
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = request(url).document
        val homeItems = document.select("div.ml-item, div.item, article, div.bs, div.bsx, div.uta").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, h4, .tt, .title, a[title]")?.text()?.trim() 
            ?: this.selectFirst("a")?.attr("title")?.trim()
            ?: return null
        
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/genre/")) return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("abs:data-src")
            ?: img?.attr("abs:data-lazy-src")
            ?: img?.attr("abs:src")
            ?: img?.attr("src")
        )

        val isSeries = href.contains("/tv-series/") || href.contains("/series/") || href.contains("/tv/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = request(searchUrl).document

        return document.select("div.ml-item, div.item, article, div.bs, div.bsx, div.uta").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title, h1, .title, .name")?.text()?.trim() ?: "FilmApik"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("div.thumb img, img.wp-post-image, .poster img")?.attr("src"))
        val description = document.selectFirst("div.entry-content, div.synopsis, [itemprop=description], .description")?.text()?.trim()

        val isSeries = url.contains("/tv-series/") || url.contains("/series/") || url.contains("/tv/")

        return if (isSeries) {
            val episodes = document.select("ul.episodios li, div.list-episode li, .eplister li, .listeps li").mapNotNull { elem ->
                val a = elem.selectFirst("a") ?: return@mapNotNull null
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.text().trim()
                val epNum = elem.selectFirst(".numerando, .epl-num, .eps")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
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
        val document = request(data).document

        document.select("iframe").asIterable().forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank()) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }
        
        // Check mirror links and player sources
        document.select("ul.muvi-player-list li, div.source-box li, .player-source").asIterable().forEach { li ->
            val serverSrc = li.selectFirst("a")?.attr("href") ?: li.attr("data-src") ?: ""
            if (serverSrc.startsWith("http") || serverSrc.startsWith("//")) {
                loadExtractor(fixUrl(serverSrc), subtitleCallback, callback)
            }
        }

        return true
    }
}
