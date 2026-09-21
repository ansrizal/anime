package com.indo

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

class Rebahin : MainAPI() {
    override var mainUrl = "http://138.68.182.147"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "Rebahin"

    override val mainPage = mainPageOf(
        "" to "Film Terbaru",
        "movies/" to "Movies",
        "tv/" to "TV Series",
        "genre/action/" to "Action",
        "genre/horror/" to "Horror",
        "genre/fantasy/" to "Fantasi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        val apiPath = when (request.data) {
            "movies/" -> "api/movies"
            "tv/" -> "api/tv"
            else -> null
        }

        if (apiPath != null) {
            getApiSection(request.name, page, apiPath)?.let { sections.add(it) }
        }

        if (sections.isEmpty()) {
            val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
            val doc = app.get(url.replace("//page", "/page")).document
            val items = doc.select("div.listupd article, div.bsx, div.ml-item, article").asIterable().mapNotNull { el ->
                val title = el.selectFirst("a[title]")?.attr("title")
                    ?: el.selectFirst("h2, h3")?.text()
                    ?: return@mapNotNull null
                val href = fixUrl(el.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val poster = el.selectFirst("img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
            if (items.isNotEmpty()) sections.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(sections, hasNext = true)
    }

    private suspend fun getApiSection(name: String, page: Int, apiPath: String): HomePageList? {
        val resp = try { app.get("$mainUrl/$apiPath?page=$page&limit=24") } catch (_: Exception) { return null }
        val text = resp.text ?: return null
        val data = try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { return null }
        if (data == null) return null
        val items = (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "")
            val title = item.optString("title", "")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val type = item.optString("type", "movie")
            val voteAvg = if (item.has("voteAverage")) item.optDouble("voteAverage", -1.0).let { if (it < 0) null else it } else null
            val href = if (type == "tv") "/tv/$id" else "/movies/$id"
            if (type == "tv")
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
        return if (items.isEmpty()) null else HomePageList(name, items)
    }

    private fun findMatchingBraceAny(s: String, start: Int, close: Char): Int {
        val open = if (close == '}') '{' else '['
        var depth = 1
        var i = start + 1
        while (depth > 0 && i < s.length) {
            if (s[i] == '"') {
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\') i++
                    i++
                }
            } else if (s[i] == open) depth++
            else if (s[i] == close) depth--
            i++
        }
        return if (depth == 0) i - 1 else -1
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = try { app.get("$mainUrl/api/search?q=$query") } catch (_: Exception) { null }
        val text = resp?.text
        val data = if (text != null) try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { null } else null

        if (data != null) {
            return (0 until data.length()).mapNotNull { i ->
                val item = data.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id", "")
                val title = item.optString("title", "")
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                val posterPath = item.optString("posterPath", "")
                val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
                val type = item.optString("type", "movie")
                val voteAvg = if (item.has("voteAverage")) item.optDouble("voteAverage", -1.0).let { if (it < 0) null else it } else null
                val href = if (type == "tv") "/tv/$id" else "/movies/$id"
                if (type == "tv") {
                    newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster; this.score = Score.from10(voteAvg)
                    }
                } else {
                    newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster; this.score = Score.from10(voteAvg)
                    }
                }
            }
        }

        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.listupd article, div.bsx, div.ml-item, article").asIterable().mapNotNull { el ->
            val title = el.selectFirst("a[title]")?.attr("title")
                ?: el.selectFirst("h2, h3")?.text()
                ?: return@mapNotNull null
            val href = fixUrl(el.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = el.selectFirst("img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.i(TAG, "load() called for $url")
        val resp = app.get(url)
        val doc = resp.document
        val raw = resp.text ?: ""
        val html = raw.replace("\\\"", "\"")

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val year = Regex("(\\b20\\d{2}\\b)").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = doc.select("a[href*=genre], a[href*=category]").asIterable()
            .map { it.text() }.filter { it.isNotBlank() }

        val voteAvg = Regex("\"voteAverage\":([0-9.]+)").find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        val isSeries = url.contains("/tv/")
        Log.i(TAG, "load() isSeries=$isSeries, raw len=${raw.length}")

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
                this.score = score
            }
        }

        val episodes = mutableListOf<Pair<String, Int>>()
        val seenUrls = mutableSetOf<String>()
        val seenEps = mutableSetOf<Int>()
        val slug = url.trimEnd('/').substringAfterLast('/')

        val epLinkSelectors = listOf(
            "a[href*=/eps/]",
            "a[href*=/episode-]",
            "a[href*=/episode/]",
            "a[href*=/season-]"
        )
        for (sel in epLinkSelectors) {
            doc.select(sel).asIterable().forEach { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (href.isBlank()) return@forEach
                val fixed = fixUrl(href)
                if (fixed == url || fixed.endsWith("#") || !seenUrls.add(fixed)) return@forEach
                val epNum = Regex("""episode[-/](\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""eps-(\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (episodes.size + 1)
                if (!seenEps.add(epNum)) return@forEach
                episodes.add(fixed to epNum)
            }
            if (episodes.isNotEmpty()) {
                Log.i(TAG, "load() episode detection via selector $sel -> ${episodes.size}")
                break
            }
        }

        if (episodes.isEmpty()) {
            Regex("\"episodes\"\\s*:\\s*\\[([\\s\\S]*?)\\]\\s*[,}]").findAll(html).forEach { match ->
                val epsJson = match.groupValues[1]
                val epNums = Regex("\"episodeNumber\"\\s*:\\s*(\\d+)").findAll(epsJson)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                val seasonNums = Regex("\"seasonNumber\"\\s*:\\s*(\\d+)").findAll(epsJson)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                epNums.forEachIndexed { i, epNum ->
                    val seasonNum = seasonNums.getOrNull(i) ?: 1
                    val epUrl = "$mainUrl/eps/$slug-season-$seasonNum-episode-$epNum/"
                    if (seenUrls.add(epUrl) && seenEps.add(epNum)) {
                        episodes.add(epUrl to epNum)
                    }
                }
            }
        }

        val episodeUrls = episodes.map { (epUrl, epNum) ->
            newEpisode(epUrl) {
                this.name = "Eps $epNum"
                this.episode = epNum
                this.season = 1
            }
        }

        if (episodeUrls.isEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(url) {
                    this.name = "Play"
                    this.episode = 1
                    this.season = 1
                }
            )) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
                this.score = score
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.year = year
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linkCount = 0
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linkCount++
            Log.i(TAG, "loadLinks() emitting link: ${link.url}")
            callback(link)
        }

        Log.i(TAG, "loadLinks() START: $data")
        val resp = try { app.get(data) } catch (e: Exception) {
            Log.e(TAG, "loadLinks() app.get failed: ${e.message}")
            return false
        }
        val raw = resp.text ?: return false
        val html = raw.replace("\\\"", "\"")
        val doc = resp.document
        Log.i(TAG, "loadLinks() page fetched, len=${raw.length}")

        val embedUrls = mutableSetOf<String>()

        doc.select("iframe").asIterable().forEach { iframe ->
            listOf(
                iframe.attr("src"),
                iframe.attr("data-src"),
                iframe.attr("data-litespeed-src"),
                iframe.attr("data-lazy-src"),
                iframe.attr("data-original")
            ).forEach { s -> if (s.isNotBlank()) embedUrls.add(toAbsolute(s, data)) }
        }

        Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            embedUrls.add(toAbsolute(m.groupValues[1], data))
        }
        Regex("""<iframe[^>]*\s(?:data-litespeed-src|data-src|data-lazy-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            embedUrls.add(toAbsolute(m.groupValues[1], data))
        }

        val filteredEmbeds = embedUrls.filter { url ->
            url.isNotBlank() &&
                !url.contains("youtube", true) &&
                !url.contains("youtu.be", true) &&
                !url.contains("google.com/maps", true)
        }

        Log.i(TAG, "loadLinks() found ${filteredEmbeds.size} embed(s): $filteredEmbeds")

        // === 1. Built-in extractor ===
        for (embedUrl in filteredEmbeds) {
            Log.i(TAG, "loadLinks() trying built-in loadExtractor on $embedUrl")
            try {
                loadExtractor(embedUrl, data, subtitleCallback, wrappedCallback)
                Log.i(TAG, "loadLinks() loadExtractor done, linkCount=$linkCount")
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() loadExtractor exception: ${e.message}")
            }
        }

        // === 2. Custom VidHide extractor ===
        if (linkCount == 0) {
            for (embedUrl in filteredEmbeds) {
                Log.i(TAG, "loadLinks() trying custom extractVidHide on $embedUrl")
                try {
                    extractVidHide(embedUrl, data, subtitleCallback, wrappedCallback)
                    Log.i(TAG, "loadLinks() extractVidHide done, linkCount=$linkCount")
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks() extractVidHide exception: ${e.message}")
                }
            }
        }

        // === 3. Fallback: JSON sources di HTML ===
        if (linkCount == 0) {
            Log.i(TAG, "loadLinks() trying JSON sources fallback")
            var pos = 0
            while (true) {
                val srcIdx = html.indexOf("\"sources\":[", pos)
                val playIdx = html.indexOf("\"playerSources\":[", pos)
                val idx = when {
                    srcIdx >= 0 && playIdx >= 0 -> minOf(srcIdx, playIdx)
                    srcIdx >= 0 -> srcIdx
                    playIdx >= 0 -> playIdx
                    else -> break
                }
                pos = idx + 1
                val arrayStart = html.indexOf('[', idx) + 1
                if (arrayStart <= 0) continue
                val arrayEnd = findMatchingBraceAny(html, arrayStart - 1, ']')
                if (arrayEnd < 0) continue
                val arrayContent = html.substring(arrayStart, arrayEnd)
                var objPos = 0
                while (true) {
                    val objStart = arrayContent.indexOf('{', objPos)
                    if (objStart < 0) break
                    val objEnd = findMatchingBraceAny(arrayContent, objStart, '}')
                    if (objEnd < 0) break
                    val obj = arrayContent.substring(objStart, objEnd + 1)
                    val videoUrl = Regex("\"playbackUrl\":\"([^\"]+)\"").find(obj)?.groupValues?.getOrNull(1)
                        ?: Regex("\"file\":\"([^\"]+)\"").find(obj)?.groupValues?.getOrNull(1)
                    val quality = Regex("\"quality\":\"([^\"]+)\"").find(obj)?.groupValues?.getOrNull(1) ?: "FHD"
                    if (!videoUrl.isNullOrBlank()) {
                        wrappedCallback(newExtractorLink("Rebahin", "Rebahin - $quality", videoUrl) {
                            this.quality = parseQuality(quality)
                            this.referer = "$mainUrl/"
                        })
                    }
                    objPos = objEnd + 1
                }
            }
        }

        Log.i(TAG, "loadLinks() DONE: total links=$linkCount")
        return linkCount > 0
    }

    /**
     * Extractor VidHide:
     * 1. GET /embed/{id}
     * 2. Parse form action + input hidden
     * 3. POST ke action URL
     * 4. Cari URL m3u8/mp4 di response
     */
    private suspend fun extractVidHide(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "extractVidHide() START embed=$embedUrl")

        // === 1. GET halaman embed ===
        val pageResp = try {
            app.get(embedUrl, referer = referer, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "extractVidHide() GET failed: ${e.message}")
            return
        }
        val pageHtml = pageResp.text ?: return
        Log.i(TAG, "extractVidHide() GET ok, len=${pageHtml.length}")

        // === 2. Ekstrak action form dan hidden inputs ===
        val actionRegex = Regex("""<form[^>]+action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val actionMatch = actionRegex.find(pageHtml)
        val actionUrl = actionMatch?.groupValues?.get(1)?.let { toAbsolute(it, embedUrl) } ?: embedUrl
        Log.i(TAG, "extractVidHide() actionUrl=$actionUrl")

        val refRegex = Regex("""name=["']referer["'][^>]+value=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val refererValue = refRegex.find(pageHtml)?.groupValues?.get(1) ?: referer
        Log.i(TAG, "extractVidHide() refererValue=$refererValue")

        val postData = mutableMapOf<String, String>()
        postData["referer"] = refererValue
        Regex("""<input[^>]+name=["']([^"']+)["'][^>]+value=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml).forEach { m ->
                val name = m.groupValues[1]
                val value = m.groupValues[2]
                if (name != "referer" && value.isNotBlank()) {
                    postData[name] = value
                }
            }
        Log.i(TAG, "extractVidHide() postData keys=${postData.keys}, referer=$refererValue")

        // === 3. POST ke action URL ===
        val postResp = try {
            app.post(
                actionUrl,
                data = postData,
                referer = embedUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "extractVidHide() POST failed: ${e.message}")
            return
        }
        val postText = postResp.text ?: return
        Log.i(TAG, "extractVidHide() POST status=${postResp.code}, len=${postText.length}")
        Log.i(TAG, "extractVidHide() POST body first 1500: ${postText.take(1500).replace("\n", "\\n")}")

        val candidates = mutableSetOf<String>()

        // === 4a. Parse JSON ===
        try {
            val json = JSONObject(postText)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.length > 5 && (v.contains("http") || v.contains("m3u8") || v.contains(".mp4") || v.startsWith("//"))) {
                    Log.i(TAG, "extractVidHide() JSON key=$k -> $v")
                    candidates.add(v)
                }
                val nested = json.optJSONObject(k)
                if (nested != null) {
                    val nk = nested.keys()
                    while (nk.hasNext()) {
                        val n = nk.next()
                        val nv = nested.optString(n, "")
                        if (nv.length > 5 && (nv.contains("http") || nv.contains("m3u8") || nv.contains(".mp4") || nv.startsWith("//"))) {
                            Log.i(TAG, "extractVidHide() JSON nested $k.$n -> $nv")
                            candidates.add(nv)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // === 4b. Ekstrak URL langsung dari body ===
        Regex("""(https?:)?//[^\s"'<>\\]+\.(m3u8|mp4)[^\s"'<>\\]*""").findAll(postText).forEach { m ->
            candidates.add(m.value)
        }

        // === 4c. Value string yang mengandung m3u8/mp4/hls ===
        Regex(""""([^"]{10,})"""").findAll(postText).forEach { m ->
            val v = m.groupValues[1]
            if (v.contains("m3u8") || v.contains(".mp4") || v.contains("/hls/") || v.contains("/stream/")) {
                candidates.add(v)
            }
        }

        // === 4d. Plain "sources" / "file" ===
        Regex(""""sources?"\s*:\s*"([^"]+)"""").findAll(postText).forEach { candidates.add(it.groupValues[1]) }
        Regex(""""file"\s*:\s*"([^"]+)"""").findAll(postText).forEach { candidates.add(it.groupValues[1]) }

        // === 4e. Base64 yang decode-nya mengandung URL ===
        Regex("""["']([A-Za-z0-9+/=]{60,})["']""").findAll(postText).forEach { m ->
            try {
                val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                if (decoded.contains("http") || decoded.contains("m3u8") || decoded.contains("/hls/")) {
                    Regex("""(https?:)?//[^\s"'<>\\]+\.(m3u8|mp4)[^\s"'<>\\]*""").findAll(decoded).forEach { mm ->
                        candidates.add(mm.value)
                    }
                }
            } catch (_: Exception) { }
        }

        Log.i(TAG, "extractVidHide() total candidates=${candidates.size}: $candidates")

        // === 5. Emit ===
        for (c in candidates) {
            val fixed = c.replace("\\/", "/").replace("\\u0026", "&")
            if (fixed.length < 10) continue
            val url = if (fixed.startsWith("//")) "https:$fixed" else fixed
            Log.i(TAG, "extractVidHide() emit=$url")
            callback(newExtractorLink("Rebahin", "Rebahin - VidHide", url) {
                this.referer = embedUrl
                this.quality = 3
            })
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "extractVidHide() NO CANDIDATES. Full POST body:")
            Log.w(TAG, postText.take(3000))
        }
    }

    private fun toAbsolute(url: String, base: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) {
            return try {
                val u = java.net.URL(base)
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.protocol}://${u.host}$port$url"
            } catch (_: Exception) {
                "$mainUrl$url"
            }
        }
        return url
    }

    private fun parseQuality(q: String): Int {
        return when {
            q.contains("4K", true) || q.contains("2160", true) -> 4
            q.contains("1080", true) -> 3
            q.contains("720", true) -> 2
            q.contains("480", true) || q.contains("360", true) -> 1
            else -> 3
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "http:$url"
        return "$mainUrl$url"
    }
}
