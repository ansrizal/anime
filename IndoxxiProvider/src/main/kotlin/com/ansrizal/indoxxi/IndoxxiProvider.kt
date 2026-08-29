package com.ansrizal.indoxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse

class IndoxxiProvider : MainAPI() {
    override var mainUrl = "https://154.203.167.230"
    override var name = "INDOXXI"
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
        // Try with interceptor first
        val res = try {
            app.get(
                url,
                headers = headers,
                interceptor = turnstileInterceptor,
                timeout = 30
            )
        } catch (e: Exception) {
            // Fallback to normal request if interceptor fails
            app.get(
                url,
                headers = headers,
                timeout = 20
            )
        }
        return res
    }

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "movies/" to "Film Terbaru",
        "tv-series/" to "TV Series",
        "genre/action/" to "Action",
        "genre/horror/" to "Horror",
        "trending/" to "Trending"
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
        // Very broad selection for movie items
        val items = document.select("div.listupd article, div.bs, div.bsx, div.ml-item, div.item, article.item, div.grid-item")
        val homeItems = items.mapNotNull {
            it.toSearchResult()
        }
        
        // Secondary fallback if main selectors fail
        if (homeItems.isEmpty()) {
            val fallbackItems = document.select("a[href*='/movies/'], a[href*='/tv-series/']").asIterable().mapNotNull { a ->
                val title = a.attr("title").ifBlank { a.text() }
                val href = a.attr("href")
                if (title.length < 3 || href.contains("/genre/") || href.contains("/category/")) return@mapNotNull null
                
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie)
            }.distinctBy { it.url }.take(20)
            if (fallbackItems.isNotEmpty()) return newHomePageResponse(request.name, fallbackItems)
        }

        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Find link with title
        val titleElement = this.selectFirst("h2, h3, h4, .tt, .title, a[title]")
        val title = titleElement?.text()?.trim() 
            ?: this.selectFirst("a")?.attr("title")?.trim()
            ?: return null
            
        if (title.isEmpty()) return null
            
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Filter out non-content links
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/genre/") || href.contains("/category/") || href.contains("/tag/")) return null

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

        return document.select("div.listupd article, div.bs, div.bsx, div.ml-item, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title, h1, .title, .name")?.text()?.trim() ?: "INDOXXI"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("div.thumb img, img.wp-post-image, .poster img")?.attr("src"))
        val description = document.selectFirst("div.entry-content, div.synopsis, [itemprop=description], .description")?.text()?.trim()

        val isSeries = url.contains("/tv-series/") || url.contains("/series/") || url.contains("/tv/")

        return if (isSeries) {
            val episodes = document.select("ul.episodios li, div.list-episode li, .eplister li, .listeps li, div.eps-item").mapNotNull { elem ->
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
            if (src.isNotBlank() && !src.contains("facebook.com")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }
        
        document.select("ul.muvi-player-list li, div.source-box li, .player-source, .mirror-item").asIterable().forEach { li ->
            val serverSrc = li.selectFirst("a")?.attr("href") ?: li.attr("data-src") ?: li.attr("data-href") ?: ""
            if (serverSrc.startsWith("http") || serverSrc.startsWith("//")) {
                loadExtractor(fixUrl(serverSrc), subtitleCallback, callback)
            }
        }

        return true
    }
}
