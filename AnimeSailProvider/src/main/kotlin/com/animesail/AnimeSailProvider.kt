package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://v1.animesail.xyz"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true

    private val turnstileInterceptor = TurnstileInterceptor("_as_turnstile")

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "movie-terbaru/" to "Movie Terbaru",
        "rilisan-anime-terbaru/" to "Anime Ongoing",
        "rilisan-donghua-terbaru/" to "Donghua Ongoing",
        "anime/" to "Daftar Anime"
    )

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            ),
            referer = ref ?: mainUrl,
            interceptor = turnstileInterceptor
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 && request.data.isEmpty() -> mainUrl
            page <= 1 -> "$mainUrl/${request.data}"
            else -> {
                val data = request.data.ifEmpty { "" }.trim('/')
                if (data.isEmpty()) {
                    "$mainUrl/page/$page/"
                } else {
                    "$mainUrl/$data/page/$page/"
                }
            }
        }

        return try {
            val response = request(url)
            val document = response.document
            
            val items = if (request.data == "anime/") {
                document.select("a[href*='/anime/']").mapNotNull { it.toDaftarAnimeResult() }.distinctBy { it.url }
            } else {
                val selectors = listOf(
                    "article.bs",
                    "article.bsz",
                    ".listupd article",
                    ".postbody article",
                    "div.bsx",
                    "div.bs",
                    "div.animposx",
                    "div.animepost",
                    ".venz ul li"
                )
                
                var foundItems = emptyList<AnimeSearchResponse>()
                for (selector in selectors) {
                    foundItems = document.select(selector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
                    if (foundItems.isNotEmpty()) break
                }
                foundItems
            }
            
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            println("AnimeSail: Error loading main page: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        
        if (href.isBlank() || href.contains("/page/") || href.contains("/genre/") || href.contains("/category/") || href.contains("/tag/")) return null

        val rawTitle = this.selectFirst(".tt h2, h3, h4, .title, h2.jdlflm")?.text()
            ?: a.attr("title")
            ?: return null

        val title = rawTitle
            .replace(Regex("(?i)Episode\\s*\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val img = this.selectFirst("img")
        val posterUrl = fixImageUrl(
            img?.attr("src")?.takeIf { !it.startsWith("data:") }
                ?: img?.attr("data-src")
                ?: img?.attr("data-lazy-src")
        )

        val type = when {
            this.hasClass("bsz") || href.contains("/movie/") || rawTitle.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val epNum = Regex("(?i)Episode\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)Ep\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun Element.toDaftarAnimeResult(): AnimeSearchResponse? {
        val href = fixUrl(attr("href"))
        if (href.isBlank() || !href.contains("/anime/") || href.contains("/page/") || href.contains("/genre/") || href.contains("/category/") || href.contains("/tag/")) return null
        
        val title = text().trim().ifEmpty { attr("title").trim() }
        if (title.isBlank() || title.lowercase() == "daftar anime" || title.lowercase() == "update terbaru" || title.lowercase() == "movie" || title.lowercase() == "genre" || title.lowercase() == "jadwal") return null

        val cleanTitle = title
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()

        return newAnimeSearchResponse(cleanTitle, href, TvType.Anime) {
            this.posterUrl = null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        return try {
            val document = request(link).document
            document.select("article.bs, article.bsz, div.bsx, div.bs, div.animposx, div.animepost, .venz ul li").mapNotNull { 
                it.toSearchResult() 
            }.distinctBy { it.url }
        } catch (e: Exception) {
            println("AnimeSail: Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var currentUrl = url
        var res = request(currentUrl)
        var document = res.document

        if (currentUrl.contains("-episode-") && !currentUrl.contains("/anime/")) {
            val seriesLink = document.selectFirst(".breadcrumb a[href*='/anime/']")?.attr("href")
                ?: document.selectFirst("div.entry-content i a[href*='/anime/']")?.attr("href")
                ?: document.selectFirst("a[href*='/anime/'][rel='tag']")?.attr("href")
                ?: document.selectFirst("a[href*='/anime/']")?.attr("href")

            if (seriesLink != null) {
                currentUrl = fixUrl(seriesLink)
                res = request(currentUrl)
                document = res.document
            }
        }

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: document.title()
            ?: "AnimeSail"
        
        val cleanTitle = title
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()

        val poster = fixImageUrl(
            document.selectFirst(".thumb img")?.attr("src")
                ?: document.selectFirst(".entry-content img")?.attr("src")
                ?: document.selectFirst(".post-thumbnail img")?.attr("src")
                ?: document.selectFirst("img.attachment-post-thumbnail")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        var type = TvType.Anime
        var year: Int? = null
        var status = ShowStatus.Completed
        var plot: String? = null
        val tags = mutableListOf<String>()

        document.select("tr").forEach { row ->
            val th = row.selectFirst("th")?.text()?.lowercase() ?: return@forEach
            val td = row.selectFirst("td")?.text()?.trim() ?: return@forEach
            
            when {
                th.contains("type") || th.contains("tipe") -> {
                    type = if (td.lowercase().contains("movie")) TvType.AnimeMovie else TvType.Anime
                }
                th.contains("dirilis") || th.contains("released") || th.contains("tahun") || th.contains("year") -> {
                    year = Regex("\\d{4}").find(td)?.value?.toIntOrNull()
                }
                th.contains("status") -> {
                    status = if (td.lowercase().contains("ongoing") || td.lowercase().contains("airing")) 
                        ShowStatus.Ongoing 
                    else 
                        ShowStatus.Completed
                }
                th.contains("genre") || th.contains("genres") -> {
                    tags.addAll(td.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            }
        }

        if (tags.isEmpty()) {
            document.select("a[href*='/genres/']").forEach { genreLink ->
                val genre = genreLink.text().trim()
                if (genre.isNotEmpty() && !tags.contains(genre)) {
                    tags.add(genre)
                }
            }
        }

        plot = document.selectFirst(".entry-content p")?.text()
            ?: document.selectFirst(".sinopsis")?.text()
            ?: document.selectFirst(".desc")?.text()
            ?: document.selectFirst(".entry-content")?.text()

        if (currentUrl.contains("/movie/")) {
            type = TvType.AnimeMovie
        }

        val episodes = document.select(".eplister ul li, .eplist ul li, ul.daftar li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val epUrl = fixUrl(a.attr("href"))
            
            val epTitle = a.selectFirst(".epl-title")?.text() 
                ?: a.text().trim()
            
            if (epTitle.isBlank()) return@mapNotNull null

            val epNum = Regex("(?i)Episode\\s*(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(\\d+)").find(li.selectFirst(".epl-num")?.text() ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (type == TvType.AnimeMovie) 1 else null

            newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum
            }
        }.distinctBy { it.data }.sortedWith(compareBy(nullsLast()) { it.episode }) // Urutan Ascending agar Next maju

        return newAnimeLoadResponse(cleanTitle, currentUrl, type) {
            this.posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.plot = plot
            this.tags = tags
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            url.startsWith("http") -> url
            else -> "$mainUrl/$url"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = request(data).document
            
            // Cek iframe default (jika ada)
            val defaultIframe = fixUrl(document.selectFirst("iframe[src]")?.attr("src") ?: "")
            if (defaultIframe.isNotBlank() && !defaultIframe.contains("statistic") && !defaultIframe.contains("error.php")) {
                // PERBAIKAN: Ganti runCatching dengan try-catch agar bisa memanggil fungsi suspend
                try {
                    resolveAndLoadIframe(defaultIframe, "$name Default", Qualities.Unknown.value, subtitleCallback, callback)
                } catch (e: Exception) {
                    println("AnimeSail: Error default iframe: ${e.message}")
                }
            }

            // Selector server list
            val options = document.select(".mobius > .mirror > option, select.mirror option, .mirror option, .server-list li[data-em], .server-list a[data-em]")
            
            // PERBAIKAN: Ganti options.amap dengan perulangan for standar
            for (element in options) {
                val encodedData = element.attr("data-em")
                if (encodedData.isBlank()) continue

                try {
                    val decoded = base64Decode(encodedData)
                    val parsed = Jsoup.parse(decoded)
                    val iframe = fixUrl(parsed.select("iframe").attr("src").ifBlank { parsed.text().trim() })
                    if (iframe.isBlank() || iframe.contains("statistic") || iframe.contains("error.php")) continue

                    val rawText = element.text().trim() // Contoh isi: "mp4 720p", "lokal"
                    
                    // Ekstrak kualitas
                    val quality = when {
                        rawText.contains("1080", true) -> Qualities.P1080.value
                        rawText.contains("720", true) -> Qualities.P720.value
                        rawText.contains("480", true) -> Qualities.P480.value
                        rawText.contains("360", true) -> Qualities.P360.value
                        rawText.contains("240", true) -> Qualities.P240.value
                        else -> Regex("(\\d{3,4})[pP]").find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
                    }
                    
                    // Format nama server (contoh: "Mp4 720p")
                    val serverName = rawText.replaceFirstChar { 
                        if (it.isLowerCase()) it.titlecase() else it.toString() 
                    }.ifBlank { name }

                    resolveAndLoadIframe(iframe, serverName, quality, subtitleCallback, callback)
                } catch (e: Exception) {
                    println("AnimeSail: Error processing server option ${element.text()}: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            println("AnimeSail: Error in loadLinks: ${e.message}")
            return false
        }
    }

    // Mencegat ekstraktor bawaan agar nama dan kualitas dari web asli tidak tertimpa
    private suspend fun loadExtractorWithCustomInfo(
        url: String,
        referer: String,
        serverName: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(
                    source = serverName,
                    name = serverName, 
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = if (link.quality == Qualities.Unknown.value) quality else link.quality
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }

    private suspend fun resolveAndLoadIframe(
        iframeUrl: String,
        serverName: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var currentUrl = iframeUrl

            if (currentUrl.contains("miiiixdrop.net", true)) {
                currentUrl = currentUrl.replace("miiiixdrop.net", "mixdrop.co")
            }

            // Tangani URL proxy (?url=...)
            if (currentUrl.contains("url=")) {
                val targetUrl = Regex("url=([^&]+)").find(currentUrl)?.groupValues?.getOrNull(1)?.let {
                    URLDecoder.decode(it, "UTF-8")
                }
                if (!targetUrl.isNullOrBlank()) {
                    resolveAndLoadIframe(targetUrl, serverName, quality, subtitleCallback, callback)
                    return
                }
            }

            // Tangani Pixeldrain secara langsung
            if (currentUrl.contains("pixeldrain.com")) {
                val fileId = Regex("pixeldrain\\.com/(?:d|u|api/file)/([a-zA-Z0-9]+)").find(currentUrl)?.groupValues?.getOrNull(1)
                if (fileId != null) {
                    callback.invoke(
                        newExtractorLink(serverName, serverName, "https://pixeldrain.com/api/file/$fileId", ExtractorLinkType.VIDEO) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality
                        }
                    )
                    return
                }
            }

            // Daftar domain server yang diperluas (termasuk buzi/buzzheavier, dodo, dll)
            val knownExtractors = listOf(
                "mixdrop", "mp4upload", "krakenfiles", "dood", "dodo", "filemoon", 
                "mega.nz", "abyss", "acefile", "vikingfile", "buzi", "buzzheavier",
                "lokal", "streamtape", "gdrive", "ok.ru", "vidhide", "embed"
            )

            // JIKA URL BUKAN DARI ANIMESAIL (Link Luar), LANGSUNG GUNAKAN EKSTRAKTOR BAWAAN
            if (knownExtractors.any { currentUrl.contains(it, true) } || !currentUrl.contains(mainUrl, true)) {
                loadExtractorWithCustomInfo(currentUrl, mainUrl, serverName, quality, subtitleCallback, callback)
                return
            }

            // JIKA URL IFRAME DARI ANIMESAIL, COBA AMBIL HTML
            val res = request(currentUrl, mainUrl)
            val doc = res.document
            val html = res.text

            // Cek Pixeldrain di dalam HTML
            if (html.contains("pixeldrain.com")) {
                val fileId = Regex("pixeldrain\\.com/(?:d|u|api/file)/([a-zA-Z0-9]+)").find(html)?.groupValues?.getOrNull(1)
                if (fileId != null) {
                    callback.invoke(
                        newExtractorLink(serverName, serverName, "https://pixeldrain.com/api/file/$fileId", ExtractorLinkType.VIDEO) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality
                        }
                    )
                    return
                }
            }

            // Cek link video langsung (.mp4 / .m3u8)
            val videoSrc = doc.selectFirst("video source, video")?.attr("src") 
                ?: Regex("""file\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(html)?.groupValues?.getOrNull(1)

            if (!videoSrc.isNullOrBlank()) {
                val finalVideoUrl = fixUrl(videoSrc)
                val type = if (finalVideoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(serverName, serverName, finalVideoUrl, type) {
                        this.referer = currentUrl
                        this.quality = quality
                    }
                )
                return
            }

            // Cek iframe bersarang (nested iframe)
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")
            if (!innerIframe.isNullOrBlank()) {
                val fixedInner = fixUrl(innerIframe)
                resolveAndLoadIframe(fixedInner, serverName, quality, subtitleCallback, callback)
                return
            }

            // Fallback ke ekstraktor
            loadExtractorWithCustomInfo(currentUrl, mainUrl, serverName, quality, subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("AnimeSail: Error resolving iframe $iframeUrl: ${e.message}")
            // KUNCI PERBAIKAN: Jika request HTML gagal (misal diblokir Cloudflare atau Error 403),
            // sistem TETAP AKAN memaksa menggunakan loadExtractor agar server tidak hilang dari daftar!
            loadExtractorWithCustomInfo(iframeUrl, mainUrl, serverName, quality, subtitleCallback, callback)
        }
    }
}
