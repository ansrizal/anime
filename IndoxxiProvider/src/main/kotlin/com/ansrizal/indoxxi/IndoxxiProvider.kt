package com.ansrizal.indoxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup

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
        "X-Requested-With" to "XMLHttpRequest"
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
        val items = document.select("div.ml-item, div.item, article.item, div.post-item, div.bs, div.bsx, .archive-container article, .poster-container")
        
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

        val titleElement = this.selectFirst("h2, h3, h4, .tt, .title, .mli-info h2, .entry-title")
        val title = titleElement?.text()?.trim() 
            ?: linkElement.attr("title").trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
        if (title.isEmpty() || title.length < 2) return null

        // Perbaikan komprehensif penarikan gambar poster
        val img = this.selectFirst("img")
        var rawImgUrl = img?.attr("data-src")
            ?.ifEmpty { null }
            ?: img?.attr("data-lazy-src")
            ?.ifEmpty { null }
            ?: img?.attr("data-original")
            ?.ifEmpty { null }
            ?: img?.attr("srcset")?.substringBefore(" ")
            ?.ifEmpty { null }
            ?: img?.attr("src")

        if (rawImgUrl != null && rawImgUrl.startsWith("//")) {
            rawImgUrl = "https:$rawImgUrl"
        }
        val posterUrl = fixUrlNull(rawImgUrl)

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
        var rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: document.selectFirst("div.thumb img, img.wp-post-image, .poster img")?.attr("src")
        
        if (rawPoster != null && rawPoster.startsWith("//")) {
            rawPoster = "https:$rawPoster"
        }
        val poster = fixUrlNull(rawPoster)
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
        val res = request(data)
        val document = res.document

        // 1. Ekstraksi langsung dari seluruh iframe yang ada di halaman
        val iframes = document.select("iframe")
        for (iframe in iframes) {
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook.com") && !src.contains("twitter.com")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 2. Scan regex untuk mendeteksi iframe / URL embed yang disembunyikan dalam skrip JS
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        val embeddedUrls = Regex("""(?:iframe|src)\s*[:=]\s*["']([^"']+)["']""").findAll(scriptContent)
        for (match in embeddedUrls) {
            var extractedUrl = match.groupValues[1]
            if (extractedUrl.startsWith("//")) extractedUrl = "https:$extractedUrl"
            if (extractedUrl.startsWith("http") && !extractedUrl.contains("facebook") && !extractedUrl.contains("google")) {
                loadExtractor(extractedUrl, subtitleCallback, callback)
            }
        }

        // 3. Ekstraksi AJAX Player (WordPress/Gdriveplayer server player switcher)
        val postData = document.selectFirst("input[name=id], input[id=post_id], #player-option-1")?.attr("value")
            ?: document.selectFirst("[data-post]")?.attr("data-post")

        if (!postData.isNullOrEmpty()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            try {
                val ajaxRes = app.post(
                    ajaxUrl,
                    headers = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                    data = mapOf("action" to "player_ajax", "post" to postData, "type" to "movie")
                ).text

                val parsedIframe = Jsoup.parse(ajaxRes).selectFirst("iframe")?.attr("src")
                if (!parsedIframe.isNullOrEmpty()) {
                    var cleanUrl = parsedIframe
                    if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
                    loadExtractor(cleanUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        return true
    }
}
