package com.gojonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
        TvType.Anime, TvType.AnimeMovie, TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "on-going-anime/page/%d/" to "On-Going Anime",
        "completed-anime/page/%d/" to "Completed Anime",
        "movie/page/%d/" to "Movie Anime",
        "anime/page/%d/?order=update" to "Latest Update",
        "anime/page/%d/?order=popular" to "Most Popular",
        "anime/list-mode/" to "List Anime"
    )

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        val url = if (page <= 1) "$mainUrl/${path.replace("page/1/", "")}"
                  else "$mainUrl/$path"
        val doc = app.get(url).document
        val home = doc.select("div.listupd article, div.bsx, article.bs, article.stylefor")
            .asSequence().mapNotNull { it.toSearchResult() }.distinctBy { it.url }.toList()
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href.isBlank() || href.contains("/page/")) return null
        val rawTitle = this.selectFirst("h2, .tt h2, .entry-title")?.text() ?: a.attr("title")
        if (rawTitle.isBlank()) return null
        val title = rawTitle
            .replace(Regex("(?i)Episode\\s*\\d+"), "")
            .replace(Regex("(?i)Subtitle\\s+Indonesia"), "")
            .replace(Regex("(?i)Sub\\s+Indo"), "")
            .replace(Regex("(?i)\\[END]"), "")
            .trim().removeSuffix("-").trim()
        val img = this.selectFirst("img")
        val rawImg = img?.attr("data-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("src")
        val posterUrl = fixImageUrl(rawImg)
        val typeStr = this.selectFirst(".typez, .eggtype, .bt .typez, span i")?.text().orEmpty()
        val tvType = when {
            href.contains("/movie/") || typeStr.contains("Movie", true) ||
                title.contains("Movie", true) -> TvType.AnimeMovie
            typeStr.contains("OVA", true) || typeStr.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }
        val epNum = this.selectFirst(".epx, .eggepisode, .bt .epx")?.text()
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl; addSub(epNum)
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url == null || url.startsWith("data:")) return null
        return fixUrlNull(url.substringBefore("?"))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.listupd article, div.bsx, article.bs, article.stylefor")
            .asSequence().mapNotNull { it.toSearchResult() }.distinctBy { it.url }.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        var animeUrl = fixUrl(url)
        var doc = app.get(animeUrl).document
        if (!animeUrl.contains("/anime/")) {
            val parentLink = doc.selectFirst(
                ".ts-breadcrumb a[href*=\"/anime/\"], .year a[href*=\"/anime/\"], .naveps a[href*=\"/anime/\"]"
            )?.attr("href")
            if (parentLink != null) {
                animeUrl = fixUrl(parentLink); doc = app.get(animeUrl).document
            }
        }
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
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
            statusStr.contains("Completed", true) -> ShowStatus.Completed
            statusStr.contains("Ongoing", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
        val typeStr = doc.select("div.spe span:contains(Tipe), div.spe span:contains(Type)").text()
        val tvType = when {
            typeStr.contains("Movie", true) || animeUrl.contains("/movie/") -> TvType.AnimeMovie
            typeStr.contains("OVA", true) || typeStr.contains("Special", true) -> TvType.OVA
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
                this.name = epName; this.episode = epNum; this.posterUrl = poster
                if (!epDate.isNullOrBlank()) this.description = "Rilis: $epDate"
            }
        }.reversed()
        val finalEpisodes = episodes.ifEmpty {
            listOf(newEpisode(animeUrl) {
                this.name = title; this.episode = 1; this.posterUrl = poster
            })
        }
        return if (tvType == TvType.AnimeMovie && finalEpisodes.size <= 1) {
            newMovieLoadResponse(title, animeUrl, TvType.AnimeMovie, finalEpisodes.first().data) {
                this.posterUrl = poster; this.plot = synopsis; this.tags = genres
                this.score = Score.from10(rating)
            }
        } else {
            newAnimeLoadResponse(title, animeUrl, tvType) {
                this.posterUrl = poster; this.plot = synopsis; this.tags = genres
                this.showStatus = showStatus; this.score = Score.from10(rating)
                addEpisodes(DubStatus.Subbed, finalEpisodes)
            }
        }
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(fixUrl(data)).document
        val collected = LinkedHashMap<String, String>()

        val optionSelector = listOf(
            "select.mirror option", "#selectserver option", ".mobius option",
            ".select-server option", ".mirrorstream option", "select#changeServer option"
        ).joinToString(", ")

        for (option in doc.select(optionSelector)) {
            val raw = option.attr("value").trim()
            if (raw.isBlank()) continue
            val iframeUrl = extractIframeUrl(raw) ?: continue
            val serverName = option.text().trim()
                .takeIf { it.isNotBlank() && !it.contains("Pilih", true) } ?: "Server"
            collected.putIfAbsent(iframeUrl, serverName)
        }
        for (item in doc.select("ul#playeroptionsul > li, .player-servers li, [data-link], [data-embed]")) {
            val link = item.attr("data-link").ifBlank { item.attr("data-embed") }
                .ifBlank { item.selectFirst("iframe")?.attr("src") ?: "" }
            if (link.isBlank()) continue
            val iframeUrl = extractIframeUrl(link) ?: continue
            collected.putIfAbsent(iframeUrl, item.text().trim().ifBlank { "Server" })
        }
        doc.selectFirst("#pembed iframe[src], #embed_holder iframe[src], .player-embed iframe[src]")
            ?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let { src -> extractIframeUrl(src)?.let { collected.putIfAbsent(it, "Server 1") } }

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

    private fun extractIframeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value == "#") return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.contains("<iframe", true)) {
            return Jsoup.parse(value).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
        }
        return try {
            val decoded = base64Decode(value).trim()
            when {
                decoded.contains("<iframe", true) ->
                    Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                decoded.startsWith("http") -> decoded
                else -> decoded.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { value }
    }

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

        // Berkasdrive decode ?id=
        if (streamUrl.contains("berkasdrive.com")) {
            val id = streamUrl.substringAfter("id=", "").substringBefore("&")
            if (id.isNotBlank()) {
                try {
                    val decoded = base64Decode(id)
                    if (decoded.startsWith("http")) targetUrl = decoded.trim()
                } catch (_: Exception) {}
            }
        }

        println("Gojonime: [$serverName] target = $targetUrl")

        // Direct video URL
        if (targetUrl.matches(Regex(""".*\.(m3u8|mp4)(\?.*)?$"""))) {
            emitVideo(targetUrl, mainUrl, serverName, quality, callback)
            return
        }

        // ABYSS-like: shortener, abyss.to, abyssplayer, harenchidesu
        if (isAbyssLike(targetUrl)) {
            try {
                val res = app.get(
                    targetUrl,
                    referer = mainUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
                    )
                )
                val html = res.text
                println("Gojonime: [$serverName] HTML len=${html.length}")

                if (decodeAbyss(html, targetUrl, serverName, quality, callback)) {
                    return
                }

                Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.let { iframeSrc ->
                        val fixed = fixUrl(iframeSrc)
                        if (fixed != targetUrl &&
                            !fixed.contains("statistic") &&
                            !fixed.contains("error.php")
                        ) {
                            println("Gojonime: [$serverName] ✓ iframe = $fixed")
                            processStreamUrl(fixed, serverName, quality, subtitleCallback, callback)
                            return
                        }
                    }
            } catch (e: Exception) {
                println("Gojonime: [$serverName] fetch error - ${e.message}")
            }
        }

        // Mitedrive
        if (targetUrl.contains("mitedrive.com", true)) {
            try {
                val res = app.get(targetUrl, referer = "https://mitedrive.com/")
                extractVideoFromHtml(res.text, targetUrl, serverName)?.let {
                    emitVideo(it, targetUrl, serverName, quality, callback)
                    return
                }
            } catch (e: Exception) {
                println("Gojonime: [$serverName] mitedrive gagal - ${e.message}")
            }
        }

        // Final fallback
        try {
            loadExtractor(targetUrl, mainUrl, subtitleCallback) { link ->
                callback.invoke(link)
            }
        } catch (e: Exception) {
            println("Gojonime: [$serverName] loadExtractor gagal - ${e.message}")
        }
    }

    // ============================================================
    // ABYSS DECRYPT via enc-dec.app
    // ============================================================
    private suspend fun decodeAbyss(
        html: String,
        sourceUrl: String,
        serverName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.trim()
            ?: run {
                println("Gojonime: [$serverName] tidak ada 'const datas' di HTML")
                return false
            }

        println("Gojonime: [$serverName] encrypted len=${encrypted.length}")

        return try {
            val response = app.post(
                "https://enc-dec.app/api/dec-abyss",
                json = mapOf("text" to encrypted),
                headers = mapOf(
                    "User-Agent" to ua,
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text

            println("Gojonime: [$serverName] dec-abyss resp: ${response.take(400)}")

            val root = JSONObject(response)
            if (root.optInt("status", 0) != 200) {
                println("Gojonime: [$serverName] dec-abyss status=${root.optInt("status")} err=${root.optString("error")}")
                return false
            }

            val result = root.optJSONObject("result") ?: run {
                println("Gojonime: [$serverName] no 'result'")
                return false
            }
            val sources = result.optJSONArray("sources") ?: run {
                println("Gojonime: [$serverName] no 'sources'")
                return false
            }

            val abyssHeaders = mapOf(
                "User-Agent" to ua,
                "Origin" to "https://abyssplayer.com/",
                "Referer" to "https://abyssplayer.com/",
                "Accept" to "*/*"
            )

            var emitted = false
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                if (!src.optBoolean("status", false)) continue

                val rawUrl = src.optString("url")
                if (rawUrl.isBlank()) continue

                // URL abyss butuh suffix .m3u8 untuk route ke tunnel Cloudflare
                val finalUrl = "$rawUrl.m3u8"

                val typeStr = src.optString("type").lowercase()
                val q = when {
                    typeStr.contains("1080") -> Qualities.P1080.value
                    typeStr.contains("720")  -> Qualities.P720.value
                    typeStr.contains("480")  -> Qualities.P480.value
                    typeStr.contains("360")  -> Qualities.P360.value
                    typeStr.contains("240")  -> Qualities.P240.value
                    else -> quality
                }

                println("Gojonime: [$serverName] source[$i] $typeStr -> $finalUrl")

                // WARM-UP: request pertama untuk bikin tunnel Cloudflare ready.
                // Diabaikan hasilnya, yang penting tunnel jadi warm.
                runCatching {
                    app.get(finalUrl, headers = abyssHeaders, timeout = 5)
                }
                println("Gojonime: [$serverName] warm-up done untuk $typeStr")

                // Emit sebagai VIDEO (bukan M3U8) — content-type aslinya video/mp4
                callback.invoke(
                    newExtractorLink(
                        source = "$serverName $typeStr",
                        name = "$serverName $typeStr",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://abyssplayer.com/"
                        this.quality = q
                        this.headers = abyssHeaders
                    }
                )
                emitted = true
            }
            if (!emitted) {
                println("Gojonime: [$serverName] tidak ada source aktif")
            }
            emitted
        } catch (e: Exception) {
            println("Gojonime: [$serverName] decodeAbyss error - ${e.message}")
            false
        }
    }

    private fun isAbyssLike(url: String): Boolean =
        listOf(
            "abyss.to", "abyssplayer", "harenchidesu",
            "gojonime.my.id", "yihdraplay.my.id",
            "short.icu", "short.ink"
        ).any { url.contains(it, true) }

    private suspend fun emitVideo(
        videoUrl: String, referer: String, serverName: String,
        quality: Int, callback: (ExtractorLink) -> Unit
    ) {
        val type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8
                   else ExtractorLinkType.VIDEO
        callback.invoke(newExtractorLink(serverName, serverName, videoUrl, type) {
            this.referer = referer
            this.quality = quality
        })
    }

    private fun extractVideoFromHtml(html: String, baseUrl: String, serverName: String): String? {
        Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").find(html)?.let {
            return it.groupValues[1].replace("\\/", "/")
        }
        Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.let {
            val u = it.groupValues[1].replace("\\/", "/")
            return if (u.startsWith("http")) u else fixUrl(u)
        }
        Regex("""<(?:video|source)[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            val u = it.groupValues[1].replace("\\/", "/")
            return if (u.startsWith("http")) u else fixUrl(u)
        }
        return null
    }

    private fun fixStreamUrl(rawUrl: String): String {
        var url = fixUrl(rawUrl)
        if (Regex("""https?://([^/]*\.)?short\.ink""").containsMatchIn(url)) {
            url = url.replace(Regex("""https?://([^/]*\.)?short\.ink"""), "https://gojonime.my.id")
        }
        if (Regex("""https?://([^/]*\.)?short\.icu""").containsMatchIn(url)) {
            url = url.replace(Regex("""https?://([^/]*\.)?short\.icu"""), "https://yihdraplay.my.id")
        }
        if (Regex("""https?://(player\.|play\.)?abyssplayer\.com""").containsMatchIn(url)) {
            url = url.replace(
                Regex("""https?://(player\.|play\.)?abyssplayer\.com"""),
                "https://harenchidesu.my.id"
            )
        }
        return url
    }
}
