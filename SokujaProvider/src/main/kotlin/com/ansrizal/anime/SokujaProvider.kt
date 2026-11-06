package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

class SokujaProvider : MainAPI() {
    override var mainUrl = "https://sokuja.net"
    override var name = "Sokuja Anime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ========== CACHE ==========
    private data class CachedAnimeData(
        val title: String,
        val poster: String?,
        val plot: String?,
        val episodes: List<Episode>,
        val type: TvType,
        val tags: List<String>? = null
    )
    private val animeCache = mutableMapOf<String, CachedAnimeData>()
    private val linkCache = mutableMapOf<String, List<ExtractorLink>>()
    // ===========================

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private suspend fun request(url: String): NiceResponse {
        return app.get(url, headers = defaultHeaders, timeout = 10)
    }

    private fun fixImageUrl(url: String?): String? {
        if (url == null || url.startsWith("data:")) return null

        val decoded = if (url.contains("/_next/image") || url.contains("url=")) {
            Regex("""url=([^&]+)""").find(url)?.groupValues?.get(1)?.let {
                URLDecoder.decode(it, "UTF-8")
            } ?: url
        } else {
            url
        }

        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("/") -> "${mainUrl.removeSuffix("/")}$decoded"
            else -> decoded
        }
    }

    // [DATA MODELS]
    data class Mirror(
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("embedType") val embedType: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class MirrorResponse(
        @JsonProperty("mirrors") val mirrors: List<Mirror>? = null
    )

    // [DAFTAR KATEGORI & GENRE]
    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime/?type=movie&order=update" to "Movie Terbaru",
        "anime/?order=title_az" to "Daftar Anime"
    )

    private fun buildPageUrl(path: String, page: Int): String {
        val base = mainUrl.removeSuffix("/")
        val cleanPath = path.trim('/')

        return when {
            path.isEmpty() -> if (page <= 1) base else "$base/?page=$page"
            page <= 1 -> "$base/$cleanPath"
            else -> {
                if (cleanPath.contains("?")) "$base/$cleanPath&page=$page"
                else "$base/$cleanPath/page/$page/"
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = buildPageUrl(path, page)
        val isMovie = path.contains("type=movie")

        val res = request(url)
        val document = res.document

        val potentialItems = if (path.isEmpty()) {
            val updateSection = document.getElementById("S:1") ?: document.selectFirst("#update-terbaru")
            updateSection?.select("a.group.block") ?: document.select("a.group.block")
        } else {
            document.select("a.group.block, div.bsx, div.listupd article, div.utao, div.uta, div.animposx, div.bs")
        }

        val homeItems = potentialItems.mapNotNull { it.toSearchResult(isMovie) }
            .groupBy { it.url }
            .map { (_, results) ->
                results.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: results.first()
            }

        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(isMovieHint: Boolean = false): SearchResponse? {
        val linkElement = if (tagName() == "a" && hasAttr("href")) this else selectFirst("a[href]")
        if (linkElement == null) return null

        val href = fixUrl(linkElement.attr("href"))
        if (listOf("/genre/", "/category/", "/page/", "/bookmark/", "/jadwal-rilis/", "/tag/").any { href.contains(it) }) return null
        if (href.removeSuffix("/") == mainUrl.removeSuffix("/")) return null

        var title = selectFirst("h3, h2, .tt, .title, .entry-title")?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: return null

        if (title.isEmpty() || title.lowercase().let { it == "daftar anime" || it == "update terbaru" || it.contains("genre") }) return null

        val epText = selectFirst(".epx, .ep, .episode, .text-gray-400")?.text()?.trim()
        if (!epText.isNullOrBlank() && (epText.contains("Episode", true) || epText.contains("EP ", true))) {
            val cleanEp = epText.replace(Regex("""\s*·.*"""), "")
            if (!title.contains(cleanEp, true)) title = "$title - $cleanEp"
        }

        val img = selectFirst("img")
        val rawImg = img?.attr("src").takeIf { !it.isNullOrBlank() && !it.startsWith("data:") }
            ?: img?.attr("data-lazy-src") ?: img?.attr("data-src")
            ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()

        val type = if (isMovieHint || title.lowercase().contains("movie") || href.contains("movie")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = fixImageUrl(rawImg)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = request(searchUrl).document
        val items = document.select("a.group.block, div.bsx, div.listupd article, div.utao, div.uta, div.luf, article.bs, div.animposx, div.bs, div.animepost")

        return items.mapNotNull { it.toSearchResult() }
            .groupBy { it.url }
            .map { (_, results) ->
                results.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: results.first()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        var currentUrl = url
        var res = request(currentUrl)
        var document = res.document

        // [REDIRECT LOGIC] Perbaikan: Cek URL yang lebih luas (termasuk yang berakhiran angka)
        val isEpisodePage = currentUrl.contains("-episode-") || 
                            currentUrl.contains("/episode/") || 
                            Regex("""/\d+/?$""").containsMatchIn(currentUrl)

        if (isEpisodePage && !currentUrl.contains("/anime/")) {
            val seriesLink = document.selectFirst("main a[href*='/anime/']:not([href*='list-mode'])")?.attr("href")
                ?: document.selectFirst(".breadcrumb a[href*='/anime/']")?.attr("href")
                ?: document.selectFirst("a[href*='/anime/']")?.attr("href")

            if (seriesLink != null) {
                currentUrl = fixUrl(seriesLink)
                res = request(currentUrl)
                document = res.document
            }
        }

        // ----- CEK CACHE -----
        animeCache[currentUrl]?.let { cached ->
            return newAnimeLoadResponse(cached.title, currentUrl, cached.type) {
                this.posterUrl = cached.poster
                this.plot = cached.plot
                this.tags = cached.tags
                addEpisodes(DubStatus.Subbed, cached.episodes)
            }
        }

        // ----- PARSING DATA UTAMA -----
        val title = document.selectFirst("h1")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: "Sokuja Anime"
        val rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("img[alt*='$title']")?.attr("src")
        val poster = fixImageUrl(rawPoster)

        // Sinopsis
        val description = document.selectFirst("div.prose.prose-invert, div.prose-invert, div.prose")?.text()?.trim()
            ?: document.selectFirst("div.sinopsis, div.synopsis, div.desc, p.leading-relaxed, .entry-content p, .desc")?.text()?.trim()

        // Genre
        val genreElements = document.select("div.flex.flex-wrap.gap-2 a[href*='/genre/']")
        val tags = genreElements.mapNotNull { it.text().trim().ifEmpty { null } }.distinct()
            .ifEmpty {
                document.select("div.rounded-xl.bg-sokuja-card.p-4 a[href*='/genre/']")
                    .mapNotNull { it.text().trim().ifEmpty { null } }.distinct()
            }

        // ----- AMBIL DAFTAR EPISODE (PERBAIKAN UTAMA) -----
        val episodes = mutableListOf<Episode>()
        val scriptData = document.select("script").joinToString { it.data() }.replace("\\\"", "\"").replace("\\\\", "\\")

        // 1. Coba parsing menggunakan struktur JSON Next.js (__NEXT_DATA__)
        try {
            val startIndex = scriptData.indexOf("\"episodes\":")
            if (startIndex != -1) {
                val jsonArrayStr = scriptData.substring(startIndex).substringAfter("[").substringBefore("]")
                Regex("""\{[^{}]*?"slug"\s*:\s*"([^"]+)"[^{}]*?"episodeNumber"\s*:\s*(\d+)[^{}]*?\}""").findAll(jsonArrayStr).forEach { match ->
                    val slug = match.groupValues[1]
                    val epNum = match.groupValues[2].toIntOrNull() ?: 0
                    val epUrl = if (slug.startsWith("/")) "$mainUrl$slug" else "$mainUrl/$slug"
                    
                    episodes.add(newEpisode(epUrl) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    })
                }
            }
        } catch (e: Exception) {
            // Abaikan error parsing JSON
        }

        // 2. Fallback ke Regex lama jika JSON gagal
        if (episodes.isEmpty()) {
            Regex("""["']slug["']:\s*["']([^"']+)["'][^{}]*?["']episodeNumber["']:\s*(\d+)""").findAll(scriptData).forEach { match ->
                val slug = match.groupValues[1]
                val epNum = match.groupValues[2].toIntOrNull() ?: 0
                val epUrl = if (slug.startsWith("/")) "$mainUrl$slug" else "$mainUrl/$slug"
                episodes.add(newEpisode(epUrl) { this.name = "Episode $epNum"; this.episode = epNum })
            }
        }

        // 3. Fallback ke HTML DOM
        if (episodes.isEmpty()) {
            document.select("a[href*='-episode-'], .eplist ul li a, div.grid a[href*='/episode/']").forEach { a ->
                val href = a.attr("href")
                if (href.isNotBlank()) {
                    val epNum = Regex("""(?:episode|ep)[-/\s]*(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""/(\d+)/?$""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    
                    if (epNum != null) {
                        episodes.add(newEpisode(fixUrl(href)) { 
                            this.name = a.text().trim().ifEmpty { "Episode $epNum" }
                            this.episode = epNum 
                        })
                    }
                }
            }
        }

        val sortedEpisodes = episodes.distinctBy { it.episode }.sortedBy { it.episode ?: 0 }
        val type = if (currentUrl.contains("movie") || title.lowercase().contains("movie")) TvType.AnimeMovie else TvType.Anime

        // ----- SIMPAN KE CACHE -----
        animeCache[currentUrl] = CachedAnimeData(title, poster, description, sortedEpisodes, type, tags)

        return newAnimeLoadResponse(title, currentUrl, type) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ----- CEK CACHE LINK MP4 -----
        linkCache[data]?.let { cachedLinks ->
            cachedLinks.forEach { callback(it) }
            return true
        }

        var episodeId = data.toIntOrNull()
        val collectedLinks = mutableListOf<ExtractorLink>()

        if (episodeId == null && data.startsWith("http")) {
            val doc = request(data).document
            val scriptData = doc.select("script").joinToString { it.data() }.replace("\\\"", "\"")
            episodeId = Regex("""["']id["']:\s*(\d+)""").find(scriptData)?.groupValues?.get(1)?.toIntOrNull()

            doc.select("iframe, .video-content iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src") ?: iframe.attr("data-src"))
                if (src.isNotEmpty() && !src.contains("google")) {
                    // PERBAIKAN: Tambahkan referer agar ekstraktor tidak diblokir
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        if (episodeId != null) {
            val mirrorRes = app.get("$mainUrl/api/video-mirrors?e=$episodeId", headers = defaultHeaders).parsedSafe<MirrorResponse>()
            mirrorRes?.mirrors?.forEach { mirror ->
                val url = mirror.embedUrl ?: return@forEach
                val quality = mirror.quality?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.Unknown.value

                if (mirror.embedType == "mp4" || url.endsWith(".mp4")) {
                    val link = newExtractorLink(mirror.serverName ?: name, mirror.serverName ?: name, url) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                    collectedLinks.add(link)
                    callback(link)
                } else {
                    // PERBAIKAN: Tambahkan referer agar ekstraktor tidak diblokir
                    loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        // ----- SIMPAN LINK MP4 KE CACHE -----
        if (collectedLinks.isNotEmpty()) {
            linkCache[data] = collectedLinks
        }

        return true
    }
}
