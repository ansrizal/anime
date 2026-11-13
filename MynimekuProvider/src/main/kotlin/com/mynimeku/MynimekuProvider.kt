package com.mynimeku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class MynimekuProvider : MainAPI() {
    override var mainUrl = "https://www.mynimeku.com"
    override var name = "Mynimeku"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "latest-series/page/%d/" to "Series Terbaru",
        "full-list/mix/o:popular/page/%d/" to "Popular Series",
        "a-z-list/page/%d/" to "List Anime",
        "full-list/mix/s:completed~t:BD,LA,MOVIE,MUSIC,ONA,OVA,SPECIAL,TV/page/%d/" to "Completed Series",
        "full-list/mix/s:on-going~t:BD,LA,MOVIE,MUSIC,ONA,OVA,SPECIAL,TV/page/%d/" to "On-Going Series",
        "full-list/mix/t:MOVIE/page/%d/" to "Anime Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        val url = if (page <= 1) {
            val cleanPath = path.replace("/page/1/", "/").replace("page/1/", "")
            "$mainUrl/$cleanPath"
        } else {
            "$mainUrl/$path"
        }

        val doc = app.get(url).document
        val home = doc.select(".mynimeku-update-widget__item, article.mynimeku-search-feed__item, article, div.bsx, div.item")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst(".mynimeku-update-widget__series-title, .mynimeku-search-feed__series-title, .mynimeku-update-widget__cover, .mynimeku-search-feed__cover, h2 a, h3 a, a[href*='/series/'], a[href*='/komik/']")
            ?: selectFirst("a[href]")
            ?: return null

        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href.isBlank() || href.contains("/page/")) return null

        // Skip comic/manga
        val typeStr = selectFirst(".mynimeku-update-widget__type, .mynimeku-search-feed__type, .typez")?.text().orEmpty().trim().uppercase()
        if (typeStr.contains("MANGA") || typeStr.contains("MANHWA") || typeStr.contains("MANHUA") || typeStr.contains("DOUJINSHI") || typeStr.contains("ONE-SHOT") || href.contains("/komik/")) {
            return null
        }

        val title = selectFirst(".mynimeku-update-widget__series-title, .mynimeku-search-feed__series-title, h2, h3")?.text()?.trim()
            ?: a.attr("title").ifBlank { null }
            ?: selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        val cleanTitle = title
            .replace(Regex("(?i)Episode\\s*\\d+"), "")
            .replace(Regex("(?i)Subtitle\\s+Indonesia"), "")
            .replace(Regex("(?i)Sub\\s+Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val img = selectFirst("img")
        val rawImg = img?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("data-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("src")

        val posterUrl = fixImageUrl(rawImg)

        val tvType = when {
            href.contains("/movie/") || typeStr.contains("MOVIE") || cleanTitle.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            typeStr.contains("OVA") || typeStr.contains("SPECIAL") -> TvType.OVA
            else -> TvType.Anime
        }

        val epNum = selectFirst(".mynimeku-update-widget__latest-pill, .epx, .mynimeku-search-feed__latest")?.text()
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: selectFirst("a[href*='/episode/']")?.attr("href")
                ?.let { Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: Regex("""(?i)Episode\s*(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val statusText = selectFirst(".mynimeku-update-widget__status, .mynimeku-search-feed__status")?.text()?.trim().orEmpty()

        return newAnimeSearchResponse(cleanTitle, href, tvType) {
            this.posterUrl = posterUrl
            if (epNum != null) {
                addSub(epNum)
            }
            val metaInfo = listOfNotNull(
                typeStr.ifBlank { null },
                statusText.ifBlank { null }
            ).joinToString(" • ")
            if (metaInfo.isNotBlank()) {
                this.otherName = metaInfo
            }
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url == null || url.startsWith("data:")) return null
        val cleanUrl = url.substringBefore("?")
        return fixUrlNull(cleanUrl)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val doc = try {
            app.get(url).document
        } catch (_: Exception) {
            app.get("$mainUrl/?s=$query").document
        }

        return doc.select("article.mynimeku-search-feed__item, .mynimeku-update-widget__item, article, a.live-search-card")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        var animeUrl = fixUrl(url)
        var doc = app.get(animeUrl).document

        // If user navigated via an episode link, find parent series link
        if (!animeUrl.contains("/series/")) {
            val parentLink = doc.selectFirst("a.mynimeku-episode-chip[href*='/series/'], .ts-breadcrumb a[href*='/series/'], .naveps a[href*='/series/']")?.attr("href")
            if (parentLink != null) {
                animeUrl = fixUrl(parentLink)
                doc = app.get(animeUrl).document
            }
        }

        // --- PERBAIKAN SELECTOR TITLE ---
        val title = doc.selectFirst("h1.komik-series-hero__title")?.text()?.trim()
            ?: doc.selectFirst("article.komik-series-page h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()?.removeSuffix(" - MyNimeku")
            ?: "Unknown"

        val poster = fixImageUrl(
            doc.selectFirst("div.komik-series-hero__cover img")?.attr("data-lazy-src")
                ?: doc.selectFirst("div.komik-series-hero__cover img")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val synopsis = doc.select("div.komik-series-entry p, div.komik-series-hero__synopsis p, div.entry-content p")
            .text().trim()

        // --- PERBAIKAN SELECTOR GENRE ---
        val genres = doc.select("div.komik-series-taxonomy__terms a, div.komik-series-taxonomy__row a, .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        var rating: Double? = null
        var year: Int? = null
        var status = ShowStatus.Completed

        doc.select("table.komik-series-table tr").forEach { tr ->
            val label = tr.selectFirst("th")?.text()?.trim().orEmpty()
            val value = tr.selectFirst("td")?.text()?.trim().orEmpty()

            when {
                label.contains("Status", ignoreCase = true) -> {
                    if (value.contains("On-Going", ignoreCase = true) || value.contains("Ongoing", ignoreCase = true)) {
                        status = ShowStatus.Ongoing
                    }
                }
                label.contains("Rating", ignoreCase = true) -> {
                    rating = Regex("""(\d+(?:\.\d+)?)""").find(value)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                }
                label.contains("Years", ignoreCase = true) || label.contains("Release", ignoreCase = true) -> {
                    if (year == null) {
                        year = Regex("""\b(19|20)\d{2}\b""").find(value)?.value?.toIntOrNull()
                    }
                }
            }
        }

        val typeStr = doc.select("table.komik-series-table tr:contains(Type) td").text()
        val tvType = when {
            typeStr.contains("MOVIE", ignoreCase = true) || animeUrl.contains("/movie/") -> TvType.AnimeMovie
            typeStr.contains("OVA", ignoreCase = true) || typeStr.contains("SPECIAL", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = doc.select("div.komik-series-chapter-list a.komik-series-chapter-item, div.komik-series-chapter-list a")
            .mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epNumStr = a.selectFirst("span.komik-series-chapter-item__num")?.text()
                    ?: a.attr("data-episode-number").takeIf { it.isNotBlank() }
                    ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.getOrNull(1)

                val epNum = epNumStr?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                val epName = a.selectFirst("span.komik-series-chapter-item__title")?.text()?.trim()
                    ?: a.text().trim()
                val epDate = a.selectFirst("span.komik-series-chapter-item__date")?.text()?.trim()

                newEpisode(epHref) {
                    this.episode = epNum
                    this.name = epName
                    if (!epDate.isNullOrBlank()) {
                        this.description = "Rilis: $epDate"
                    }
                }
            }
            .distinctBy { it.data }
            .reversed()

        return newAnimeLoadResponse(title, animeUrl, tvType) {
            this.posterUrl = poster

            // Tampilkan genre di plot agar PASTI terlihat
            val genreText = if (genres.isNotEmpty()) {
                "Genre: ${genres.joinToString(", ")}\n\n$synopsis"
            } else {
                synopsis
            }

            this.plot = genreText
            this.tags = genres
            this.year = year
            this.showStatus = status
            this.score = Score.from10(rating)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val servers = mutableListOf<Pair<String, String>>()

        // 1. Episode Player buttons
        doc.select("button.mynimeku-episode-server-btn[data-player-url]").forEach { btn ->
            // Bersihkan URL dari HTML entity &#038; menjadi &
            val rawUrl = btn.attr("data-player-url").replace("&#038;", "&")
            val playerUrl = fixUrlNull(rawUrl) ?: return@forEach
            
            // Ambil teks kualitas (misal: "CLOUD 360P")
            val qualityText = btn.text().trim()
            // Ambil tipe server (public/private)
            val serverType = btn.attr("data-player-type").trim()
            
            // Format nama menjadi: (public) CLOUD 360P
            val finalLabel = if (serverType.isNotBlank()) {
                "($serverType) $qualityText"
            } else {
                qualityText
            }

            servers.add(finalLabel to playerUrl)
        }

        // 2. Download links
        doc.select(".mynimeku-episode-download-card").forEach { card ->
            val res = card.selectFirst(".mynimeku-episode-download-card__res")?.text()?.trim() ?: "Download"
            card.select(".mynimeku-episode-download-card__links a[href]").forEach { a ->
                val linkUrl = fixUrlNull(a.attr("href")) ?: return@forEach
                val host = a.text().trim()
                val label = "Download $res - $host"
                servers.add(label to linkUrl)
            }
        }

        // 3. Fallback iframe / embed elements in document
        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            servers.add("Embed" to src)
        }

        servers.distinctBy { it.second }.forEach { (serverName, serverUrl) ->
            extractVideoLink(serverName, serverUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun extractVideoLink(
        serverName: String,
        serverUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
        )

        // 1. Ambil kualitas (misal: 360, 480, 720)
        val detectedQuality = getQualityFromName(serverName)
        
        // 2. Ambil nama sumber (CLOUD, DRIVE, atau PROXY)
        // Kita buang tanda kurung dan ambil kata pertama
        val cleanName = serverName.replace(Regex("""\(.*?\)"""), "").trim() // Hasil: "CLOUD 360P"
        val sourceName = cleanName.substringBefore(" ").uppercase() // Hasil: "CLOUD"

        try {
            // Coba gunakan extractor bawaan Cloudstream
            val loaded = loadExtractor(serverUrl, referer, subtitleCallback) { link ->
                runBlocking {
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName, // <-- Gunakan CLOUD/DRIVE/PROXY
                            name = serverName,
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.headers = link.headers
                            this.quality = if (detectedQuality != Qualities.Unknown.value) detectedQuality else link.quality
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
            if (loaded) return

            // Jika gagal, coba ambil HTML dari URL player secara manual
            val res = app.get(serverUrl, headers = headers, allowRedirects = true)
            val targetUrl = res.url
            val responseText = res.text

            // Ekstrak URL Base64 dari MyPlayerKu (jika ada)
            val base64Regex = Regex("""myplayerku\.my\.id/(?:public|drive|private|proxy)/([A-Za-z0-9+/=]+)""")
            val base64Match = base64Regex.find(serverUrl)
            
            if (base64Match != null) {
                // Decode Base64 untuk mendapatkan ID video asli
                val encodedId = base64Match.groupValues[1]
                val decodedId = try {
                    String(android.util.Base64.decode(encodedId, android.util.Base64.DEFAULT))
                } catch (e: Exception) {
                    ""
                }

                if (decodedId.isNotBlank()) {
                    // Coba tembak API MyPlayerKu
                    val apiUrl = "https://players.myplayerku.my.id/api/v1/video?id=$decodedId"
                    val apiRes = app.get(apiUrl, headers = headers).text
                    
                    // Cari link m3u8 di response API
                    val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    val m3u8Match = m3u8Regex.find(apiRes)
                    if (m3u8Match != null) {
                        val m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
                        M3u8Helper.generateM3u8(
                            sourceName,
                            m3u8Url,
                            referer = "https://players.myplayerku.my.id/",
                            headers = headers
                        ).forEach { link ->
                            link.quality = detectedQuality
                            callback.invoke(link)
                        }
                        return
                    }
                }
            }

            // Fallback jika API gagal: cari iframe di dalam HTML player
            val playerDoc = Jsoup.parse(responseText)
            var found = false
            
            playerDoc.select("iframe[src]").forEach { iframe ->
                val iframeUrl = fixUrlNull(iframe.attr("src")) ?: return@forEach
                if (loadExtractor(iframeUrl, targetUrl, subtitleCallback, callback)) {
                    found = true
                }
            }

            // Fallback terakhir: cari .m3u8 atau .mp4 di HTML
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            m3u8Regex.findAll(responseText).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                M3u8Helper.generateM3u8(
                    sourceName,
                    m3u8Url,
                    referer = targetUrl,
                    headers = headers
                ).forEach { link ->
                    link.quality = detectedQuality
                    callback.invoke(link)
                }
                found = true
            }

            val mp4Regex = Regex("""["'](https?://[^"']+\.(?:mp4|mkv|webm)[^"']*)["']""")
            mp4Regex.findAll(responseText).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = sourceName,
                        name = serverName,
                        url = videoUrl
                    ) {
                        this.referer = targetUrl
                        this.quality = detectedQuality
                    }
                )
                found = true
            }

            if (!found) {
                // Jika benar-benar tidak ada, lemparkan URL player mentah
                // Kadang Cloudstream bisa memutarnya jika tipenya didukung
                callback.invoke(
                    newExtractorLink(
                        source = sourceName,
                        name = serverName,
                        url = serverUrl
                    ) {
                        this.referer = referer
                        this.quality = detectedQuality
                    }
                )
            }

        } catch (_: Exception) {
            // Jangan abaikan error, tapi tetap kirim URL agar user bisa mencoba "Buka di Browser"
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = serverName,
                    url = serverUrl
                ) {
                    this.referer = referer
                    this.quality = detectedQuality
                }
            )
        }
    }

    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720", ignoreCase = true) -> Qualities.P720.value
            name.contains("480", ignoreCase = true) -> Qualities.P480.value
            name.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
