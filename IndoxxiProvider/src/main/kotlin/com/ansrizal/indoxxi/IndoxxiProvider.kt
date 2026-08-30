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
        return try {
            app.get(
                url,
                headers = headers,
                interceptor = turnstileInterceptor,
                timeout = 30
            )
        } catch (e: Exception) {
            app.get(
                url,
                headers = headers,
                timeout = 20
            )
        }
    }

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "category/movie/" to "Film Terbaru",
        "category/tv-series/" to "TV Series",
        "genre/action/" to "Action",
        "genre/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val formattedPath = if (path.isEmpty()) "" else if (path.endsWith("/")) path else "$path/"
        
        val url = if (page <= 1) {
            "$mainUrl/$formattedPath"
        } else {
            "$mainUrl/${formattedPath}page/$page/"
        }

        val document = request(url).document
        val items = document.select("div.ml-item, div.item, article.item, div.post-item, div.bs, div.bsx, .archive-container article")
        
        val homeItems = items.mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = homeItems
                )
            ),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        val cleanMainUrl = mainUrl.removeSuffix("/")
        val cleanHref = href.removeSuffix("/")
        
        if (cleanHref == cleanMainUrl || 
            href.contains("/genre/") || 
            href.contains("/category/") || 
            href.contains("/page/") ||
            href.contains("/tag/")) return null

        val titleElement = this.selectFirst("h2, h3, h4, .tt, .title, .mli-info h2")
        val title = titleElement?.text()?.trim() 
            ?: linkElement.attr("title").trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
        if (title.isEmpty() || title.length < 2) return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("srcset")?.substringBefore(" ")
            ?: img?.attr("src")
            ?: this.selectFirst(".lazy")?.attr("data-original")
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

        return document.select("div.ml-item, div.item, article.item, div.post-item, div.bs, div.bsx").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title, h1.name, h1, .title")?.text()?.trim() ?: "INDOXXI"
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: document.selectFirst("div.thumb img, img.wp-post-image, .poster img")?.attr("src")
        )
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

        val iframes = document.select("iframe")
        for (iframe in iframes) {
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook.com") && !src.contains("twitter.com")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        val playerElements = document.select("ul.muvi-player-list li, div.source-box li, .player-source, .mirror-item, option[value*='http'], .bonnette, [data-link]")
        for (elem in playerElements) {
            val serverSrc = elem.selectFirst("a")?.attr("href") 
                ?: elem.attr("data-src") 
                ?: elem.attr("data-href") 
                ?: elem.attr("data-link")
                ?: elem.attr("value")
            
            if (!serverSrc.isNullOrBlank() && (serverSrc.startsWith("http") || serverSrc.startsWith("//"))) {
                loadExtractor(fixUrl(serverSrc), subtitleCallback, callback)
            }
        }

        val videoSources = document.select("video source, video")
        for (video in videoSources) {
            val videoUrl = video.attr("src")
            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = fixUrl(videoUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
