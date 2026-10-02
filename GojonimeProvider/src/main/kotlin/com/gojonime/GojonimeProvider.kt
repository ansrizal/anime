package com.gojonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class GojonimeProvider : MainAPI() {
    override var mainUrl = "https://gojonime.net"
    override var name = "Gojonime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "on-going-anime/page/%d/" to "On-Going Anime",
        "completed-anime/page/%d/" to "Completed Anime",
        "movie/page/%d/" to "Movie Anime",
        "anime/page/%d/?order=update" to "Latest Update",
        "anime/page/%d/?order=popular" to "Most Popular",
        "anime/list-mode/" to "List Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        val url = if (page <= 1) {
            "$mainUrl/${path.replace("page/1/", "")}"
        } else {
            "$mainUrl/$path"
        }

        val doc = app.get(url).document
        val home = doc.select("div.listupd article, div.bsx, article.bs, article.stylefor")
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
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href.isBlank() || href.contains("/page/")) return null

        val rawTitle = this.selectFirst("h2, .tt h2, .entry-title")?.text()
            ?: a.attr("title")
        if (rawTitle.isBlank()) return null

        val title = rawTitle
            .replace(Regex("(?i)Episode\\s*\\d+"), "")
            .replace(Regex("(?i)Subtitle\\s+Indonesia"), "")
            .replace(Regex("(?i)Sub\\s+Indo"), "")
            .replace(Regex("(?i)\\[END]"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val img = this.selectFirst("img")
        val rawImg = img?.attr("data-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("src")

        val posterUrl = fixImageUrl(rawImg)

        val typeStr = this.selectFirst(".typez, .eggtype, .bt .typez, span i")?.text().orEmpty()
        val tvType = when {
            href.contains("/movie/") || typeStr.contains("Movie", ignoreCase = true) ||
                title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            typeStr.contains("OVA", ignoreCase = true) ||
                typeStr.contains("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val epNum = this.selectFirst(".epx, .eggepisode, .bt .epx")?.text()
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url == null || url.startsWith("data:")) return null
        val cleanUrl = url.substringBefore("?")
        return fixUrlNull(cleanUrl)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.listupd article, div.bsx, article.bs, article.stylefor")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        var animeUrl = fixUrl(url)
        var doc = app.get(animeUrl).document

        if (!animeUrl.contains("/anime/")) {
            val parentLink = doc.selectFirst(
                ".ts-breadcrumb a[href*=\"/anime/\"], .year a[href*=\"/anime/\"], .naveps a[href*=\"/anime/\"]"
            )?.attr("href")
            if (parentLink != null) {
                animeUrl = fixUrl(parentLink)
                doc = app.get(animeUrl).document
            }
        }

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".entry-title")?.text()?.trim()
            ?: "Unknown"

        val poster = fixImageUrl(
            doc.selectFirst("div.thumb img, div.thumbook img")?.attr("src")
                ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        )

        val synopsis = doc.select("div.entry-content p, div.desc p, div.mindesc").text().trim()
        val genres = doc.select(".genxed a").map { it.text().trim() }
        val rating = doc.selectFirst("div.rating strong")?.text()
            ?.replace("Rating", "")?.trim()?.toDoubleOrNull()

        val statusStr = doc.select("div.spe span:contains(Status)").text()
        val showStatus = when {
            statusStr.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            statusStr.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val typeStr = doc.select("div.spe span:contains(Tipe), div.spe span:contains(Type)").text()
        val tvType = when {
            typeStr.contains("Movie", ignoreCase = true) || animeUrl.contains("/movie/") -> TvType.AnimeMovie
            typeStr.contains("OVA", ignoreCase = true) ||
                typeStr.contains("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = doc.select(".eplister ul li, div.bxcl.epcheck ul li, div.bxcl ul li").mapNotNull { ep ->
            val a = ep.selectFirst("a[href]") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val epNumStr = ep.selectFirst(".epl-num")?.text()?.trim()
            val epNum = epNumStr?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            val epName = ep.selectFirst(".epl-title")?.text()?.trim() ?: "Episode $epNumStr"
            val epDate = ep.selectFirst(".epl-date")?.text()?.trim()

            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
                this.posterUrl = poster
                if (!epDate.isNullOrBlank()) {
                    this.description = "Rilis: $epDate"
                }
            }
        }.reversed()

        val finalEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(animeUrl) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return if (tvType == TvType.AnimeMovie && finalEpisodes.size <= 1) {
            newMovieLoadResponse(title, animeUrl, TvType.AnimeMovie, finalEpisodes.first().data) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                this.score = Score.from10(rating)
            }
        } else {
            newAnimeLoadResponse(title, animeUrl, tvType) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                this.showStatus = showStatus
                this.score = Score.from10(rating)
                addEpisodes(DubStatus.Subbed, finalEpisodes)
            }
        }
    }

    // ============================================================
    // LOAD LINKS — versi rewrite dengan scan semua server
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(fixUrl(data)).document

        // Peta URL -> serverName (LinkedHashMap untuk dedup otomatis)
        val collected = LinkedHashMap<String, String>()

        // (1) Semua <option> di <select class="mirror"> dan varian selector
        val optionSelector = listOf(
            "select.mirror option",
            "#selectserver option",
            ".mobius option",
            ".select-server option",
            ".mirrorstream option",
            "select#changeServer option"
        ).joinToString(", ")

        for (option in doc.select(optionSelector)) {
            val raw = option.attr("value").trim()
            if (raw.isBlank()) continue
            val iframeUrl = extractIframeUrl(raw) ?: continue
            val serverName = option.text().trim()
                .takeIf { it.isNotBlank() && !it.contains("Pilih", ignoreCase = true) }
                ?: "Server"
            collected.putIfAbsent(iframeUrl, serverName)
        }

        // (2) <li> / [data-link] / [data-embed]
        for (item in doc.select("ul#playeroptionsul > li, .player-servers li, [data-link], [data-embed]")) {
            val link = item.attr("data-link")
                .ifBlank { item.attr("data-embed") }
                .ifBlank { item.selectFirst("iframe")?.attr("src") ?: "" }
            if (link.isBlank()) continue
            val iframeUrl = extractIframeUrl(link) ?: continue
            val serverName = item.text().trim().ifBlank { "Server" }
            collected.putIfAbsent(iframeUrl, serverName)
        }

        // (3) Default iframe di player utama (#pembed / #embed_holder)
        doc.selectFirst("#pembed iframe[src], #embed_holder iframe[src], .player-embed iframe[src]")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { src ->
                extractIframeUrl(src)?.let { collected.putIfAbsent(it, "Server 1") }
            }

        println("Gojonime: ditemukan ${collected.size} server unik")

        var success = 0
        for ((rawUrl, serverName) in collected) {
            val streamUrl = fixStreamUrl(rawUrl)
            val quality = detectQuality(serverName)
            println("Gojonime: proses [$serverName] -> $streamUrl")
            try {
                processStreamUrl(streamUrl, serverName, quality, subtitleCallback, callback)
                success++
            } catch (e: Exception) {
                println("Gojonime: gagal [$serverName] $streamUrl - ${e.message}")
            }
        }

        return success > 0
    }

    /**
     * Ekstrak URL iframe dari value option.
     * Nilai bisa: URL langsung, HTML <iframe>, atau base64 dari salah satunya.
     */
    private fun extractIframeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value == "#") return null

        if (value.startsWith("http://") || value.startsWith("https://")) return value

        if (value.contains("<iframe", ignoreCase = true)) {
            return Jsoup.parse(value).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
        }

        return try {
            val decoded = base64Decode(value).trim()
            when {
                decoded.contains("<iframe", ignoreCase = true) ->
                    Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                decoded.startsWith("http") -> decoded
                else -> decoded.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            value
        }
    }

    /** Deteksi kualitas dari teks opsi, mis. "Server 3 - HD" atau "1080p". */
    private fun detectQuality(text: String): Int = when {
        Regex("""\b1080[pP]\b""").containsMatchIn(text) -> Qualities.P1080.value
        Regex("""\b720[pP]\b""").containsMatchIn(text)  -> Qualities.P720.value
        Regex("""\b480[pP]\b""").containsMatchIn(text)  -> Qualities.P480.value
        Regex("""\b360[pP]\b""").containsMatchIn(text)  -> Qualities.P360.value
        Regex("""\b240[pP]\b""").containsMatchIn(text)  -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }

    // ============================================================
    // PROSES STREAM
    // ============================================================
    private suspend fun processStreamUrl(
        streamUrl: String,
        serverName: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var targetUrl = streamUrl

        // --- berkasdrive: decode ?id= ---
        if (streamUrl.contains("berkasdrive.com")) {
            val id = streamUrl.substringAfter("id=", "").substringBefore("&")
            if (id.isNotBlank()) {
                try {
                    val decoded = base64Decode(id)
                    if (decoded.startsWith("http")) {
                        targetUrl = fixStreamUrl(decoded)
                    }
                } catch (_: Exception) {}
            }
        }

        // --- shortener domain -> pakai ShortIcuExtractor ---
        if (targetUrl.contains("short.icu") || targetUrl.contains("short.ink") ||
            targetUrl.contains("yihdraplay") || targetUrl.contains("gojonime.my.id") ||
            targetUrl.contains("harenchidesu")
        ) {
            try {
                var emitted = false
                ShortIcuExtractor().apply { name = serverName }.getUrl(
                    targetUrl, mainUrl, subtitleCallback
                ) { link ->
                    emitted = true
                    callback.invoke(
                        newExtractorLink(serverName, link.name, link.url, link.type) {
                            this.referer = link.referer
                            this.quality = if (link.quality != Qualities.Unknown.value) link.quality else quality
                        }
                    )
                }
                if (emitted) return
                println("Gojonime: ShortIcu tidak menghasilkan link untuk $targetUrl")
            } catch (e: Exception) {
                println("Gojonime: ShortIcu error $targetUrl - ${e.message}")
            }
            // fall-through: coba fetch manual
        }

        // --- fetch manual: cari <video>, <source>, file:.mp4/.m3u8 di HTML ---
        try {
            val res = app.get(targetUrl, referer = mainUrl)
            val html = res.text
            val doc = res.document

            // a) pixeldrain
            if (html.contains("pixeldrain.com")) {
                val fid = Regex("""pixeldrain\.com/(?:d|u|api/file)/([A-Za-z0-9]+)""")
                    .find(html)?.groupValues?.getOrNull(1)
                if (fid != null) {
                    callback.invoke(
                        newExtractorLink(
                            serverName, serverName,
                            "https://pixeldrain.com/api/file/$fid",
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality
                        }
                    )
                    return
                }
            }

            // b) video / source tags
            val videoSrc = doc.selectFirst("video[src], video source[src]")?.attr("src")
                ?: Regex("""file\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""(?:src|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                    .find(html)?.groupValues?.getOrNull(1)

            if (!videoSrc.isNullOrBlank()) {
                val finalUrl = fixUrl(videoSrc)
                val type = if (finalUrl.contains(".m3u8", ignoreCase = true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(serverName, serverName, finalUrl, type) {
                        this.referer = targetUrl
                        this.quality = quality
                    }
                )
                return
            }

            // c) nested iframe — rekursif sekali
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")
            if (!innerIframe.isNullOrBlank() && innerIframe != targetUrl) {
                val innerFixed = fixUrl(innerIframe)
                if (innerFixed != targetUrl) {
                    processStreamUrl(innerFixed, serverName, quality, subtitleCallback, callback)
                    return
                }
            }
        } catch (e: Exception) {
            println("Gojonime: fetch $targetUrl error - ${e.message}")
        }

        // --- fallback terakhir: extractor bawaan CloudStream ---
        try {
            loadExtractor(targetUrl, mainUrl, subtitleCallback) { link ->
                callback.invoke(
                    newExtractorLink(serverName, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.headers = link.headers
                        this.quality = if (link.quality != Qualities.Unknown.value) link.quality else quality
                        this.extractorData = link.extractorData
                    }
                )
            }
        } catch (e: Exception) {
            println("Gojonime: loadExtractor gagal $targetUrl - ${e.message}")
        }
    }

    // ============================================================
    // FIX STREAM URL — samakan dengan replaceDomain() + loadMi() di web
    // ============================================================
    private fun fixStreamUrl(rawUrl: String): String {
        var url = fixUrl(rawUrl)

        // 1) replaceDomain() dari website — urutan penting:
        //    short.ink -> gojonime.my.id  (BUKAN ke short.icu!)
        if (Regex("""https?://([^/]*\.)?short\.ink""").containsMatchIn(url)) {
            url = url.replace(
                Regex("""https?://([^/]*\.)?short\.ink"""),
                "https://gojonime.my.id"
            )
        }
        //    short.icu -> yihdraplay.my.id
        if (Regex("""https?://([^/]*\.)?short\.icu""").containsMatchIn(url)) {
            url = url.replace(
                Regex("""https?://([^/]*\.)?short\.icu"""),
                "https://yihdraplay.my.id"
            )
        }
        //    abyssplayer -> harenchidesu
        if (Regex("""https?://(player\.|play\.)?abyssplayer\.com""").containsMatchIn(url)) {
            url = url.replace(
                Regex("""https?://(player\.|play\.)?abyssplayer\.com"""),
                "https://harenchidesu.my.id"
            )
        }

        // 2) Mapping tambahan dari loadMi() di website
        val extraMappings = mapOf(
            "desustream.me/otakuhade/baru/" to "desustream.info/dstream/otakustream/index.php",
            "desustream.me/moedesu/stream/hd/" to "desustream.info/dstream/moedesu/hd/index.php",
            "desustream.com/moedesu/hd/" to "desustream.info/dstream/moedesu/index.php",
            "desustream.me/beta/stream/hd/" to "desustream.info/dstream/otakuwatch2/hd/index.php",
            "desustream.me/ondesu/hd/index.php" to "desustream.info/dstream/ondesu/hd/index.php",
            "desustream.me/arcg/done/" to "desustream.info/dstream/arcg/",
            "desustream.me/otakustream/?" to "desustream.info/dstream/otakustream/index.php?",
            "desustream.me/desudrive/player.php" to "desustream.info/dstream/desudrive/player.php",
            "desustream.me/desudesuhd/" to "desustream.info/dstream/desudesuhd/index.php",
            "desustream.me/desudesuhd3/" to "desustream.info/dstream/desudesuhd3/index.php",
            "desustream.me/arcg" to "desustream.info/dstream/arcg"
        )
        extraMappings.forEach { (old, new) -> url = url.replace(old, new) }

        return url
    }
}
