package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.nicehttp.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://v1.animesail.xyz"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val turnstileInterceptor = TurnstileInterceptor("_as_turnstile")

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "movie-terbaru/" to "Movie Terbaru",
        "rilisan-anime-terbaru/" to "Anime Ongoing",
        "rilisan-donghua-terbaru/" to "Donghua Ongoing",
        "anime/" to "Daftar Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            val data = request.data.ifEmpty { "rilisan-anime-terbaru" }.removeSuffix("/")
            "$mainUrl/$data/page/$page/"
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = request(url).document
        val home = document.select("div.listupd article, article.bs, div.bs, div.bsx, div.ml-item, div.item, article, div.uta").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))

        val rawTitle = this.selectFirst(".tt > h2, h2, h3, .title, a[title]")?.text()
            ?: a.attr("title")
            ?: return null

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val img = this.selectFirst("img")
        val posterUrl = fixImageUrl(
            img?.attr("data-src")
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("src")
        )

        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val typeText = this.selectFirst(".tt > span, .typez, span.type")?.text() ?: ""
        val type = if (typeText.contains("Movie", ignoreCase = true) || href.contains("/movie/")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document

        return document.select("div.listupd article, article.bs, div.bs, div.bsx, div.ml-item, div.item, article, div.uta").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var currentUrl = url
        var res = request(currentUrl)
        var document = res.document
        
        // Auto-redirect from Episode page to Anime page for "Update Terbaru"
        if (!currentUrl.contains("/anime/")) {
            val seriesLink = document.selectFirst(".breadcrumb a[href*='/anime/'], .series-all a[href*='/anime/'], .info-content a[href*='/anime/'], a[href*='/anime/']:has(h1)")?.attr("href")
            if (seriesLink != null) {
                currentUrl = fixUrl(seriesLink)
                res = request(currentUrl)
                document = res.document
            }
        }

        val title = document.selectFirst("h1.entry-title, h1")?.text()
            ?.replace("Subtitle Indonesia", "")
            ?.replace("Sub Indo", "")
            ?.trim() ?: "AnimeSail"
            
        val poster = fixImageUrl(document.selectFirst(".thumb img, .entry-content img, meta[property='og:image']")?.attr("src") ?: document.selectFirst("img[alt*='$title']")?.attr("src"))
        
        val typeText = document.select("tbody th:contains(Tipe), .info-content span:contains(Type)").next().text().lowercase()
        val type = if (typeText.contains("movie")) TvType.AnimeMovie else TvType.Anime
        val year = document.select("tbody th:contains(Dirilis), .info-content span:contains(Released)").next().text().trim().toIntOrNull()
        val statusText = document.select("tbody th:contains(Status), .info-content span:contains(Status)").next().text().trim()
        val plotText = document.selectFirst("div.entry-content p, .desc, .sinopsis")?.text()
        val tagsList = document.select("tbody th:contains(Genre), .info-content .genx a").select("a").map { it.text() }
        val durationText = document.select("tbody th:contains(Durasi), .info-content span:contains(Duration)").next().text().trim()

        val episodes = document.select("ul.daftar > li, .eplist ul li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href"))
            val name = a.text()

            var episodeNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(\\d+)").find(it.selectFirst(".num, .ep")?.text() ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            newEpisode(link) {
                this.name = name
                this.episode = episodeNum
            }
        }.distinctBy { it.name }.sortedByDescending { it.episode }

        return newAnimeLoadResponse(title, currentUrl, type) {
            this.posterUrl = poster
            this.year = year
            this.duration = getDurationFromString(durationText)
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = if (statusText.contains("ongoing", true)) ShowStatus.Ongoing else ShowStatus.Completed
            this.plot = plotText
            this.tags = tagsList
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url == null) return null
        return if (url.startsWith("//")) "https:$url" else fixUrl(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document
        val playerPath = "$mainUrl/utils/player/"

        document.select(".mobius > .mirror > option, select.mirror option").amap { element ->
            safeApiCall {
                val encodedData = element.attr("data-em")
                if (encodedData.isBlank()) return@safeApiCall

                val iframe = fixUrl(Jsoup.parse(base64Decode(encodedData)).select("iframe").attr("src"))
                if (iframe.contains("statistic") || iframe.isBlank()) return@safeApiCall

                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)

                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                if (iframe.endsWith(".mp4", ignoreCase = true) || iframe.endsWith(".m3u8", ignoreCase = true)) {
                    callback.invoke(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = iframe,
                            type = if (iframe.endsWith(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            referer = mainUrl
                            this.quality = quality
                        }
                    )
                } else if (iframe.contains("${playerPath}popup")) {
                    val encodedUrl = iframe.substringAfter("url=").substringBefore("&")
                    if (encodedUrl.isNotBlank()) {
                        val realUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                        loadFixedExtractor(realUrl, serverName, quality, mainUrl, subtitleCallback, callback)
                    }
                } else if (iframe.contains("${playerPath}framezilla") || iframe.contains("uservideo.xyz")) {
                    val bsrc = Regex("""bsrc=(.*?)(&|$)""").find(iframe)?.groupValues?.getOrNull(1)
                    var innerLink = ""
                    
                    if (!bsrc.isNullOrBlank()) {
                        try {
                            innerLink = base64Decode(bsrc)
                        } catch (_: Exception) {}
                    }
                    
                    if (innerLink.isBlank()) {
                        innerLink = request(iframe, ref = data).document.select("iframe").attr("src")
                    }
                    
                    if (innerLink.isNotBlank()) {
                        innerLink = fixUrl(innerLink)
                        if (innerLink.contains(playerPath) || innerLink.contains("player-kodir")) {
                            extractInternalPlayer(innerLink, data, serverName, quality, callback)
                        } else {
                            loadFixedExtractor(innerLink, serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }
                } else if (iframe.contains("player-kodir") || iframe.contains("${playerPath}kodir2") || iframe.contains("${playerPath}mega") || iframe.contains("${playerPath}gideo") || iframe.contains(playerPath)) {
                    extractInternalPlayer(iframe, data, serverName, quality, callback)
                } else if (iframe.contains("aghanim.xyz/tools/redirect/")) {
                    val id = iframe.substringAfter("id=").substringBefore("&token")
                    val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                    loadFixedExtractor(link, serverName, quality, mainUrl, subtitleCallback, callback)
                } else {
                    loadFixedExtractor(iframe, serverName, quality, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun extractInternalPlayer(
        url: String,
        ref: String,
        serverName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = request(url, ref = ref).text
        val doc = Jsoup.parse(res)
        
        var link = Jsoup.parse(res.substringAfter("= `", "").substringBefore("`;", "")).select("source").last()?.attr("src")
        if (link.isNullOrBlank()) link = doc.select("source").attr("src")
        if (link.isNullOrBlank()) link = doc.select("video").attr("src")
        if (link.isNullOrBlank()) link = Regex("""(?:file|src):\s*["']([^"']+)["']""").find(res)?.groupValues?.getOrNull(1)

        if (!link.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = fixUrl(link),
                    type = INFER_TYPE
                ) {
                    referer = url
                    this.quality = quality
                }
            )
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            val finalName = if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = finalName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
