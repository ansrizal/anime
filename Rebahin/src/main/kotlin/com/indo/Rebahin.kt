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

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        }

        val episodes = mutableListOf<Pair<String, Int>>()
        val seenUrls = mutableSetOf<String>()
        val seenEps = mutableSetOf<Int>()

        for (sel in listOf("a[href*=/eps/]", "a[href*=/episode-]", "a[href*=/episode/]", "a[href*=/season-]")) {
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
            if (episodes.isNotEmpty()) break
        }

        val episodeUrls = episodes.map { (epUrl, epNum) ->
            newEpisode(epUrl) { this.name = "Eps $epNum"; this.episode = epNum; this.season = 1 }
        }

        if (episodeUrls.isEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(url) { this.name = "Play"; this.episode = 1; this.season = 1 }
            )) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
            posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
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
            Log.i(TAG, "loadLinks() emitting: ${link.url}")
            callback(link)
        }

        Log.i(TAG, "loadLinks() START: $data")
        val resp = try { app.get(data) } catch (e: Exception) {
            Log.e(TAG, "loadLinks() app.get failed: ${e.message}"); return false
        }
        val raw = resp.text ?: return false
        val html = raw.replace("\\\"", "\"")
        val doc = resp.document

        val embedUrls = mutableSetOf<String>()
        doc.select("iframe").asIterable().forEach { iframe ->
            listOf("src", "data-src", "data-litespeed-src", "data-lazy-src", "data-original")
                .forEach { attr ->
                    val s = iframe.attr(attr)
                    if (s.isNotBlank()) embedUrls.add(toAbsolute(s, data))
                }
        }
        Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            embedUrls.add(toAbsolute(m.groupValues[1], data))
        }

        val filteredEmbeds = embedUrls.filter { url ->
            url.isNotBlank() && !url.contains("youtube", true) && !url.contains("youtu.be", true)
        }
        Log.i(TAG, "loadLinks() embeds: $filteredEmbeds")

        for (embedUrl in filteredEmbeds) {
            try { loadExtractor(embedUrl, data, subtitleCallback, wrappedCallback) } catch (_: Exception) { }
        }

        if (linkCount == 0) {
            for (embedUrl in filteredEmbeds) {
                try { extractVidHide(embedUrl, data, wrappedCallback) } catch (e: Exception) {
                    Log.e(TAG, "extractVidHide exception: ${e.message}")
                }
            }
        }

        if (linkCount == 0) {
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
                            this.quality = parseQuality(quality); this.referer = "$mainUrl/"
                        })
                    }
                    objPos = objEnd + 1
                }
            }
        }

        Log.i(TAG, "loadLinks() DONE: total=$linkCount")
        return linkCount > 0
    }

    /**
     * Extractor VidHide v4.6.6. Mencoba banyak endpoint AJAX.
     */
    private suspend fun extractVidHide(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val candidates = mutableSetOf<String>()
        val baseHost = try { "${java.net.URL(embedUrl).protocol}://${java.net.URL(embedUrl).host}" } catch (_: Exception) { "" }
        val embedId = Regex("""/embed/([A-Za-z0-9]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: ""

        Log.i(TAG, "extractVidHide: base=$baseHost id=$embedId")

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val commonHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to embedUrl,
            "Origin" to baseHost,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        // 1. GET halaman embed (untuk cookie sesi)
        try {
            val r = app.get(embedUrl, headers = mapOf(
                "User-Agent" to ua,
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ))
            val text = r.text ?: ""
            Log.i(TAG, "extractVidHide: GET len=${text.length}")
            collectVideoUrls(text, candidates)
        } catch (e: Exception) {
            Log.e(TAG, "extractVidHide: GET failed ${e.message}")
        }

        // 2. Coba semua endpoint AJAX VidHide yang dikenal
        if (candidates.isEmpty() && embedId.isNotBlank()) {
            val endpoints = listOf(
                "$baseHost/dl?op=view&id=$embedId&referer=$referer",
                "$baseHost/dl?op=view&id=$embedId",
                "$baseHost/dl?op=view&id=$embedId&referer=$embedUrl",
                "$baseHost/stream/$embedId",
                "$baseHost/api/v1/video/$embedId",
                "$baseHost/api/v1/stream/$embedId"
            )
            for (ep in endpoints) {
                try {
                    Log.i(TAG, "extractVidHide: GET $ep")
                    val r = app.get(ep, headers = commonHeaders)
                    val body = r.text ?: ""
                    Log.i(TAG, "extractVidHide: resp ${r.code} len=${body.length}")
                    if (body.isNotBlank()) {
                        collectVideoUrls(body, candidates)
                        if (candidates.isEmpty()) parseJsonUrl(body, candidates)
                    }
                    if (candidates.isNotEmpty()) break
                } catch (_: Exception) { }
            }
        }

        // 3. POST ke /dl?op=view
        if (candidates.isEmpty() && embedId.isNotBlank()) {
            val postEndpoints = listOf(
                "$baseHost/dl?op=view&id=$embedId",
                "$baseHost/dl"
            )
            for (ep in postEndpoints) {
                try {
                    Log.i(TAG, "extractVidHide: POST $ep")
                    val r = app.post(
                        ep,
                        data = mapOf("op" to "view", "id" to embedId, "referer" to referer),
                        headers = commonHeaders
                    )
                    val body = r.text ?: ""
                    Log.i(TAG, "extractVidHide: POST resp ${r.code} len=${body.length}")
                    if (body.isNotBlank()) {
                        collectVideoUrls(body, candidates)
                        if (candidates.isEmpty()) parseJsonUrl(body, candidates)
                    }
                    if (candidates.isNotEmpty()) break
                } catch (_: Exception) { }
            }
        }

        // 4. POST ke /embed/{id} dengan referer (form validation)
        if (candidates.isEmpty() && embedId.isNotBlank()) {
            try {
                val r = app.post(
                    "$baseHost/embed/$embedId",
                    data = mapOf("referer" to referer),
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to embedUrl,
                        "Origin" to baseHost,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    )
                )
                val body = r.text ?: ""
                Log.i(TAG, "extractVidHide: POST /embed resp ${r.code} len=${body.length}")
                collectVideoUrls(body, candidates)
                if (candidates.isEmpty()) parseJsonUrl(body, candidates)
            } catch (_: Exception) { }
        }

        Log.i(TAG, "extractVidHide: total=${candidates.size}: $candidates")

        for (url in candidates) {
            val fixed = url.replace("\\/", "/")
            val finalUrl = if (fixed.startsWith("//")) "https:$fixed" else fixed
            callback(newExtractorLink("Rebahin", "Rebahin - VidHide", finalUrl) {
                this.referer = embedUrl
                this.quality = if (finalUrl.contains("1080")) 3 else if (finalUrl.contains("720")) 2 else 3
            })
        }
    }

    private fun parseJsonUrl(body: String, out: MutableSet<String>) {
        try {
            val json = JSONObject(body)
            for (key in listOf("file", "url", "src", "m3u8", "hls", "link")) {
                val v = json.optString(key, "")
                if (v.isNotBlank() && (v.contains(".m3u8") || v.contains(".mp4") || v.startsWith("http"))) {
                    out.add(v)
                }
            }
            // Nested: data.file
            val data = json.optJSONObject("data")
            if (data != null) {
                for (key in listOf("file", "url", "src", "m3u8", "hls", "link")) {
                    val v = data.optString(key, "")
                    if (v.isNotBlank() && v.startsWith("http")) out.add(v)
                }
            }
        } catch (_: Exception) { }
    }

    private fun collectVideoUrls(text: String, out: MutableSet<String>) {
        val patterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex(""""file"\s*:\s*"([^"]+\.mp4[^"]*)""""),
            Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
            Regex(""""src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
            Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)"""),
            Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""")
        )
        for (p in patterns) {
            p.findAll(text).forEach { m ->
                val u = m.groupValues[1].trim()
                if (u.isNotBlank() && !u.contains(" ") && u.length < 500) out.add(u)
            }
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
            } catch (_: Exception) { "$mainUrl$url" }
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
