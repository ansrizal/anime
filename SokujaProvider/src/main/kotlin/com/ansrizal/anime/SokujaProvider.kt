package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup

class SokujaProvider : MainAPI() {
    override var mainUrl = "https://x6.sokuja.uk"
    override var name = "Sokuja Anime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val turnstileInterceptor = TurnstileInterceptor("cf_clearance")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
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
        "anime/?order=update" to "Update Terbaru",
        "anime/?status=&type=&order=title" to "Daftar Anime",
        "genre/action/" to "Action Anime",
        "genre/isekai/" to "Isekai Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (page <= 1) {
            if (path.isEmpty()) "$mainUrl/anime/?order=update" else "$mainUrl/$path"
        } else {
            if (path.contains("?")) {
                val base = path.substringBefore("?")
                val query = path.substringAfter("?")
                "$mainUrl/${base.removeSuffix("/")}/page/$page/?$query"
            } else {
                "$mainUrl/${path.removeSuffix("/")}/page/$page/"
            }
        }

        val document = request(url).document
        
        // Dynamic selector untuk menangkap layout Grid (.bs), List (.uta/.utao), dan Card (.listupd)
        val items = document.select("div.listupd article, div.bs, div.bsx, div.utao, div.uta, div.luf, div.animposx")
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
        
        val cleanMain = mainUrl.removeSuffix("/")
        val cleanHref = href.removeSuffix("/")
        
        if (cleanHref == cleanMain || 
            href.contains("/genre/") || 
            href.contains("/category/") || 
            href.contains("/page/") ||
            href.contains("/bookmark/")) return null

        val title = this.selectFirst(".tt, h2, h3, h4, .title, .entry-title, .luf h4")?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.isEmpty()) return null

        val img = this.selectFirst("img")
        var rawImg = img?.attr("data-src")
            ?.ifEmpty { null }
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("src")
            ?: img?.attr("srcset")?.substringBefore(" ")

        if (rawImg != null && rawImg.startsWith("//")) {
            rawImg = "https:$rawImg"
        }
        val posterUrl = fixUrlNull(rawImg)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = request(searchUrl).document

        return document.select("div.listupd article, div.bs, div.bsx, div.utao, div.uta, div.luf, div.animposx").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        // Jika user membuka tautan episode langsung, alihkan ke halaman utama anime
        val seriesLink = document.selectFirst("div.breadcrumb a[href*='/anime/'], div.ninfo a[href*='/anime/'], span.all-ep a, .all-episode a")?.attr("href")
        if (seriesLink != null && !url.contains("/anime/")) {
            return load(fixUrl(seriesLink))
        }

        val title = document.selectFirst("h1.entry-title, h1, .infotable h1")?.text()?.replace("Nonton Anime ", "")?.trim() ?: "Sokuja Anime"
        
        var rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("data-src")
            ?: document.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("src")

        if (rawPoster != null && rawPoster.startsWith("//")) {
            rawPoster = "https:$rawPoster"
        }
        val poster = fixUrlNull(rawPoster)
        val description = document.selectFirst("div.sinopc, div.entry-content, div.synopsis, [itemprop=description]")?.text()?.trim()

        val episodes = document.select("li[data-index], div.eplister li, div.listeps li, .eplister ul li, div.list-episode li, div.lsteps ul li, ul.clnew li").mapNotNull { elem ->
            val a = elem.selectFirst("a") ?: return@mapNotNull null
            val epUrl = fixUrl(a.attr("href"))
            val epName = a.text().trim()
            val epNum = elem.selectFirst("span.epstitle, .epl-num, .eps, .epl-zero")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: Regex("""Episode\s?(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()

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

        // 1. Scan semua iframe pemutar video
        document.select("iframe, iframe[data-src]").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"

            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("disqus")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 2. Scan opsi Server Select (dropdown / mirror list)
        val serverOptions = document.select("select.mirror option, ul.mserver li a, .select-server option, [data-index]")
        for (option in serverOptions) {
            val embedUrl = option.attr("value").ifBlank { option.attr("data-em") }.ifBlank { option.attr("href") }
            if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
