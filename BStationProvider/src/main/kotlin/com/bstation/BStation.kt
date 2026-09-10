package com.bstation

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class BStation : MainAPI() {
    override var mainUrl = "https://www.bilibili.tv"
    override var name = "BStation"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private val apiUrl = "https://api.bilibili.tv"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl
    )

    // ================================================================
    //  FIX POSTER — strip @720w_405h_... suffix
    //  BStation pakai: //pic.bstarstatic.com/xxx.png@720w_405h_1e_1c_90q
    //  Kita pakai versi original: //pic.bstarstatic.com/xxx.png
    // ================================================================
    private fun String?.cleanImage(): String? {
        if (this.isNullOrBlank()) return null
        if (this.startsWith("data:")) return null
        var u = this.substringBefore("@").substringBefore(" ").trim()
        u = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> u
        }
        return u.ifBlank { null }
    }

    private fun Element.extractPoster(): String? {
        // Prioritas: picture > source/srcset > img/src
        this.select("img").forEach { img ->
            // coba src dulu karena BStation embed full URL di src
            img.attr("src").cleanImage()?.let { return it }
            img.attr("data-src").cleanImage()?.let { return it }
        }
        this.select("source").forEach { src ->
            val candidate = src.attr("srcset").substringBefore(",").substringBefore(" ")
            candidate.cleanImage()?.let { return it }
        }
        return null
    }

    // ================================================================
    //  CARD PARSER
    // ================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        // Cari anchor ke /play/ atau /video/
        val a = if (this.tagName() == "a") this
        else this.selectFirst("a[href*=/play/], a[href*=/video/]")
        ?: return null

        val rawHref = a.attr("href").ifBlank { return null }
        val cleanHref = rawHref.substringBefore("?").trim()
        val fullUrl = when {
            cleanHref.startsWith("//") -> "https:$cleanHref"
            cleanHref.startsWith("/") -> "$mainUrl$cleanHref"
            else -> cleanHref
        }
        if (!fullUrl.contains("/play/") && !fullUrl.contains("/video/")) return null

        // Title — cari di dalam anchor atau parent card
        val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: a.attr("title").ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst(".card-title")?.text()?.ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null

        if (title.length < 2) return null

        // Poster
        val poster = a.extractPoster() ?: this.extractPoster()

        val type = if (fullUrl.contains("/play/")) TvType.Anime else TvType.Movie

        return newAnimeSearchResponse(title, fullUrl, type) {
            this.posterUrl = poster
        }
    }

    // ================================================================
    //  MAIN PAGE
    // ================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/id/" to "Populer",
        "$mainUrl/id/anime" to "Anime",
        "$mainUrl/id/trending" to "Trending",
        "$mainUrl/id/short-drama" to "Dracin",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        // Ambil dari berbagai section container
        val items = document.select(
            "li.section__list__item, " +
            "li.scroll-wrap__list__item, " +
            "div.card-item, " +
            "div.recommended-container .card-item"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        if (items.isNotEmpty()) {
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, false)
    }

    // ================================================================
    //  SEARCH
    // ================================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/id/search-result?q=${query.replace(" ", "%20")}"
        val document = app.get(url).document

        val items = document.select(
            "li.section__list__item, " +
            "li.scroll-wrap__list__item, " +
            "div.card-item, " +
            "div.bstar-video-card"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return items.toNewSearchResponseList()
    }

    // ================================================================
    //  LOAD — parse __initialState dari HTML play page
    //  Ini SANGAT reliable karena data sudah ada di HTML
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text

        // Ambil window.__initialState=(function(...){...}(...)) — ekstrak argumennya
        // Format: window.__initialState=(function(...){return {...}}(arg1,arg2,...))
        // Kita cari string ogv:{...} di dalam HTML
        val ogvSection = extractJsonObject(html, "\"ogv\":{")
            ?: extractJsonObject(html, "ogv:{")

        val seasonId = Regex(""""season":\{[^}]*"season_id":(\d+)""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""/play/(\d+)""").find(url)?.groupValues?.get(1)
            ?: return null

        val title = Regex(""""season":\{[^}]*"title":"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\u002F", "/")
            ?: "Unknown"

        val description = Regex(""""description":"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\u002F", "/")
            ?.replace("\\\"", "\"")

        val poster = Regex(""""horizontal_cover":"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\u002F", "/")
            ?.cleanImage()
            ?: Regex(""""vertical_cover":"([^"]+)"""")
                .find(html)?.groupValues?.get(1)
                ?.replace("\\u002F", "/")
                ?.cleanImage()

        // Extract semua episode_id + title dari sectionsList
        // Format: episodes:[{cover:"...",episode_id:27387373,...,long_title_display:"Keseharian Baru",title_display:"E1 - Keseharian Baru",...}]
        val episodes = mutableListOf<Episode>()
        val episodeRegex = Regex(
            """"episode_id":(\d+),"[^"]*":"[^"]*","[^"]*":"[^"]*","[^"]*":"([^"]+)","[^"]*":"([^"]+)""""
        )
        // Fallback regex yang lebih fleksibel
        val epRegex = Regex(""""episode_id":(\d+).*?"title_display":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

        epRegex.findAll(html).forEach { match ->
            val epId = match.groupValues[1]
            val titleDisplay = match.groupValues[2].replace("\\u002F", "/")
            val epNum = Regex("""E(\d+)""").find(titleDisplay)?.groupValues?.get(1)?.toIntOrNull()

            if (episodes.none { it.data == "pgc:$epId" }) {
                episodes.add(newEpisode("pgc:$epId") {
                    this.name = titleDisplay
                    this.episode = epNum ?: (episodes.size + 1)
                })
            }
        }

        // Kalau tidak ada episode, fallback ke API
        if (episodes.isEmpty()) {
            val seriesUrl = "$apiUrl/intl/gateway/web/v2/ogv/play/series" +
                    "?s_locale=id_ID&platform=web&season_id=$seasonId"
            val resp = try {
                app.get(seriesUrl, headers = apiHeaders).parsedSafe<SeriesApiResponse>()
            } catch (e: Exception) { null }

            resp?.data?.sections?.forEach { sec ->
                sec.episodes?.forEach { ep ->
                    val id = ep.episodeId ?: return@forEach
                    episodes.add(newEpisode("pgc:$id") {
                        this.name = ep.titleDisplay ?: ep.longTitleDisplay ?: "Episode"
                        this.episode = Regex("""E(\d+)""").find(ep.titleDisplay ?: "")
                            ?.groupValues?.get(1)?.toIntOrNull()
                    })
                }
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Utility: extract balanced {} mulai dari prefix
    private fun extractJsonObject(html: String, prefix: String): String? {
        val start = html.indexOf(prefix)
        if (start < 0) return null
        val openBrace = html.indexOf('{', start + prefix.length - 1)
        if (openBrace < 0) return null
        var depth = 0
        for (i in openBrace until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(openBrace, i + 1)
                }
            }
        }
        return null
    }

    // ================================================================
    //  LOAD LINKS
    // ================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = when {
            data.startsWith("pgc:") -> data.removePrefix("pgc:").toLongOrNull()
            else -> data.toLongOrNull()
        } ?: return false

        // Play URL API — sesuai DevTools
        val playUrl = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&ep_id=$epId" +
                "&tk=&qn=64&type=0&device=wap&tf=0"
        val response = try {
            app.get(playUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
        } catch (e: Exception) { null }

        var any = false

        // durl (FLV/mp4) - paling kompatibel
        response?.data?.durl?.forEach { d ->
            val u = d.url ?: return@forEach
            callback.invoke(newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            })
            any = true
            d.backupUrl?.forEach { b ->
                callback.invoke(newExtractorLink(name, "$name (backup)", b, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
            }
        }

        // DASH fallback
        if (response?.data?.durl.isNullOrEmpty()) {
            response?.data?.dash?.video?.forEach { v ->
                val u = v.baseUrl ?: return@forEach
                val q = when (v.id) {
                    80 -> Qualities.P1080.value
                    64 -> Qualities.P720.value
                    32 -> Qualities.P480.value
                    16 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(newExtractorLink(name, "$name ${v.id ?: ""}P", u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = q
                })
                any = true
            }
        }

        // Subtitle
        try {
            val subUrl = "$apiUrl/intl/gateway/web/v2/subtitle" +
                    "?s_locale=id_ID&platform=web&episode_id=$epId"
            app.get(subUrl, headers = apiHeaders)
                .parsedSafe<SubtitleApiResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val u = sub.url ?: return@forEach
                    subtitleCallback.invoke(newSubtitleFile(sub.lan ?: "Unknown", u))
                }
        } catch (_: Exception) { }

        return any
    }

    // ================================================================
    //  DATA CLASSES
    // ================================================================
    data class SeriesApiResponse(@JsonProperty("data") val data: SeriesApiData?)
    data class SeriesApiData(@JsonProperty("sectionsList") val sections: List<SeriesApiSection>?)
    data class SeriesApiSection(@JsonProperty("episodes") val episodes: List<SeriesApiEpisode>?)
    data class SeriesApiEpisode(
        @JsonProperty("episode_id") val episodeId: Long?,
        @JsonProperty("title_display") val titleDisplay: String?,
        @JsonProperty("long_title_display") val longTitleDisplay: String?,
        @JsonProperty("cover") val cover: String?
    )

    data class PlayUrlResponse(@JsonProperty("data") val data: PlayUrlData?)
    data class PlayUrlData(
        @JsonProperty("durl") val durl: List<Durl>?,
        @JsonProperty("dash") val dash: Dash?
    )
    data class Durl(
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?
    )
    data class Dash(@JsonProperty("video") val video: List<DashVideo>?)
    data class DashVideo(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("baseUrl") val baseUrl: String?
    )

    data class SubtitleApiResponse(@JsonProperty("data") val data: SubtitleApiData?)
    data class SubtitleApiData(@JsonProperty("subtitles") val subtitles: List<SubtitleItem>?)
    data class SubtitleItem(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
