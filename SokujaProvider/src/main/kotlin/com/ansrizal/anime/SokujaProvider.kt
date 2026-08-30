package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse

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

    // Daftar Genre Lengkap Sesuai Web
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

    private fun buildPageUrl(path: String, page: Int): String {
        val base = mainUrl.removeSuffix("/")
        val cleanPath = path.trim('/')

        return when {
            path.isEmpty() -> if (page <= 1) base else "$base/?page=$page"
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
            "div.bsx", "div.listupd article", "div.utao", "div.uta", "div.luf",
            "article.bs", "div.post-show article", "div.bxb article",
            "div.animposx", "div.bs", "div.animepost"
        )

        val potentialItems = mutableListOf<Element>()
        for (sel in selectors) {
            val elements = document.select(sel)
            if (elements.isNotEmpty()) {
                potentialItems.addAll(elements)
            }
        }

        val homeItems = potentialItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElement.attr("href"))

        val invalid = listOf("/genre/", "/category/", "/page/", "/bookmark/", "/jadwal-rilis/", "/tag/")
        if (invalid.any { href.contains(it) }) return null
        if (href.removeSuffix("/") == mainUrl.removeSuffix("/")) return null

        val title = selectFirst(".tt, h2, h3, h4, .title, .entry-title, .post-title, .ttname, .anime-title, .judul, .name, .item-title")
            ?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.isEmpty()) return null

        val lowerTitle = title.lowercase()
        if (lowerTitle == "daftar anime" || lowerTitle == "update terbaru" || lowerTitle.contains("genre")) {
            return null
        }

        // Fix Gambar Abu-abu: Pengecekan atribut gambar & CSS background secara mendalam
        val imgEl = selectFirst("img")
        var rawImg = imgEl?.attr("data-lazy-src")
            ?.ifEmpty { imgEl.attr("data-src") }
            ?.ifEmpty { imgEl.attr("data-original") }
            ?.ifEmpty { imgEl.attr("src") }
            
        if (rawImg.isNullOrBlank() || rawImg.contains("data:image") || rawImg.contains("blank.gif")) {
            val styleAttr = selectFirst(".thumb, .poster, .img, div[style*='background']")?.attr("style") ?: ""
            rawImg = Regex("""url\(['"]?(.*?)['"]?\)""").find(styleAttr)?.groupValues?.get(1)
        }

        val posterUrl = fixUrlNull(rawImg)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = request(searchUrl).document

        val selectors = listOf("div.bsx", "div.listupd article", "article.bs", "div.animposx")
        val items = mutableListOf<Element>()
        for (sel in selectors) {
            items.addAll(document.select(sel))
        }

        return items.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val seriesLink = document.selectFirst("a:contains(Semua Episode), div.breadcrumb a[href*='/anime/'], div.ninfo a[href*='/anime/']")?.attr("href")
        val targetDoc = if (seriesLink != null && !url.contains("/anime/")) {
            request(fixUrl(seriesLink)).document
        } else {
            document
        }

        val title = targetDoc.selectFirst("h1.entry-title, h1, .post-title")?.text()
            ?.replace("Nonton Anime ", "")
            ?.replace("Subtitle Indonesia", "")
            ?.trim() ?: "Sokuja Anime"

        val imgEl = targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")
        val rawPoster = targetDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: imgEl?.attr("data-lazy-src")
            ?: imgEl?.attr("data-src")
            ?: imgEl?.attr("src")
        val poster = fixUrlNull(rawPoster)

        val description = targetDoc.selectFirst("div.sinopc, div.entry-content, div.synopsis, [itemprop=description]")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val episodeElements = targetDoc.select("div.eplister ul li, div.listeps ul li, ul.clist li")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEach { li ->
                val a = li.selectFirst("a") ?: return@forEach
                val epUrl = fixUrl(a.attr("href"))
                
                val epNumText = li.selectFirst(".epl-num, .epl-sub")?.text() ?: a.text()
                
                // Fix Episode Ngawur: Mengabaikan kata waktu (hari/minggu lalu) dan hanya mengekstrak nomor episode
                val epNum = Regex("""(?i)(?:episode|ep)\s*(\d+)""").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/episode-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val cleanName = "Episode $epNum"

                episodes.add(newEpisode(epUrl) {
                    this.name = cleanName
                    this.episode = epNum
                })
            }
        } else {
            targetDoc.select("a[href*='/episode/'], a[href*='episode-']").forEach { a ->
                val epUrl = fixUrl(a.attr("href"))
                val epNum = Regex("""(?i)(?:episode|ep)\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/episode-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                episodes.add(newEpisode(epUrl) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                })
            }
        }

        // Urutkan episode dari Episode 1 ke paling atas
        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document

        val sources = mutableListOf<String>()

        // 1. Keruk Iframe dan Embed
        document.select("iframe[src], iframe[data-src], embed[src]").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            if (src.isNotBlank()) sources.add(src)
        }

        // 2. Keruk elemen option pemutar/mirror
        document.select("select.mirror option, div.mirror-stream option, ul.mserver li a, div.server a").forEach { element ->
            val value = element.attr("value")
                .ifBlank { element.attr("data-em") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("href") }

            if (value.isNotBlank()) sources.add(value)
        }

        // 3. Ekstrak link pemutar video asli
        sources.distinct().forEach { rawUrl ->
            var cleanUrl = when {
                rawUrl.startsWith("//") -> "https:$rawUrl"
                rawUrl.startsWith("/") -> "$mainUrl$rawUrl"
                else -> rawUrl
            }

            if (cleanUrl.contains(mainUrl) && (cleanUrl.contains("/embed") || cleanUrl.contains("/player") || cleanUrl.contains("option="))) {
                try {
                    val embedDoc = request(cleanUrl).document
                    val innerIframe = embedDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!innerIframe.isNullOrBlank()) {
                        cleanUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                    }
                } catch (_: Exception) {}
            }

            if (cleanUrl.startsWith("http") && !cleanUrl.contains("facebook") && !cleanUrl.contains("disqus")) {
                loadExtractor(cleanUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
