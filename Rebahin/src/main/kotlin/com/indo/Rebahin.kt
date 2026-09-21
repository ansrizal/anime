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

        // === TV Series: deteksi episode ===
        val episodeUrls = mutableListOf<Episode>()
        val seenEpisodes = mutableSetOf<String>()

        // Pola 1: JSON "episodes":[...]
        Regex("\"episodes\"\\s*:\\s*\\[([^\\]]+)\\]").find(html)?.let { match ->
            val epsJson = match.groupValues[1]
            Regex("\"episodeNumber\"\\s*:\\s*(\\d+)[^}]*?\"seasonNumber\"\\s*:\\s*(\\d+)").findAll(epsJson).forEach { ep ->
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

        // Pola 2: <a href="...episode...">
        if (episodeUrls.isEmpty()) {
            doc.select("a[href*=/episode], a[href*=/eps-], a[href*=/season-], a[href*=-episode-], a[href*=-eps-]")
                .asIterable().forEach { a ->
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (href.isBlank()) return@forEach
                    val fixed = fixUrl(href)
                    if (fixed == url || !seenEpisodes.add(fixed)) return@forEach
                    val epNum = Regex("""(?:episode|eps)[-/](\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val seasonNum = Regex("""season[-/](\d+)""").find(fixed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episodeUrls.add(newEpisode(fixed) {
                        this.name = if (epNum != null) "Eps $epNum" else a.text().ifBlank { "Episode ${episodeUrls.size + 1}" }
                        this.episode = epNum ?: (episodeUrls.size + 1)
                        this.season = seasonNum ?: 1
                    })
                }
        }

        // Fallback: tidak ada episode terdeteksi → jadikan Movie response
        // supaya tombol Play tetap muncul
        if (episodeUrls.isEmpty()) {
            println("Rebahin: no episodes found for $url, treating as movie")
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
        var count = 0
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            count++
            callback(link)
        }

        println("Rebahin: loadLinks start for $data")

        val resp = try {
            app.get(data)
        } catch (e: Exception) {
            println("Rebahin: fetch failed: ${e.message}")
            return false
        }
        val html = resp.text ?: run {
            println("Rebahin: empty response body")
            return false
        }
        val doc = resp.document

        // === 1. Kumpulkan URL iframe ===
        val embedUrls = linkedSetOf<String>()
        doc.select("iframe").forEach { iframe ->
            listOf("abs:src", "src", "abs:data-src", "data-src",
                   "abs:data-litespeed-src", "data-litespeed-src",
                   "abs:data-lazy-src", "data-lazy-src").forEach { attr ->
                val v = iframe.attr(attr)
                if (v.isNotBlank()) {
                    val abs = toAbsolute(v, data)
                    if (!abs.contains("youtube", true) && !abs.contains("youtu.be", true)) {
                        embedUrls.add(abs)
                    }
                }
            }
        }
        // Regex fallback pada raw HTML
        Regex("""<iframe[^>]*?\s(?:src|data-src|data-litespeed-src|data-lazy-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val abs = toAbsolute(m.groupValues[1], data)
                if (!abs.contains("youtube", true) && !abs.contains("youtu.be", true)) {
                    embedUrls.add(abs)
                }
            }

        println("Rebahin: found ${embedUrls.size} embed url(s): $embedUrls")

        for (embedUrl in embedUrls) {
            println("Rebahin: trying embed=$embedUrl")

            // Langsung link? (mp4/m3u8)
            if (embedUrl.contains(".mp4") || embedUrl.contains(".m3u8")) {
                wrappedCallback(newExtractorLink("Rebahin", "Direct", embedUrl) {
                    this.referer = data
                })
                continue
            }

            // (a) Coba extractor bawaan Cloudstream
            try {
                loadExtractor(embedUrl, data, subtitleCallback, wrappedCallback)
                println("Rebahin: loadExtractor done, count=$count")
            } catch (e: Exception) {
                println("Rebahin: loadExtractor failed: ${e.message}")
            }
            if (count > 0) continue

            // (b) Coba domain alternatif VidHide
            if (embedUrl.contains("vidhide", true)) {
                val alts = listOf("vidhide.com", "vidhide.pro", "vidhide.to", "vidhide.su")
                for (alt in alts) {
                    val altUrl = embedUrl
                        .replace("vidhide.org", alt)
                        .replace("vidhide.com", alt)
                        .replace("vidhide.pro", alt)
                        .replace("vidhide.to", alt)
                    if (altUrl == embedUrl) continue
                    try {
                        loadExtractor(altUrl, data, subtitleCallback, wrappedCallback)
                        println("Rebahin: loadExtractor($alt) done, count=$count")
                    } catch (_: Exception) {}
                    if (count > 0) break
                }
            }
            if (count > 0) continue

            // (c) Manual extraction (cari m3u8 langsung di halaman embed)
            try {
                manualExtract(embedUrl, data, wrappedCallback)
                println("Rebahin: manual done, count=$count")
            } catch (e: Exception) {
                println("Rebahin: manual failed: ${e.message}")
            }
        }

        println("Rebahin: loadLinks done, total=$count")
        return count > 0
    }

    private suspend fun manualExtract(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val resp = try { app.get(embedUrl, referer = referer) } catch (_: Exception) { return }
        val text = resp.text ?: return
        val unescaped = text.replace("\\/", "/")

        val patterns = listOf(
            Regex(""""hls4"\s*:\s*"([^"]+)""""),
            Regex(""""hls2"\s*:\s*"([^"]+)""""),
            Regex(""""hls"\s*:\s*"([^"]+)""""),
            Regex(""""file"\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)""""),
            Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
            Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*"""),
            Regex("""https?://[^"'\s\\]+\.mp4[^"'\s\\]*""")
        )

        for (p in patterns) {
            for (source in listOf(text, unescaped)) {
                val m = p.find(source) ?: continue
                val url = if (m.groupValues.size > 1) m.groupValues[1] else m.value
                if (url.isNotBlank() && (url.contains("m3u8") || url.contains("mp4"))) {
                    println("Rebahin: manual found -> $url")
                    callback(newExtractorLink("Rebahin", "Manual", url) {
                        this.referer = embedUrl
                    })
                    return
                }
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
