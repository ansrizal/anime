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

        // --- TV Series: kumpulkan episode dari berbagai strategi ---
        val episodes = mutableListOf<Pair<String, Int>>() // Pair(url, epNum)
        val seenUrls = mutableSetOf<String>()
        val seenEps = mutableSetOf<Int>()

        // Slug untuk konstruksi URL: "young-sherlock-2026" dari ".../tv/young-sherlock-2026/"
        val slug = url.trimEnd('/').substringAfterLast('/')
        Log.i(TAG, "load() slug=$slug")

        // === Strategi A: HTML <a href=".../eps/...-episode-N/"> ===
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

        // === Strategi B: JSON "episodes":[...] ===
        if (episodes.isEmpty()) {
            Regex("\"episodes\"\\s*:\\s*\\[([\\s\\S]*?)\\]\\s*[,}]").findAll(html).forEach { match ->
                val epsJson = match.groupValues[1]
                val epNums = Regex("\"episodeNumber\"\\s*:\\s*(\\d+)").findAll(epsJson)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                val seasonNums = Regex("\"seasonNumber\"\\s*:\\s*(\\d+)").findAll(epsJson)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                Log.i(TAG, "load() JSON episodes -> epNums=$epNums, seasonNums=$seasonNums")
                epNums.forEachIndexed { i, epNum ->
                    val seasonNum = seasonNums.getOrNull(i) ?: 1
                    val epUrl = "$mainUrl/eps/$slug-season-$seasonNum-episode-$epNum/"
                    if (seenUrls.add(epUrl) && seenEps.add(epNum)) {
                        episodes.add(epUrl to epNum)
                    }
                }
            }
        }

        Log.i(TAG, "load() total episodes=${episodes.size}")

        val episodeUrls = episodes.map { (epUrl, epNum) ->
            newEpisode(epUrl) {
                this.name = "Eps $epNum"
                this.episode = epNum
                this.season = 1
            }
        }

        // Selalu kembalikan TvSeriesLoadResponse untuk URL /tv/,
        // walaupun tidak ada episode terdeteksi — supaya CloudStream tetap
        // memperlakukan sebagai series (bukan memutar URL series sebagai movie).
        if (episodeUrls.isEmpty()) {
            Log.w(TAG, "load() no episodes detected, returning series with 1 dummy pointing to series URL")
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

        Log.i(TAG, "loadLinks() found ${filteredEmbeds.size} embed(s): $filteredEmbeds")

        // === 2. Built-in extractor untuk semua embed ===
        for (embedUrl in filteredEmbeds) {
            Log.i(TAG, "loadLinks() trying built-in loadExtractor on $embedUrl")
            try {
                loadExtractor(embedUrl, data, subtitleCallback, wrappedCallback)
                Log.i(TAG, "loadLinks() loadExtractor done, linkCount=$linkCount")
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() loadExtractor exception: ${e.message}")
            }
        }

        // === 3. Custom VidHide extractor (jika built-in gagal) ===
        if (linkCount == 0) {
            for (embedUrl in filteredEmbeds) {
                Log.i(TAG, "loadLinks() trying custom extractVidHide on $embedUrl")
                try {
                    extractVidHide(embedUrl, data, wrappedCallback)
                    Log.i(TAG, "loadLinks() extractVidHide done, linkCount=$linkCount")
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks() extractVidHide exception: ${e.message}")
                }
            }
        }

        // === 4. Fallback: JSON sources/playerSources di HTML ===
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

    private suspend fun extractVidHide(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = try { java.net.URL(embedUrl).host } catch (_: Exception) { "" }
        Log.i(TAG, "extractVidHide() START host=$host")

        // Beberapa varian header
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
        for ((i, headers) in headersList.withIndex()) {
            try {
                Log.i(TAG, "extractVidHide() fetch attempt #$i")
                val r = app.get(embedUrl, headers = headers)
                if (!r.text.isNullOrBlank()) {
                    pageText = r.text
                    Log.i(TAG, "extractVidHide() got ${r.text.length} chars")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "extractVidHide() fetch #$i failed: ${e.message}")
            }
        }

        val text = pageText ?: run {
            Log.e(TAG, "extractVidHide() could not fetch page")
            return
        }

        val candidates = mutableSetOf<String>()

        // Langsung cari di raw
        collectVideoUrls(text, candidates)
        Log.i(TAG, "extractVidHide() after raw collect: ${candidates.size} candidate(s)")

        // Unpack JS
        val unpacked = unpackDeanEdwards(text)
        if (unpacked != text) {
            Log.i(TAG, "extractVidHide() unpacked (${unpacked.length} chars)")
            collectVideoUrls(unpacked, candidates)
            Log.i(TAG, "extractVidHide() after unpack: ${candidates.size} candidate(s)")
        } else {
            Log.w(TAG, "extractVidHide() unpack returned same string")
        }

        // Unpack berulang (beberapa pakai nested)
        var current = unpacked
        var depth = 0
        while (depth < 3) {
            val next = unpackDeanEdwards(current)
            if (next == current) break
            collectVideoUrls(next, candidates)
            current = next
            depth++
        }

        val decoded = decodeUnicodeEscapes(current)
        if (decoded != current) collectVideoUrls(decoded, candidates)

        Log.i(TAG, "extractVidHide() total candidates=${candidates.size}")

        for (url in candidates) {
            val fixed = url.replace("\\/", "/")
            Log.i(TAG, "extractVidHide() emitting: $fixed")
            callback(newExtractorLink("Rebahin", "Rebahin - $host", fixed) {
                this.referer = embedUrl
                this.quality = if (fixed.contains("1080")) 3 else if (fixed.contains("720")) 2 else 3
            })
        }
    }

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
     * Format khas: eval(function(p,a,c,k,e,d){...}('PAYLOAD',RADIX,COUNT,'SYM|TAB'.split('|'),0,{}))
     */
    private fun unpackDeanEdwards(input: String): String {
        // Cari blok packer dengan lebih longgar
        val regex = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)[\s\S]*?\}\s*\(\s*['"]((?:\\[\s\S]|[^'"])*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]((?:\\[\s\S]|[^'"])*?)['"]\s*\.split\s*\(\s*['"]\|['"]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = regex.find(input) ?: run {
            Log.w(TAG, "unpackDeanEdwards() no match for packer signature")
            return input
        }

        val payloadRaw = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: return input
        val count = match.groupValues[3].toIntOrNull() ?: return input
        val symtabRaw = match.groupValues[4]

        Log.i(TAG, "unpackDeanEdwards() radix=$radix count=$count symtabLen=${symtabRaw.length}")

        val payload = payloadRaw
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\/", "/")

        val symtab = symtabRaw.split("|").toMutableList()

        fun unbase(c: Int): String {
            val head = if (c < radix) "" else unbase(c / radix)
            val r = c % radix
            val ch = if (r > 35) (r + 29).toChar().toString() else r.toString(36)
            return head + ch
        }

        while (symtab.size < count) symtab.add(unbase(symtab.size))

        val dict = HashMap<String, String>()
        for (i in symtab.indices) {
            val key = unbase(i)
            val v = symtab[i]
            dict[key] = if (v.isBlank()) key else v
        }

        val result = Regex("""\b\w+\b""").replace(payload) { m ->
            dict[m.value] ?: m.value
        }

        Log.i(TAG, "unpackDeanEdwards() unpacked payload: ${result.take(200)}")
        return result
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
