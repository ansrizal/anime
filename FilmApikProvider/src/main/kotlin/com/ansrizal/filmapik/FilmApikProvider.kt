package com.ansrizal.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse

class FilmApikProvider : MainAPI() {
    override var mainUrl = "https://filmapik.college"
    override var name = "FilmApik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

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
        "" to "Beranda",
        "category/box-office/" to "Box Office",
        "latest/" to "Film Terbaru",
        "tvshows/" to "Drama Terbaru",
        "tvshows-genre/anime/" to "Anime",
        "tvshows-genre/k-drama/" to "Drama Korea",
        "category/action/" to "Action",
        "category/comedy/" to "Comedy",
        "category/horror/" to "Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            val data = request.data.removeSuffix("/")
            if (data.isEmpty()) {
                "$mainUrl/latest/page/$page/"
            } else {
                "$mainUrl/$data/page/$page/"
            }
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = request(url).document
        val home = mutableListOf<HomePageList>()

        if (request.data.isEmpty() && page <= 1) {
            // Box Office Section
            val boxOffice = document.select("#famv-boxoffice a.group").mapNotNull { it.toSearchResult() }
            if (boxOffice.isNotEmpty()) home.add(HomePageList("Box Office", boxOffice, isHorizontalImages = true))

            // TV Shows Section
            val tvShows = document.select("#famv-tvshows a.group").mapNotNull { it.toSearchResult() }
            if (tvShows.isNotEmpty()) home.add(HomePageList("Drama Terbaru", tvShows, isHorizontalImages = true))

            // Latest Movies Grid
            val latest = document.select("article.card, .grid article").mapNotNull { it.toSearchResult() }
            if (latest.isNotEmpty()) home.add(HomePageList("Film Terbaru", latest))
        } else {
            val items = document.select("article.card, .grid article, a.group, div.card").mapNotNull { it.toSearchResult() }
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, .title, a[title]")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        val linkElement = if (this.tagName() == "a") this else this.selectFirst("a")
        val href = fixUrl(linkElement?.attr("href") ?: return null)
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/category/") || href.contains("/release-year/")) return null

        val img = this.selectFirst("img")
        val srcset = img?.attr("srcset") ?: img?.attr("data-srcset")
        val posterUrl = fixUrlNull(
            if (!srcset.isNullOrBlank()) {
                srcset.split(",").last().trim().split(" ").first()
            } else {
                img?.attr("abs:data-src")
                    ?: img?.attr("abs:src")
                    ?: img?.attr("src")
            }
        )

        val isSeries = href.contains("/tvshows/") || href.contains("/series/") || href.contains("/tv/") || href.contains("/tv-series/")
        val quality = this.selectFirst(".badge-quality")?.text()?.trim()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                quality?.let { addQuality(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                quality?.let { addQuality(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = request(searchUrl).document

        return document.select("article.card, .grid article, a.group").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title, h1, .title, .name")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("div.thumb img, img.wp-post-image, .poster img")?.attr("src"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.entry-content, div.synopsis, [itemprop=description], .description, .prose")?.text()?.trim()

        val isSeries = url.contains("/tvshows/") || url.contains("/series/") || url.contains("/tv/") || url.contains("/tv-series/")

        return if (isSeries) {
            // First check if there is an episode list in the HTML
            var episodes = document.select(".episodios li, .list-episode li, .eplister li, #episodes-list a, .famv-episodes a").mapNotNull { elem ->
                val a = if (elem.tagName() == "a") elem else elem.selectFirst("a")
                val epUrl = fixUrl(a?.attr("href") ?: return@mapNotNull null)
                val epName = a.text().trim().ifEmpty { "Episode" }
                
                newEpisode(epUrl) {
                    this.name = epName
                }
            }
            
            // Fallback: If no episodes found, try to look for Season/Episode structure in breadcrumbs or other elements
            if (episodes.isEmpty()) {
                document.select(".tv-episodes a").mapNotNull { a ->
                    val epUrl = fixUrl(a.attr("href"))
                    newEpisode(epUrl) {
                        this.name = a.text().trim()
                    }
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
        val response = request(data)
        val html = response.text

        // 1. Parse from window.famvServers JSON (Most reliable for new theme)
        val serversRegex = Regex("""window\.famvServers\s*=\s*(\[.*?\]);""")
        val serversJson = serversRegex.find(html)?.groupValues?.get(1)
        if (serversJson != null) {
            val urlRegex = Regex(""""url"\s*:\s*"(.*?)"""")
            urlRegex.findAll(serversJson).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                if (url.isNotBlank()) {
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }

        // 2. Fallback to iframes in the document
        val document = response.document
        document.select("iframe").asIterable().forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook.com") && !src.contains("twitter.com") && !src.contains("google.com")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }
        
        // 3. Check for specific player scripts or data attributes
        document.select(".famv-server-btn").forEach { btn ->
            val url = btn.attr("data-url")
            if (url.isNotBlank()) loadExtractor(fixUrl(url), subtitleCallback, callback)
        }

        return true
    }
}
