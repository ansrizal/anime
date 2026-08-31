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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private suspend fun request(url: String): NiceResponse {
        return app.get(url, headers = defaultHeaders, timeout = 30)
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
        "anime/?order=title_az" to "Daftar Anime",
        "genre/action/" to "Action",
        "genre/adult-cast/" to "Adult Cast",
        "genre/adventure/" to "Adventure",
        "genre/anthropomorphic/" to "Anthropomorphic",
        "genre/apocalyptic-battle/" to "Apocalyptic Battle",
        "genre/avant-garde/" to "Avant Garde",
        "genre/award-winning/" to "Award Winning",
        "genre/battle/" to "Battle",
        "genre/cgdct/" to "CGDCT",
        "genre/childcare/" to "Childcare",
        "genre/comedy/" to "Comedy",
        "genre/curse-exorcism/" to "Curse Exorcism",
        "genre/dark-fantasy/" to "Dark Fantasy",
        "genre/dark-humor/" to "Dark Humor",
        "genre/delinquents/" to "Delinquents",
        "genre/detective/" to "Detective",
        "genre/drama/" to "Drama",
        "genre/ecchi/" to "Ecchi",
        "genre/emotional-journey/" to "Emotional Journey",
        "genre/erotica/" to "Erotica",
        "genre/fantasy/" to "Fantasy",
        "genre/fire-brigade/" to "Fire Brigade",
        "genre/gag-humor/" to "Gag Humor",
        "genre/girls-love/" to "Girls Love",
        "genre/gore/" to "Gore",
        "genre/gourmet/" to "Gourmet",
        "genre/harem/" to "Harem",
        "genre/hentai/" to "Hentai",
        "genre/hero-unit/" to "Hero Unit",
        "genre/high-stakes-game/" to "High Stakes Game",
        "genre/historical/" to "Historical",
        "genre/horror/" to "Horror",
        "genre/idol/" to "Idol",
        "genre/idols-female/" to "Idols (Female)",
        "genre/isekai/" to "Isekai",
        "genre/iyashikei/" to "Iyashikei",
        "genre/josei/" to "Josei",
        "genre/kids/" to "Kids",
        "genre/love-polygon/" to "Love Polygon",
        "genre/love-status-quo/" to "Love Status Quo",
        "genre/magic/" to "Magic",
        "genre/magical-sex-shift/" to "Magical Sex Shift",
        "genre/mahou-shoujo/" to "Mahou Shoujo",
        "genre/martial-arts/" to "Martial Arts",
        "genre/mecha/" to "Mecha",
        "genre/medical/" to "Medical",
        "genre/military/" to "Military",
        "genre/music/" to "Music",
        "genre/mystery/" to "Mystery",
        "genre/mythology/" to "Mythology",
        "genre/organized-crime/" to "Organized Crime",
        "genre/otaku-culture/" to "Otaku Culture",
        "genre/parody/" to "Parody",
        "genre/performing-arts/" to "Performing Arts",
        "genre/pets/" to "Pets",
        "genre/prison/" to "Prison",
        "genre/psychological/" to "Psychological",
        "genre/psychological-thriller/" to "Psychological Thriller",
        "genre/racing/" to "Racing",
        "genre/reincarnation/" to "Reincarnation",
        "genre/romance/" to "Romance",
        "genre/romantic-subtext/" to "Romantic Subtext",
        "genre/samurai/" to "Samurai",
        "genre/school/" to "School",
        "genre/sci-fi/" to "Sci-Fi",
        "genre/seinen/" to "Seinen",
        "genre/shoujo/" to "Shoujo",
        "genre/shounen/" to "Shounen",
        "genre/showbiz/" to "Showbiz",
        "genre/slice-of-life/" to "Slice of Life",
        "genre/space/" to "Space",
        "genre/sports/" to "Sports",
        "genre/strategy-game/" to "Strategy Game",
        "genre/super-power/" to "Super Power",
        "genre/supernatural/" to "Supernatural",
        "genre/survival/" to "Survival",
        "genre/suspense/" to "Suspense",
        "genre/team-sports/" to "Team Sports",
        "genre/thriller-sosial/" to "Thriller Sosial",
        "genre/time-travel/" to "Time Travel",
        "genre/urban-fantasy/" to "Urban Fantasy",
        "genre/vampire/" to "Vampire",
        "genre/video-game/" to "Video Game",
        "genre/villainess/" to "Villainess",
        "genre/visual-arts/" to "Visual Arts",
        "genre/workplace/" to "Workplace"
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

        // PENTING: Gunakan TvType.Anime untuk Update Terbaru agar Cloudstream memicu fungsi load() 
        // dan kita bisa melakukan redirect ke halaman series utama.
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
        
        // [REDIRECT LOGIC] Jika ini halaman episode (Update Terbaru), cari link ke halaman series utama
        if (currentUrl.contains("-episode-") && !currentUrl.contains("/anime/")) {
            val seriesLink = document.selectFirst("main a[href*='/anime/']:not([href*='list-mode'])")?.attr("href")
                ?: document.selectFirst(".breadcrumb a[href*='/anime/']")?.attr("href")
            
            if (seriesLink != null) {
                currentUrl = fixUrl(seriesLink)
                res = request(currentUrl)
                document = res.document
            }
        }

        val rawData = document.select("script").joinToString { it.data() }.replace("\\\"", "\"").replace("\\\\", "\\")
        val title = document.selectFirst("h1")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: "Sokuja Anime"
        val rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("img[alt*='$title']")?.attr("src")
        val poster = fixImageUrl(rawPoster)
        val description = document.selectFirst("p.leading-relaxed, .entry-content p, .desc")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        Regex("""["']id["']:\s*(\d+)\s*,\s*["']slug["']:\s*["']([^"']+)["']\s*,\s*["']title["']:\s*["']([^"']+)["']\s*,\s*["']episodeNumber["']:\s*(\d+)""")
            .findAll(rawData).forEach { match ->
                val epUrl = if (match.groupValues[2].startsWith("/")) "$mainUrl${match.groupValues[2]}" else "$mainUrl/${match.groupValues[2]}"
                episodes.add(newEpisode(epUrl) {
                    this.name = match.groupValues[3]
                    this.episode = match.groupValues[4].toIntOrNull() ?: 0
                    this.data = match.groupValues[1]
                })
            }

        if (episodes.isEmpty()) {
            document.select("a[href*='-episode-'], .eplist ul li a").forEach { a ->
                val href = a.attr("href")
                val epNum = Regex("""episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null) episodes.add(newEpisode(fixUrl(href)) { this.name = a.text().trim(); this.episode = epNum })
            }
        }

        val type = if (currentUrl.contains("movie") || title.lowercase().contains("movie")) TvType.AnimeMovie else TvType.Anime
        
        return newAnimeLoadResponse(title, currentUrl, type) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode }.sortedByDescending { it.episode })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var episodeId = data.toIntOrNull()
        
        if (episodeId == null && data.startsWith("http")) {
            val doc = request(data).document
            val scriptData = doc.select("script").joinToString { it.data() }.replace("\\\"", "\"")
            episodeId = Regex("""["']id["']:\s*(\d+)""").find(scriptData)?.groupValues?.get(1)?.toIntOrNull()
            
            doc.select("iframe, .video-content iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src") ?: iframe.attr("data-src"))
                if (src.isNotEmpty() && !src.contains("google")) {
                    loadExtractor(src, subtitleCallback, callback)
                }
            }
        }

        if (episodeId != null) {
            val mirrorRes = app.get("$mainUrl/api/video-mirrors?e=$episodeId", headers = defaultHeaders).parsedSafe<MirrorResponse>()
            mirrorRes?.mirrors?.forEach { mirror ->
                val url = mirror.embedUrl ?: return@forEach
                val quality = mirror.quality?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.Unknown.value
                
                if (mirror.embedType == "mp4" || url.endsWith(".mp4")) {
                    callback(newExtractorLink(mirror.serverName ?: name, mirror.serverName ?: name, url) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    })
                } else {
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
