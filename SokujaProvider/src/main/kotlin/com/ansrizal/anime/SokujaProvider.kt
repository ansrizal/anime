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

        val potentialItems = mutableListOf<Element>()
        val selectors = listOf(
            "div.bsx", "div.listupd article", "div.utao", "div.uta", "div.luf",
            "article.bs", "div.post-show article", "div.bxb article",
            "div.swiper-slide", "div.animposx", "div.bs", "div.animepost",
            "div.anime-list", "div.anime-item", "div.episodelist article",
            "div.item", "div.anime-card", "div.card", "div.thumb-item",
            "div.anime__item", "div.anime-card__inner"
        )

        for (sel in selectors) {
            val elements = document.select(sel)
            if (elements.isNotEmpty()) {
                potentialItems.addAll(elements)
            }
        }

        val finalItems = if (potentialItems.isEmpty()) {
            document.select("a[href*='/anime/']")
                .toList()
                .filter { a ->
                    val href = a.attr("href")
                    !href.contains("/genre/") && !href.contains("/category/") &&
                    !href.contains("/page/") && !href.contains("/tag/") &&
                    !href.contains("/bookmark/") && !href.contains("/jadwal-rilis/")
                }
                .mapNotNull { a ->
                    var parent = a.parent()
                    var container: Element? = a
                    for (i in 0..5) {
                        if (parent != null) {
                            if (parent.selectFirst("img") != null || parent.hasClass("bsx") || parent.hasClass("item") || parent.hasClass("post")) {
                                container = parent
                                break
                            }
                            parent = parent.parent()
                        }
                    }
                    container
                }.distinctBy { it?.outerHtml() }.filterNotNull()
        } else {
            potentialItems
        }

        val homeItems = finalItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        var href = fixUrl(linkElement.attr("href"))

        val invalid = listOf("/genre/", "/category/", "/page/", "/bookmark/", "/jadwal-rilis/", "/tag/")
        if (invalid.any { href.contains(it) }) return null
        if (href.removeSuffix("/") == mainUrl.removeSuffix("/")) return null

        val title = selectFirst(".tt, h2, h3, h4, .title, .entry-title, .post-title, .ttname, .anime-title, .judul, .name, .item-title")
            ?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: linkElement.text().trim().ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.isEmpty()) return null

        // [FILTER JUDUL MENU] - Mencegah "Daftar Anime" atau "Update Terbaru" terdeteksi sebagai judul anime
        val lowerTitle = title.lowercase()
        if (lowerTitle == "daftar anime" || lowerTitle == "update terbaru" || lowerTitle == "action" || lowerTitle == "isekai" || lowerTitle.contains("genre")) {
            return null
        }

        // [PERBAIKAN GAMBAR ABU-ABU] - Ambil dari data-original, data-lazy, dan background CSS
        val img = selectFirst("img, div.thumb, .thumb, .poster")
        var rawImg = img?.attr("data-original")
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("data-lazy-srcset")?.substringBefore(" ")
            ?: img?.attr("data-src")
            ?: img?.attr("data-poster")
            ?: img?.attr("src")
            ?: img?.attr("srcset")?.substringBefore(" ")
            ?: img?.selectFirst("img")?.attr("data-src")
            ?: img?.selectFirst("img")?.attr("src")
            
        if (rawImg.isNullOrBlank()) {
            rawImg = img?.attr("style")?.let { style ->
                Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            }
        }

        if (rawImg != null && (rawImg.startsWith("data:") || rawImg.contains("placeholder") || rawImg.contains("default") || rawImg.contains("spacer") || rawImg.contains("blank"))) {
            rawImg = null
        }

        rawImg = when {
            rawImg.isNullOrBlank() -> null
            rawImg.startsWith("//") -> "https:$rawImg"
            else -> rawImg
        }
        val posterUrl = fixUrlNull(rawImg)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = request(searchUrl).document

        val selectors = listOf(
            "div.bsx", "div.listupd article", "div.utao", "div.uta", "div.luf",
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

        val seriesLink = document.selectFirst(
            "a:contains(Semua Episode), div.breadcrumb a[href*='/anime/'], " +
            "div.ninfo a[href*='/anime/'], span.all-ep a, .all-episode a"
        )?.attr("href")

        val targetDoc = if (seriesLink != null && !url.contains("/anime/")) {
            val parentUrl = fixUrl(seriesLink)
            if (parentUrl != url) request(parentUrl).document else document
        } else {
            document
        }

        val title = targetDoc.selectFirst("h1.entry-title, h1, .infotable h1, .post-title, .anime-title")?.text()
            ?.replace("Nonton Anime ", "")
            ?.replace("Subtitle Indonesia", "")
            ?.trim() ?: "Sokuja Anime"

        var rawPoster = targetDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("data-original")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("data-src")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("src")
        rawPoster = if (rawPoster?.startsWith("//") == true) "https:$rawPoster" else rawPoster
        val poster = fixUrlNull(rawPoster)

        val description = targetDoc.selectFirst("div.sinopc, div.entry-content, div.synopsis, [itemprop=description], .deskripsi")?.text()?.trim()

        // [PERBAIKAN EPISODE] - Mencari semua container list episode dan fallback
        val episodes = mutableListOf<Episode>()
        
        val episodeContainer = targetDoc.selectFirst("div.eplister, div.listeps, div.eps, div.list-episode, div.episodelist, div.lstep, div.anime-episode")
        
        if (episodeContainer != null) {
            episodeContainer.select("a[href]").forEachIndexed { index, a ->
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.text().trim().ifEmpty { "Episode ${index + 1}" }
                
                val epNum = Regex("""Episode\s?(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                })
            }
        }

        if (episodes.isEmpty()) {
            targetDoc.select("a[href*='/episode/'], a[href*='/watch/'], a[href*='/stream/'], a[href*='episode-']")
                .forEachIndexed { index, a ->
                    val epUrl = fixUrl(a.attr("href"))
                    val epName = a.text().trim().ifEmpty { "Episode ${index + 1}" }
                    
                    val epNum = Regex("""Episode\s?(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""episode-(\d+)""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: (index + 1)

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    })
                }
        }

        if (episodes.isEmpty()) {
            val watchLink = targetDoc.selectFirst("a[href*='/episode/'], a[href*='/stream/'], a[href*='/watch/']")
            if (watchLink != null) {
                episodes.add(
                    newEpisode(fixUrl(watchLink.attr("href"))) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            } else {
                episodes.add(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.reversed())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Kumpulkan semua server URLs dari data-video attribute
        // Struktur: <a class="server" data-video="URL">Nama Server</a>
        val serverUrls = mutableListOf<String>()

        // Default server dari iframe src
        document.selectFirst("iframe#tontonin")?.attr("src")?.ifBlank { null }?.let {
            serverUrls.add(it)
        }

        // Server tambahan dari a.server[data-video]
        document.select("a.server[data-video]").asIterable().forEach { a ->
            val url = a.attr("data-video").ifBlank { null } ?: return@forEach
            if (!serverUrls.contains(url)) serverUrls.add(url)
        }

        // Load semua server
        serverUrls.forEach { url ->
            val fullUrl = if (url.startsWith("/")) "$mainUrl$url" else url
            if (fullUrl.contains("btube3.php")) {
                // Internal player (btube3.php) — ambil direct video URL dari <source> tag
                try {
                    val playerDoc = app.get(fullUrl).document
                    val videoSrc = playerDoc.selectFirst("source[src]")?.attr("src")
                        ?: playerDoc.selectFirst("video")?.attr("src")
                    if (!videoSrc.isNullOrBlank()) {
                        val itag = Regex("[?&]itag=(\\d+)").find(videoSrc)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val quality = when (itag) {
                            18 -> Qualities.P360.value
                            22 -> Qualities.P720.value
                            37 -> Qualities.P1080.value
                            59 -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                        callback(
                            newExtractorLink(
                                "AnimeIndo",
                                "B-TUBE",
                                videoSrc
                            ) {
                                this.quality = quality
                                this.referer = "https://www.blogger.com/"
                            }
                        )
                    }
                } catch (_: Exception) {}
            } else if (fullUrl.contains("xtwap.top")) {
                // CEPAT server — parse JWPlayer source dan extract HLS qualities
                try {
                    val html = app.get(fullUrl).text
                    val fileMatch = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    val filePath = fileMatch?.groupValues?.getOrNull(1)
                    if (!filePath.isNullOrBlank()) {
                        val masterUrl = if (filePath.startsWith("/")) "https://xtwap.top$filePath" else filePath
                        val links = M3u8Helper.generateM3u8("AnimeIndo", masterUrl, fullUrl)
                        if (links.isNotEmpty()) {
                            links.forEach { callback(it) }
                        } else {
                            callback(newExtractorLink("AnimeIndo", "CEPAT", masterUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = fullUrl
                            })
                        }
                    }
                } catch (_: Exception) {}
            } else {
                // External servers (blogger.com, gdplayer.to, dll)
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        // Download link dari .navi (biasanya GDrive)
        document.select("div.navi a[href]").asIterable().forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            if (href.startsWith("http") && !href.contains(mainUrl)) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
