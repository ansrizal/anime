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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val turnstileInterceptor = TurnstileInterceptor("cf_clearance")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
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
        "popular/" to "Film Populer",
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

        val titleElement = this.selectFirst("h2, h3, h4, .tt, .title, .mli-info h2, .entry-title")
        val title = titleElement?.text()?.trim() 
            ?: linkElement.attr("title").trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
        if (title.isEmpty() || title.length < 2) return null

        val img = this.selectFirst("img")
        var rawImgUrl = img?.attr("data-src")
            ?.ifEmpty { null }
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("data-original")
            ?: img?.attr("srcset")?.substringBefore(" ")
            ?: img?.attr("src")

        if (rawImgUrl != null && rawImgUrl.startsWith("//")) {
            rawImgUrl = "https:$rawImgUrl"
        }
        val posterUrl = fixUrlNull(rawImgUrl)

        val isSeries = href.contains("/tv-series/") || href.contains("/series/") || href.contains("/tv/") || href.contains("/anime/") || href.contains("/eps/")

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

        val title = document.selectFirst("h1.entry-title, h1.name, h1, .title, header h1")?.text()?.trim() ?: "INDOXXI"
        
        // Poster extraction dengan fallback poster default jika "No Image Available"
        var rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
            ?: document.selectFirst(".poster img, .thumb img, img.wp-post-image, article img, .poster-container img, .content-poster img")?.attr("data-src")
            ?: document.selectFirst(".poster img, .thumb img, img.wp-post-image, article img, .poster-container img, .content-poster img")?.attr("src")

        if (rawPoster != null && rawPoster.startsWith("//")) {
            rawPoster = "https:$rawPoster"
        }
        val poster = fixUrlNull(rawPoster)
        val description = document.selectFirst("div.entry-content, div.synopsis, [itemprop=description], .description, .plot, .entry-content p")?.text()?.trim()

        val isSeries = url.contains("/tv-series/") || url.contains("/series/") || url.contains("/tv/") || url.contains("/anime/") || url.contains("/eps/") || document.select("a:contains(Lihat Semua Episode), .list-episode, .eplister, .listeps, #episode_list").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // 1. Ambil URL utama serial jika sedang berada di halaman single episode (/eps/...)
            val seeAllBtn = document.selectFirst("a:contains(Lihat Semua Episode), a.btn-eps, a[href*='/series/'], a[href*='/tv/'], a[href*='/anime/']")
            val parentUrl = seeAllBtn?.attr("href")?.let { fixUrl(it) } ?: url

            val targetDoc = if (parentUrl != url && parentUrl.isNotBlank()) {
                request(parentUrl).document
            } else {
                document
            }

            // 2. Parse episode dari halaman utama anime/serial
            val episodeElements = targetDoc.select("ul.episodios li, div.list-episode li, .eplister li, .listeps li, div.eps-item, #episode_list a, .episodiodiv a, .epsdiv a, div.episodelist a, #episodes a, div.season-list a, .les-content a, .les-list a, div.pagination a, ul.page-numbers a")
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, elem ->
                    val a = if (elem.tagName() == "a") elem else elem.selectFirst("a")
                    val epUrl = a?.attr("href")?.let { fixUrl(it) } ?: url
                    
                    if (!epUrl.contains("/page/")) {
                        val epName = a?.text()?.trim()?.ifEmpty { null } ?: elem.selectFirst(".epl-title, .title, .lex-title")?.text()?.trim() ?: "Episode ${index + 1}"
                        val epNum = elem.selectFirst(".numerando, .epl-num, .eps, .epnum, .epl-zero")?.text()?.filter { it.isDigit() }?.toIntOrNull() 
                            ?: epName.filter { it.isDigit() }.toIntOrNull() 
                            ?: (index + 1)
                        
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.episode = epNum
                            }
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
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

        // 1. Ekstraksi Multi-Server (VIDHIDE, STREAMWISH, MIXDROP, DOODSTREAM, STREAMP2P)
        val serverElements = document.select(".server-item, .player-servers li, option[value*='http'], [data-link], [data-embed], [data-url], div.server-list a, ul.embed-selector li")
        for (server in serverElements) {
            val serverUrl = server.attr("data-link")
                .ifBlank { server.attr("data-embed") }
                .ifBlank { server.attr("data-url") }
                .ifBlank { server.attr("value") }
                .ifBlank { server.selectFirst("a")?.attr("href") }
            
            if (!serverUrl.isNullOrBlank()) {
                var cleanUrl = fixUrl(serverUrl)
                if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
                loadExtractor(cleanUrl, subtitleCallback, callback)
            }
        }

        // 2. Ekstraksi iframe baku di halaman
        val iframes = document.select("iframe, iframe[data-src], iframe[data-lazy-src]")
        for (iframe in iframes) {
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook.com") && !src.contains("twitter.com") && !src.contains("disqus.com")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 3. Regex Scan Script untuk menemukan URL Vidhide / Streamwish / Mixdrop / Doodstream yang di-encode
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        val streamUrls = Regex("""https?://[^\s"'<>]+(?:vidhide|streamwish|mixdrop|dood|streamtape|filelions)[^\s"'<>]*""").findAll(scriptContent)
        for (match in streamUrls) {
            val extractedUrl = match.value
            loadExtractor(extractedUrl, subtitleCallback, callback)
        }

        return true
    }
}
