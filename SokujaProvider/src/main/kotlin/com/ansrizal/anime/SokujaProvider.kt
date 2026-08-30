package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder

class SokujaProvider : MainAPI() {
    override var mainUrl = "https://x6.sokuja.uk"
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
        if (url == null) return null
        if (url.contains("/_next/image") || url.contains("url=")) {
            Regex("""url=([^&]+)""").find(url)?.groupValues?.get(1)?.let {
                return URLDecoder.decode(it, "UTF-8")
            }
        }
        return fixUrlNull(url)
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

    // [PERBAIKAN KATEGORI] - Daftar Genre Lengkap sesuai web
    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime/" to "Daftar Anime",
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

    // [PERBAIKAN PAGINATION] - Update Terbaru memakai ?page=N
    private fun buildPageUrl(path: String, page: Int): String {
        val base = mainUrl.removeSuffix("/")
        val cleanPath = path.trim('/')

        return when {
            path.isEmpty() -> if (page <= 1) base else "$base/?page=$page" // Fix untuk Update Terbaru
            page <= 1 -> "$base/$cleanPath"
            else -> "$base/$cleanPath/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = buildPageUrl(path, page)

        val res = request(url)
        val document = res.document

        val selectors = listOf(
            "a.group.block", "div.bsx", "div.listupd article", "div.utao", "div.uta"
        )

        val potentialItems = mutableListOf<Element>()
        for (sel in selectors) {
            potentialItems.addAll(document.select(sel))
        }

        val homeItems = potentialItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = if (tagName() == "a" && hasAttr("href")) this else selectFirst("a[href]")
        if (linkElement == null) return null
        
        val href = fixUrl(linkElement.attr("href"))

        val invalid = listOf("/genre/", "/category/", "/page/", "/bookmark/", "/jadwal-rilis/", "/tag/")
        if (invalid.any { href.contains(it) }) return null
        if (href.removeSuffix("/") == mainUrl.removeSuffix("/")) return null

        val title = selectFirst("h3, h2, .tt, .title, .entry-title")?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: return null

        if (title.isEmpty()) return null

        val lowerTitle = title.lowercase()
        if (lowerTitle == "daftar anime" || lowerTitle == "update terbaru" || lowerTitle.contains("genre")) {
            return null
        }

        // [PERBAIKAN GAMBAR] - Mencari di img tag, atau script data jika diperlukan
        val img = selectFirst("img")
        val rawImg = img?.attr("src") 
            ?: img?.attr("data-src")
            ?: img?.attr("data-lazy-src")

        val posterUrl = fixImageUrl(rawImg)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = request(searchUrl).document

        val selectors = listOf(
            "a.group.block", "div.bsx", "div.listupd article", "div.utao", "div.uta", "div.luf",
            "article.bs", "div.animposx", "div.bs", "div.animepost"
        )
        val items = mutableListOf<Element>()
        for (sel in selectors) {
            items.addAll(document.select(sel))
        }

        val finalItems = if (items.isEmpty()) {
            document.select("a[href*='/anime/']")
                .toList()
                .filter { !it.attr("href").contains("/genre/") && !it.attr("href").contains("/page/") }
                .mapNotNull { a ->
                    var parent = a.parent()
                    for (i in 0..5) {
                        if (parent != null && parent.selectFirst("img") != null) {
                            return@mapNotNull parent
                        }
                        parent = parent?.parent()
                    }
                    null
                }.distinctBy { it.outerHtml() }
        } else items

        return finalItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1")?.text()
            ?.replace("Subtitle Indonesia", "")
            ?.trim() ?: "Sokuja Anime"

        val rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[alt*='$title']")?.attr("src")
        val poster = fixImageUrl(rawPoster)

        val description = document.selectFirst("p.leading-relaxed")?.text()?.trim()

        // [PERBAIKAN EPISODE] - Parsing dari JSON di script tags
        val episodes = mutableListOf<Episode>()
        val scriptData = document.select("script").joinToString { it.data() }
        
        // Regex untuk mencari pola episode dalam JSON Next.js
        val epRegex = Regex("""\{"id":(\d+),"slug":"([^"]+)","title":"([^"]+)","episodeNumber":(\d+)""")
        epRegex.findAll(scriptData).forEach { match ->
            val id = match.groupValues[1]
            val slug = match.groupValues[2]
            val epTitle = match.groupValues[3]
            val epNum = match.groupValues[4].toIntOrNull() ?: 0
            
            // Format URL: mainUrl + slug
            val epUrl = if (slug.startsWith("/")) "$mainUrl$slug" else "$mainUrl/$slug"
            
            episodes.add(newEpisode(epUrl) { // URL as data for legacy if needed, but ID is better for mirrors
                this.name = epTitle
                this.episode = epNum
                this.data = id // Store ID in data field
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // [PERBAIKAN LINK] - Menggunakan API mirrors
        val episodeId = data.toIntOrNull() ?: return false
        val mirrorRes = app.get("$mainUrl/api/video-mirrors?e=$episodeId", headers = defaultHeaders).parsedSafe<MirrorResponse>()
        
        mirrorRes?.mirrors?.forEach { mirror ->
            val url = mirror.embedUrl ?: return@forEach
            val quality = mirror.quality?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.Unknown.value
            
            if (mirror.embedType == "mp4" || url.endsWith(".mp4")) {
                callback(
                    newExtractorLink(
                        mirror.serverName ?: name,
                        mirror.serverName ?: name,
                        url
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
            } else {
                loadExtractor(url, subtitleCallback, callback)
            }
        }

        return true
    }
}
