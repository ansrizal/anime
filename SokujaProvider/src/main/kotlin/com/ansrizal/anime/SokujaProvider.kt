package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse

class SokujaProvider : MainAPI() {
    override var mainUrl = "https://sokuja.net"
    override var name = "Sokuja Anime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val turnstileInterceptor = TurnstileInterceptor("_as_turnstile")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1"
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
        "" to "Update Terbaru",
        "anime/" to "Daftar Anime",
        "genre/action/" to "Action Anime",
        "genre/isekai/" to "Isekai Anime",
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
        
        // Very broad selection to avoid empty lists
        val items = document.select("div.listupd article, div.bs, div.bsx, div.utao, div.uta, div.luf, div.item, div.utao, .post-show, .relat")
        val homeItems = items.mapNotNull {
            it.toSearchResult()
        }

        // Emergency fallback to root anime list if main page is still empty
        if (homeItems.isEmpty() && request.data.isEmpty()) {
            val fallbackUrl = "$mainUrl/anime/?order=update"
            val fallbackDoc = request(fallbackUrl).document
            val fallbackItems = fallbackDoc.select("div.listupd article, div.bs, div.bsx").mapNotNull { it.toSearchResult() }
            if (fallbackItems.isNotEmpty()) return newHomePageResponse(request.name, fallbackItems)
        }

        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the link to the anime/manga details
        val linkElement = this.selectFirst("a[href*='/anime/']") 
            ?: this.selectFirst("a[href*='/manga/']") 
            ?: this.selectFirst("a") 
            ?: return null
            
        val href = fixUrl(linkElement.attr("href"))
        // Basic filtering to avoid non-content links
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/genre/") || href.contains("/category/") || href.contains("/tag/")) return null

        val title = this.selectFirst("h2, h3, h4, .tt, .title, .entry-title")?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: linkElement.text().trim().ifEmpty { null }
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("abs:data-src")
            ?: img?.attr("abs:data-lazy-src")
            ?: img?.attr("abs:src")
            ?: img?.attr("src")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = request(searchUrl).document

        return document.select("div.listupd article, div.bs, div.bsx, div.utao, div.uta, div.luf").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.replace("Nonton Anime ", "")?.trim() ?: "Sokuja Anime"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("div.fotoanime img, div.thumb img, div.thumb img")?.attr("src"))
        val description = document.selectFirst("div.sinopc, div.entry-content, div.synopsis, [itemprop=description]")?.text()?.trim()

        val episodes = document.select("li[data-index], div.eplister li, div.listeps li, .eplister ul li, div.list-episode li, div.lsteps ul li").mapNotNull { elem ->
            val a = elem.selectFirst("a") ?: return@mapNotNull null
            val epUrl = fixUrl(a.attr("href"))
            val epName = a.text().trim()
            val epNum = elem.selectFirst("span.epstitle, .epl-num, .eps")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: Regex("Episode\\s?(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
            }
        }.distinctBy { it.data }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(
                DubStatus.Subbed,
                episodes.ifEmpty {
                    listOf(newEpisode(url) { this.name = "Full Stream"; this.episode = 1 })
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document

        // Check iframe embeds
        document.select("iframe").asIterable().forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains("streamtape") || src.contains("filemoon") || src.contains("dood") || src.contains("ds2play")) {
                loadExtractor(src, subtitleCallback, callback)
            } else if (src.endsWith(".m3u8") || src.contains(".m3u8?")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Sokuja HLS Stream",
                        url = src,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = Qualities.P1080.value
                    }
                )
            }
        }

        return true
    }
}
