package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.*
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
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
            ),
            referer = ref ?: mainUrl
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
            val document = request(url).document
            val items = document.select("article.bs, article.bsz").mapNotNull { 
                it.toSearchResult() 
            }.distinctBy { it.url }
            
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            println("AnimeSail: Error loading main page: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        
        if (href.isBlank() || href.contains("/page/")) return null

        val rawTitle = this.selectFirst(".tt h2")?.text()
            ?: a.attr("title")
            ?: return null

        // Clean title
        val title = rawTitle
            .replace(Regex("(?i)Episode\\s*\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .replace(Regex("\\(\\d{4}\\)"), "")  // Remove year in parentheses
            .trim()
            .removeSuffix("-")
            .trim()

        val img = this.selectFirst("img")
        val posterUrl = fixImageUrl(img?.attr("src"))

        // Detect type from class
        val type = when {
            this.hasClass("bsz") || href.contains("/movie/") -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        // Extract episode number from title
        val epNum = Regex("(?i)Episode\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        return try {
            val document = request(link).document
            document.select("article.bs, article.bsz").mapNotNull { 
                it.toSearchResult() 
            }.distinctBy { it.url }
        } catch (e: Exception) {
            println("AnimeSail: Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document
        
        // Get title from h1 or fallback
        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: document.title()
            ?: "AnimeSail"
        
        val cleanTitle = title
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()

        // Get poster
        val poster = fixImageUrl(
            document.selectFirst(".thumb img")?.attr("src")
                ?: document.selectFirst(".entry-content img")?.attr("src")
                ?: document.selectFirst(".post-thumbnail img")?.attr("src")
                ?: document.selectFirst("img.attachment-post-thumbnail")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        // Initialize metadata
        var type = TvType.Anime
        var year: Int? = null
        var status = ShowStatus.Completed
        var plot: String? = null
        val tags = mutableListOf<String>()

        // Parse info from table rows
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
                    status = if (td.lowercase().contains("ongoing") || td.lowercase().contains("airing") || td.lowercase().contains("ongoing")) 
                        ShowStatus.Ongoing 
                    else 
                        ShowStatus.Completed
                }
                th.contains("genre") || th.contains("genres") -> {
                    tags.addAll(td.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
                th.contains("durasi") || th.contains("duration") -> {
                    // Duration can be parsed here if needed
                }
            }
        }

        // Also try to get genres from links
        if (tags.isEmpty()) {
            document.select("a[href*='/genres/']").forEach { genreLink ->
                val genre = genreLink.text().trim()
                if (genre.isNotEmpty() && !tags.contains(genre)) {
                    tags.add(genre)
                }
            }
        }

        // Get plot/synopsis
        plot = document.selectFirst(".entry-content p")?.text()
            ?: document.selectFirst(".sinopsis")?.text()
            ?: document.selectFirst(".desc")?.text()
            ?: document.selectFirst(".entry-content")?.text()

        // Detect if it's a movie based on URL
        if (url.contains("/movie/")) {
            type = TvType.AnimeMovie
        }

        // Get episodes
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
        }.distinctBy { it.data }.sortedByDescending { it.episode }

        return newAnimeLoadResponse(cleanTitle, url, type) {
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
            
            document.select(".mobius > .mirror > option, select.mirror option").forEach { element ->
                val encodedData = element.attr("data-em")
                if (encodedData.isBlank()) return@forEach

                try {
                    val decoded = base64Decode(encodedData)
                    val iframe = fixUrl(Jsoup.parse(decoded).select("iframe").attr("src"))
                    if (iframe.isBlank() || iframe.contains("statistic")) return@forEach

                    val rawText = element.text().trim()
                    val quality = getIndexQuality(rawText)
                    val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar { 
                        if (it.isLowerCase()) it.titlecase() else it.toString() 
                    } ?: name

                    if (iframe.endsWith(".mp4") || iframe.endsWith(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = iframe,
                                type = if (iframe.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                    } else {
                        // Handle other iframe types
                        loadExtractor(iframe, data, subtitleCallback) { link ->
                            callback.invoke(
                                newExtractorLink(
                                    source = link.source,
                                    name = "$serverName - ${link.name}",
                                    url = link.url,
                                    type = link.type
                                ) {
                                    this.referer = link.referer
                                    this.quality = link.quality ?: quality
                                    this.headers = link.headers
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    println("AnimeSail: Error processing link: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            println("AnimeSail: Error in loadLinks: ${e.message}")
            return false
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
