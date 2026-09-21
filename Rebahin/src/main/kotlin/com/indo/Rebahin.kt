package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
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
                val title = el.selectFirst("a[title]")?.attr("title") ?: el.selectFirst("h2, h3")?.text() ?: return@mapNotNull null
                val href = fixUrl(el.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val poster = el.selectFirst("img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
            if (items.isNotEmpty()) sections.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(sections, hasNext = true)
    }

    private suspend fun getApiSection(name: String, page: Int, apiPath: String): HomePageList? {
        val resp = try { app.get("$mainUrl/$apiPath?page=$page&limit=24") } catch(_: Exception) { return null }
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
        val resp = try { app.get("$mainUrl/api/search?q=$query") } catch(_: Exception) { null }
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
                    newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster; this.score = Score.from10(voteAvg) }
                } else {
                    newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster; this.score = Score.from10(voteAvg) }
                }
            }
        }

        // Scraper fallback
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.listupd article, div.bsx, div.ml-item, article").asIterable().mapNotNull { el ->
            val title = el.selectFirst("a[title]")?.attr("title") ?: el.selectFirst("h2, h3")?.text() ?: return@mapNotNull null
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
        } ?: doc.select("a[href*=genre], a[href*=category]").asIterable().map { it.text() }.filter { it.isNotBlank() }

        val voteAvg = Regex("\"voteAverage\":([0-9.]+)").find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        val isSeries = url.contains("/tv/")
        return if (isSeries) {
            val episodeUrls = mutableListOf<Episode>()
            val episodesMatch = Regex("\"episodes\":\\[([^\\]]+)\\]").find(html)
            if (episodesMatch != null) {
                val epsJson = episodesMatch.groupValues[1]
                Regex("\"episodeNumber\":(\\d+),\"seasonNumber\":(\\d+)").findAll(epsJson).forEach { ep ->
                    val epNum = ep.groupValues[1].toIntOrNull()
                    val seasonNum = ep.groupValues[2].toIntOrNull()
                    if (epNum != null) {
                        val epUrl = if (seasonNum != null) "$url/season-$seasonNum/episode-$epNum"
                        else "$url/season-1/episode-$epNum"
                        episodeUrls.add(newEpisode(epUrl) {
                            this.name = "Eps $epNum"
                            this.episode = epNum
                            this.season = seasonNum ?: 1
                        })
                    }
                }
            }
            if (episodeUrls.isEmpty()) {
                doc.select("a[href*=/episode-]").asIterable().forEach { a ->
                    val href = a.attr("href").ifBlank { return@forEach }
                    val epNum = Regex("episode-(\\d+)$").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (epNum != null) {
                        episodeUrls.add(newEpisode(fixUrl(href)) {
                            this.name = "Eps $epNum"
                            this.episode = epNum
                        })
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(data)
        val raw = resp.text ?: return true
        val html = raw.replace("\\\"", "\"")
        val doc = resp.document
        var found = false

        // Strategy 1: JSON "sources" / "playerSources"
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
                val quality = Regex("\"quality\":\"([^\"]+)\"").find(obj)?.groupValues?.getOrNull(1) ?: "FHD"
                if (videoUrl != null) {
                    callback(newExtractorLink("Rebahin", "Rebahin - $quality", videoUrl) {
                        this.quality = parseQuality(quality)
                        this.referer = "$mainUrl/"
                    })
                    found = true
                }
                objPos = objEnd + 1
            }
        }

        // Strategy 2: iframe embed langsung di halaman
        doc.select("iframe[src]").asIterable().forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() &&
                !src.contains("youtube", true) &&
                !src.contains("youtu.be", true) &&
                !src.contains("google", true)
            ) {
                val fixed = if (src.startsWith("//")) "https:$src" else fixUrl(src)
                callback(newExtractorLink("Rebahin", "Rebahin - Embed", fixed) {
                    this.referer = "$mainUrl/"
                    this.isEmbed = true
                })
                found = true
            }
        }

        // Strategy 3: atribut data-url / data-src / data-embed / data-video
        doc.select("[data-url], [data-src], [data-embed], [data-video]").asIterable().forEach { el ->
            val url = el.attr("data-url").ifBlank {
                el.attr("data-src").ifBlank {
                    el.attr("data-embed").ifBlank {
                        el.attr("data-video")
                    }
                }
            }
            if (url.isNotBlank() && (url.startsWith("http") || url.startsWith("//"))) {
                val fixed = if (url.startsWith("//")) "https:$url" else url
                if (!fixed.contains("youtube", true) && !fixed.contains("youtu.be", true)) {
                    callback(newExtractorLink("Rebahin", "Rebahin - Server", fixed) {
                        this.referer = "$mainUrl/"
                        this.isEmbed = true
                    })
                    found = true
                }
            }
        }

        // Strategy 4: pola server muvipro (data-id + data-server) via AJAX
        val serverEls = doc.select("a[data-id][data-server], li[data-id][data-server], a[data-post][data-nume]")
        serverEls.asIterable().forEach { el ->
            val serverId = el.attr("data-id").ifBlank { el.attr("data-post") }
            val serverNum = el.attr("data-server").ifBlank { el.attr("data-nume") }
            val postType = el.attr("data-type").ifBlank { "movie" }
            if (serverId.isNotBlank()) {
                val actions = listOf("muvipro_player", "player_ajax", "idmuvi_player", "get_player")
                for (action in actions) {
                    try {
                        val ajaxResp = app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to action,
                                "id" to serverId,
                                "post" to serverId,
                                "nume" to serverNum,
                                "server" to serverNum,
                                "type" to postType
                            )
                        )
                        val ajaxText = ajaxResp.text ?: continue
                        val embedUrl = Regex("""src=["']([^"']+)["']""").find(ajaxText)?.groupValues?.getOrNull(1)
                            ?: Regex("""(https?:)?//[^\s"'<>]+\.(mp4|m3u8)[^\s"'<>]*""").find(ajaxText)?.value
                        if (!embedUrl.isNullOrBlank() &&
                            !embedUrl.contains("youtube", true) &&
                            !embedUrl.contains("youtu.be", true)
                        ) {
                            val fixed = if (embedUrl.startsWith("//")) "https:$embedUrl" else fixUrl(embedUrl)
                            callback(newExtractorLink("Rebahin", "Rebahin - Server $serverNum", fixed) {
                                this.referer = "$mainUrl/"
                                this.isEmbed = !fixed.contains(".mp4") && !fixed.contains(".m3u8")
                            })
                            found = true
                            break
                        }
                    } catch (_: Exception) {
                        // coba action berikutnya
                    }
                }
            }
        }

        return found
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
