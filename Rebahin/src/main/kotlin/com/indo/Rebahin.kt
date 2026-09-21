package com.indo

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

    override val mainPage = mainPageOf(
        "movies/" to "Movies",
        "tv/" to "TV Series",
        "genre/action/" to "Action",
        "genre/horror/" to "Horror"
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

        // Scraper fallback
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
        val tags = Regex("\"genres\":\\[([^\\]]+)\\]").find(html)?.let { m ->
            Regex("\"name\":\"([^\"]+)\"").findAll(m.value).map { it.groupValues[1] }.toList()
        } ?: doc.select("a[href*=genre], a[href*=category]").asIterable()
            .map { it.text() }.filter { it.isNotBlank() }

        val voteAvg = Regex("\"voteAverage\":([0-9.]+)").find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        val isSeries = url.contains("/tv/")

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
                this.score = score
            }
        }

        // --- TV Series ---
        val episodeUrls = mutableListOf<Episode>()
        val seenEpisodes = mutableSetOf<String>()

        Regex("\"episodes\"\\s*:\\s*\\[([^\\]]+)\\]").find(html)?.let { match ->
            val epsJson = match.groupValues[1]
            Regex("\"episodeNumber\"\\s*:\\s*(\\d+)[^}]*\"seasonNumber\"\\s*:\\s*(\\d+)").findAll(epsJson).forEach { ep ->
                val epNum = ep.groupValues[1].toIntOrNull()
                val seasonNum = ep.groupValues[2].toIntOrNull()
                if (epNum != null) {
                    val epUrl = if (seasonNum != null) "$url/season-$seasonNum/episode-$epNum"
                    else "$url/season-1/episode-$epNum"
                    if (seenEpisodes.add(epUrl)) {
                        episodeUrls.add(newEpisode(epUrl) {
                            this.name = "Eps $epNum"
                            this.episode = epNum
                            this.season = seasonNum ?: 1
                        })
                    }
                }
            }
        }

        if (episodeUrls.isEmpty()) {
            doc.select("a[href*=/episode-], a[href*=/episode/], a[href*=/season-], a[href*=/eps-]").asIterable().forEach { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (href.isBlank()) return@forEach
                val fixed = fixUrl(href)
                if (fixed == url || !seenEpisodes.add(fixed)) return@forEach
                val epNum = Regex("""episode[-/](\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""eps-(\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val seasonNum = Regex("""season[-/](\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodeUrls.add(newEpisode(fixed) {
                    this.name = if (epNum != null) "Eps $epNum" else a.text().ifBlank { "Episode ${episodeUrls.size + 1}" }
                    this.episode = epNum ?: (episodeUrls.size + 1)
                    this.season = seasonNum ?: 1
                })
            }
        }

        if (episodeUrls.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
            callback(link)
        }

        val resp = try { app.get(data) } catch (_: Exception) { return false }
        val raw = resp.text ?: return false
        val html = raw.replace("\\\"", "\"")
        val doc = resp.document

        // === 1. Kumpulkan URL iframe ===
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

        // === 2. Coba built-in extractor (untuk extractor lain) ===
        for (embedUrl in filteredEmbeds) {
            try {
                loadExtractor(embedUrl, data, subtitleCallback, wrappedCallback)
            } catch (_: Exception) { }
        }

        // === 3. Custom VidHide / generic extractor ===
        if (linkCount == 0) {
            for (embedUrl in filteredEmbeds) {
                try {
                    extractVidHide(embedUrl, data, wrappedCallback)
                } catch (_: Exception) { }
            }
        }

        // === 4. Fallback: parse JSON sources/playerSources di HTML ===
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
                            this.quality = parseQuality(quality)
                            this.referer = "$mainUrl/"
                        })
                    }
                    objPos = objEnd + 1
                }
            }
        }

        return linkCount > 0
    }

    /**
     * Extractor manual untuk VidHide dan sejenisnya.
     * Fetch halaman embed, handle redirect, unpack Dean Edwards JS, cari m3u8/mp4.
     */
    private suspend fun extractVidHide(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = try { java.net.URL(embedUrl).host } catch (_: Exception) { "" }

        // Beberapa varian header yang perlu dicoba
        val headersList = listOf(
            mapOf(
                "Referer" to embedUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            ),
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        )

        var pageText: String? = null
        for (headers in headersList) {
            try {
                val r = app.get(embedUrl, headers = headers)
                if (!r.text.isNullOrBlank()) {
                    pageText = r.text
                    break
                }
            } catch (_: Exception) { }
        }

        val text = pageText ?: return
        val candidates = mutableSetOf<String>()

        // Cari langsung di text
        collectVideoUrls(text, candidates)

        // Unpack Dean Edwards JS dulu
        val unpacked = unpackDeanEdwards(text)
        if (unpacked != text) {
            collectVideoUrls(unpacked, candidates)
        }

        // Kadang ada multiple eval bertingkat
        var current = unpacked
        var depth = 0
        while (depth < 3) {
            val next = unpackDeanEdwards(current)
            if (next == current) break
            collectVideoUrls(next, candidates)
            current = next
            depth++
        }

        // Decode unicode escapes jika ada
        val decoded = decodeUnicodeEscapes(current)
        if (decoded != current) collectVideoUrls(decoded, candidates)

        for (url in candidates) {
            val fixed = url.replace("\\/", "/")
            callback(newExtractorLink("Rebahin", "Rebahin - $host", fixed) {
                this.referer = embedUrl
                this.quality = if (fixed.contains("1080")) 3 else if (fixed.contains("720")) 2 else 3
            })
        }
    }

    /**
     * Kumpulkan URL m3u8/mp4 dari string apapun.
     */
    private fun collectVideoUrls(text: String, out: MutableSet<String>) {
        val patterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex(""""file"\s*:\s*"([^"]+\.mp4[^"]*)""""),
            Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
            Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)"""),
            Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""")
        )
        for (p in patterns) {
            p.findAll(text).forEach { m ->
                val u = m.groupValues[1].trim()
                if (u.isNotBlank()) out.add(u)
            }
        }
    }

    /**
     * Dean Edwards Packer unpacker.
     * Format: eval(function(p,a,c,k,e,d){...}('PAYLOAD',RADIX,COUNT,'SYM|TAB'.split('|'),0,{}))
     */
    private fun unpackDeanEdwards(input: String): String {
        // Cari blok eval(...) yang diakhiri dengan pola khas packer
        val regex = Regex("""\}\s*\(\s*['"]([^'"]*(?:\\.[^'"]*)*)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([^'"]*)['"]\s*\.split\s*\(\s*['"]\|['"]\s*\)""")
        val match = regex.find(input) ?: return input

        val payloadRaw = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: return input
        val count = match.groupValues[3].toIntOrNull() ?: return input
        val symtabRaw = match.groupValues[4]

        // Unescape payload (\\' -> ', \\\\ -> \\, \n -> newline, dll)
        val payload = payloadRaw
            .replace("\\'", "'")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

        val symtab = symtabRaw.split("|").toMutableList()

        fun unbase(c: Int): String {
            if (c < radix) return ""
            val head = unbase(c / radix)
            val r = c % radix
            val ch = if (r > 35) (r + 29).toChar().toString() else r.toString(36)
            return head + ch
        }

        // Isi symtab yang kosong
        while (symtab.size < count) {
            symtab.add(unbase(symtab.size))
        }

        // Buat kamus kata
        val dict = HashMap<String, String>()
        for (i in symtab.indices) {
            val key = unbase(i)
            val v = symtab[i]
            dict[key] = if (v.isBlank()) key else v
        }

        // Ganti semua token \w+ dengan padanannya
        return Regex("""\b\w+\b""").replace(payload) { m ->
            dict[m.value] ?: m.value
        }
    }

    private fun decodeUnicodeEscapes(s: String): String {
        val re = Regex("""\\u([0-9a-fA-F]{4})""")
        return re.replace(s) { m ->
            try {
                m.groupValues[1].toInt(16).toChar().toString()
            } catch (_: Exception) {
                m.value
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
