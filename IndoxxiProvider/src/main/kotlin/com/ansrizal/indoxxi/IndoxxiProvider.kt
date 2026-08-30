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

        val isSeries = href.contains("/tv-series/") || href.contains("/series/") || href.contains("/tv/") || href.contains("/anime/")

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
        
        // Ekstraksi poster lengkap dengan fallback multi-selector
        var rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
            ?: document.selectFirst(".poster img, .thumb img, img.wp-post-image, article img, .poster-container img, .mobile-poster img, .g-item img, .content-poster img")?.attr("data-src")
            ?: document.selectFirst(".poster img, .thumb img, img.wp-post-image, article img, .poster-container img, .mobile-poster img, .g-item img, .content-poster img")?.attr("src")

        if (rawPoster.isNullOrEmpty()) {
            val styleAttr = document.selectFirst(".poster, .thumb, .cover, .backdrop, #cover, .poster-wrapper")?.attr("style")
            if (!styleAttr.isNullOrEmpty() && styleAttr.contains("url(")) {
                rawPoster = styleAttr.substringAfter("url(").substringBefore(")").replace("'", "").replace("\"", "")
            }
        }

        if (rawPoster != null && rawPoster.startsWith("//")) {
            rawPoster = "https:$rawPoster"
        }
        val poster = fixUrlNull(rawPoster)
        val description = document.selectFirst("div.entry-content, div.synopsis, [itemprop=description], .description, .plot, .entry-content p")?.text()?.trim()

        val isSeries = url.contains("/tv-series/") || url.contains("/series/") || url.contains("/tv/") || url.contains("/anime/") || document.select("ul.episodios, div.list-episode, .eplister, .listeps, .eps-item, #episode_list, .epsdiv, #episodes, .les-content, .bxcl").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Mencari seluruh episode dari list HTML bawaan tema (Dooplay/Muvi/LK21)
            val episodeElements = document.select("ul.episodios li, div.list-episode li, .eplister li, .listeps li, div.eps-item, #episode_list a, .episodiodiv a, .epsdiv a, div.episodelist a, #episodes a, div.season-list a, .les-content a, .les-list a")
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, elem ->
                    val a = if (elem.tagName() == "a") elem else elem.selectFirst("a")
                    val epUrl = a?.attr("href")?.let { fixUrl(it) } ?: url
                    val epName = a?.text()?.trim()?.ifEmpty { null } ?: elem.selectFirst(".epl-title, .title, .lex-title")?.text()?.trim() ?: "Episode ${index + 1}"
                    val epNum = elem.selectFirst(".numerando, .epl-num, .eps, .epnum, .epl-zero")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (index + 1)
                    
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.episode = epNum
                        }
                    )
                }
            } else {
                // Ekstraksi AJAX alternatif jika episode disembunyikan dalam tab season
                val postId = document.selectFirst("input[name=id], input[id=post_id], #post_ID, input[name=post_id]")?.attr("value")
                    ?: document.selectFirst("[data-id]")?.attr("data-id")
                    ?: document.selectFirst("[data-post]")?.attr("data-post")

                if (!postId.isNullOrEmpty()) {
                    val ajaxActions = listOf("get_episodes", "load_episodes", "get_episodes_list", "fetch_episodes", "muvi_episodes")
                    for (actionName in ajaxActions) {
                        try {
                            val ajaxRes = app.post(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                headers = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                                data = mapOf("action" to actionName, "series_id" to postId, "id" to postId, "post_id" to postId)
                            ).text

                            val parsedEp = Jsoup.parse(ajaxRes).select("a")
                            if (parsedEp.isNotEmpty()) {
                                parsedEp.forEachIndexed { index, a ->
                                    val epUrl = fixUrl(a.attr("href"))
                                    val epName = a.text().trim().ifEmpty { "Episode ${index + 1}" }
                                    episodes.add(
                                        newEpisode(epUrl) {
                                            this.name = epName
                                            this.episode = index + 1
                                        }
                                    )
                                }
                                break
                            }
                        } catch (_: Exception) {}
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

        // 1. Ekstraksi iframe & atribut alternatifnya (data-src, data-player, src)
        val iframes = document.select("iframe, iframe[data-src], iframe[data-lazy-src]")
        for (iframe in iframes) {
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook.com") && !src.contains("twitter.com") && !src.contains("disqus.com")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 2. Ekstraksi tombol/tab server (embed / stream link)
        val playerTabs = document.select("ul.muvi-player-list li, div.source-box li, .player-source, .mirror-item, option[value*='http'], .bonnette, [data-link], [data-embed], [data-url], .server-item, .embed-selector a, #player-option-1")
        for (elem in playerTabs) {
            val serverSrc = elem.selectFirst("a")?.attr("href") 
                ?: elem.attr("data-src") 
                ?: elem.attr("data-href") 
                ?: elem.attr("data-link")
                ?: elem.attr("data-embed")
                ?: elem.attr("data-url")
                ?: elem.attr("value")
            
            if (!serverSrc.isNullOrBlank()) {
                var cleanSrc = fixUrl(serverSrc)
                if (cleanSrc.startsWith("//")) cleanSrc = "https:$cleanSrc"
                if (cleanSrc.startsWith("http")) {
                    loadExtractor(cleanSrc, subtitleCallback, callback)
                }
            }
        }

        // 3. Scan regex script JS untuk mendeteksi URL iframe/player yang di-render secara dinamis
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        val embeddedUrls = Regex("""(?:iframe|src|file|link|embed)\s*[:=]\s*["']([^"']+)["']""").findAll(scriptContent)
        for (match in embeddedUrls) {
            var extractedUrl = match.groupValues[1]
            if (extractedUrl.startsWith("//")) extractedUrl = "https:$extractedUrl"
            if (extractedUrl.startsWith("http") && !extractedUrl.contains("facebook") && !extractedUrl.contains("google") && !extractedUrl.contains("schema.org")) {
                loadExtractor(extractedUrl, subtitleCallback, callback)
            }
        }

        // 4. Pengecekan AJAX Player khusus untuk CMS streaming WordPress
        val postId = document.selectFirst("input[name=id], input[id=post_id], #post_ID, input[name=post_id]")?.attr("value")
            ?: document.selectFirst("[data-id]")?.attr("data-id")
            ?: document.selectFirst("[data-post]")?.attr("data-post")

        if (!postId.isNullOrEmpty()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val actions = listOf("player_ajax", "gdriveplayer_ajax", "get_player", "ajax_player", "select_server", "doo_player_ajax")
            
            for (actionName in actions) {
                try {
                    val ajaxRes = app.post(
                        ajaxUrl,
                        headers = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                        data = mapOf("action" to actionName, "post" to postId, "id" to postId, "type" to "movie")
                    ).text

                    val docAjax = Jsoup.parse(ajaxRes)
                    val parsedIframe = docAjax.selectFirst("iframe")?.attr("src")
                        ?: docAjax.selectFirst("iframe")?.attr("data-src")

                    if (!parsedIframe.isNullOrEmpty()) {
                        var cleanUrl = parsedIframe
                        if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
                        loadExtractor(cleanUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }

        return true
    }
}
