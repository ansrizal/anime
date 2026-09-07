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
        "Referer" to "$mainUrl/"
    )

    private fun fixImageUrl(url: String?): String? {
        if (url == null || url.startsWith("data:")) return null
        if (url.contains("url=")) {
            val encodedUrl = url.substringAfter("url=").substringBefore("&")
            return try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (_: Exception) { encodedUrl }
        }
        return fixUrlNull(url)
    }

    private fun episodeToAnimeUrl(url: String): String {
        return if (url.contains("-episode-") && !url.contains("/anime/")) {
            val slug = url.trimEnd('/').substringAfterLast("/")
            val animeSlug = slug.replace(Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE), "")
            "$mainUrl/anime/$animeSlug/"
        } else url
    }

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        } else {
            if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}&page=$page"
        }

        val document = app.get(url, headers = defaultHeaders).document
        val items = document.select("a.group.block, div.bsx, div.listupd article, div.uta").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fixUrl(episodeToAnimeUrl(a.attr("href")))
        
        if (href == "$mainUrl/" || href.contains("/genre/") || href.contains("/category/")) return null

        var title = selectFirst("h3, h2, .tt, .title")?.text()?.trim() ?: a.attr("title")
        if (title.isNullOrBlank()) return null

        val epText = selectFirst(".epx, .ep, .episode")?.text()?.trim()
        if (!epText.isNullOrBlank() && epText.contains("Episode", true)) {
            val cleanEp = epText.substringBefore(" ·")
            if (!title.contains(cleanEp, true)) title = "$title - $cleanEp"
        }

        val img = selectFirst("img")
        val poster = img?.attr("data-src") ?: img?.attr("src") ?: img?.attr("data-lazy-src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixImageUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = defaultHeaders).document
        return document.select("a.group.block, div.bsx, div.listupd article, div.uta").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = episodeToAnimeUrl(url)
        val document = app.get(animeUrl, headers = defaultHeaders).document
        
        val title = document.selectFirst("h1")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: ""
        val poster = fixImageUrl(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.prose, .sinopsis, .synopsis, .desc")?.text()?.trim()
        val tags = document.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        val episodes = mutableListOf<Episode>()
        
        // Try parsing from script data (JSON) - Faster and more complete
        val scripts = document.select("script").joinToString { it.data() }
        Regex("""["']id["']:\s*(\d+)\s*,\s*["']slug["']:\s*["']([^"']+)["']\s*,\s*["']title["']:\s*["']([^"']+)["']\s*,\s*["']episodeNumber["']:\s*(\d+)""")
            .findAll(scripts).forEach { match ->
                val epUrl = fixUrl(match.groupValues[2])
                episodes.add(newEpisode(epUrl) {
                    this.name = match.groupValues[3]
                    this.episode = match.groupValues[4].toIntOrNull()
                    this.data = match.groupValues[1] // ID for direct mirror API
                })
            }

        // Fallback to HTML list
        if (episodes.isEmpty()) {
            document.select(".eplist ul li a, a[href*='-episode-']").forEach { a ->
                val href = a.attr("href")
                val epNum = Regex("""episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = a.text().trim()
                    this.episode = epNum
                })
            }
        }

        val sortedEpisodes = episodes.distinctBy { it.episode }.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
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
        var episodeId = data.toIntOrNull()
        
        if (episodeId == null && data.startsWith("http")) {
            val html = app.get(data, headers = defaultHeaders).text
            episodeId = Regex("""["']id["']:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            
            // Extract from iframes while at it
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src") ?: "")
                if (src.isNotEmpty() && !src.contains("google")) {
                    loadExtractor(src, subtitleCallback, callback)
                }
            }
        }

        if (episodeId != null) {
            val res = app.get("$mainUrl/api/video-mirrors?e=$episodeId", headers = defaultHeaders).parsedSafe<MirrorResponse>()
            res?.mirrors?.forEach { mirror ->
                val url = mirror.embedUrl ?: return@forEach
                if (mirror.embedType == "mp4" || url.endsWith(".mp4")) {
                    callback(newExtractorLink(mirror.serverName ?: name, mirror.serverName ?: name, url) {
                        this.referer = "$mainUrl/"
                        this.quality = mirror.quality?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.Unknown.value
                    })
                } else {
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class Mirror(@JsonProperty("serverName") val serverName: String?, @JsonProperty("embedUrl") val embedUrl: String?, @JsonProperty("embedType") val embedType: String?, @JsonProperty("quality") val quality: String?)
    data class MirrorResponse(@JsonProperty("mirrors") val mirrors: List<Mirror>?)
}
