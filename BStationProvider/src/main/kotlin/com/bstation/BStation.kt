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
        "Referer" to "https://www.bilibili.tv/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://www.bilibili.tv"
    )

    // ================================================================
    //  HELPER — bersihkan URL gambar Bilibili
    //  Bilibili CDN: //i0.hdslb.com/xxx.jpg@320w_200h_1c
    //  Harus: buang @suffix, tambah https:
    // ================================================================
    private fun String?.cleanImageUrl(): String? {
        if (this.isNullOrBlank()) return null
        if (this.startsWith("data:")) return null
        var u = this.substringBefore("@")
            .substringBefore(",")
            .substringBefore(" ")
            .trim()
        u = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> u
        }
        return u.ifBlank { null }
    }

    // ================================================================
    //  HELPER — extract poster dari wrapper element
    //  Cek berurutan: img.data-src > img.src > source.data-srcset > picture
    //  Skip kalau src/data-src mulai dengan "data:" (base64 placeholder)
    // ================================================================
    private fun Element.extractPoster(): String? {
        // 1. Coba semua <img> di dalam
        this.select("img").forEach { img ->
            val attrs = listOf(
                img.attr("data-src"),
                img.attr("data-original"),
                img.attr("data-lazy"),
                img.attr("src")
            )
            for (a in attrs) {
                val clean = a.cleanImageUrl()
                if (!clean.isNullOrBlank()) return clean
            }
        }
        // 2. Coba <source>
        this.select("source").forEach { src ->
            val attrs = listOf(
                src.attr("data-srcset"),
                src.attr("srcset")
            )
            for (a in attrs) {
                val first = a.substringBefore(",").substringBefore(" ").trim()
                val clean = first.cleanImageUrl()
                if (!clean.isNullOrBlank()) return clean
            }
        }
        // 3. Coba style="background-image: url(...)"
        val style = this.attr("style")
        if (style.contains("url(")) {
            val m = Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(style)
            val clean = m?.groupValues?.get(1).cleanImageUrl()
            if (!clean.isNullOrBlank()) return clean
        }
        return null
    }

    // ================================================================
    //  HELPER — parse kartu video dari HTML
    // ================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        // Cari <a> ke /play/ atau /video/
        val a = if (this.tagName() == "a") this
        else this.selectFirst("a[href*=play], a[href*=video]")
        ?: return null

        val rawHref = a.attr("href").ifBlank { return null }

        // Buang query string, biarkan hanya path
        val cleanHref = rawHref.substringBefore("?")
        val href = when {
            cleanHref.startsWith("//") -> "https:$cleanHref"
            cleanHref.startsWith("/") -> "$mainUrl$cleanHref"
            else -> cleanHref
        }
        if (!href.contains("/play/") && !href.contains("/video/")) return null

        // Title
        val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title")?.text()?.ifBlank { null }
            ?: a.selectFirst(".card-title")?.text()?.ifBlank { null }
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

        // Poster
        val poster = a.extractPoster() ?: this.extractPoster()

        // Type: /play/ = PGC (anime), /video/ = UGC (movie)
        val type = if (href.contains("/play/")) TvType.Anime else TvType.Movie

        return newAnimeSearchResponse(title, href, type) {
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

        val items = document.select("a[href*=play], a[href*=video]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

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
        val items = document.select("a[href*=play], a[href*=video]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return items.toNewSearchResponseList()
    }

    // ================================================================
    //  LOAD — PGC (/play/) dan UGC (/video/)
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content").cleanImageUrl()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst(".video-info-desc, .desc, .bstar-meta__desc")?.text()

        // ==== PGC: /play/{season_id} atau /play/{season_id}/{episode_id} ====
        val playMatch = Regex("""/play/(\d+)(?:/(\d+))?""").find(url)
        if (playMatch != null) {
            val seasonId = playMatch.groupValues[1]
            val currentEpId = playMatch.groupValues.getOrNull(2)?.ifBlank { null }

            // Ambil list episode dari API
            val seriesUrl = "$apiUrl/intl/gateway/web/v2/ogv/play/series" +
                    "?s_locale=id_ID&platform=web&season_id=$seasonId"
            val seriesResponse = try {
                app.get(seriesUrl, headers = apiHeaders).parsedSafe<SeriesResponse>()
            } catch (e: Exception) { null }

            val episodes = mutableListOf<Episode>()
            seriesResponse?.data?.sections?.forEach { section ->
                section.episodes?.forEach { ep ->
                    val epId = ep.episodeId
                    if (epId != null) {
                        episodes.add(newEpisode("pgc:$epId") {
                            this.name = ep.longTitle?.ifBlank { null }
                                ?: ep.title?.ifBlank { null }
                                ?: "Episode ${ep.episodeNumber ?: episodes.size + 1}"
                            this.episode = ep.episodeNumber ?: (episodes.size + 1)
                            this.posterUrl = ep.cover.cleanImageUrl() ?: poster
                        })
                    }
                }
            }

            // Fallback kalau series kosong & URL punya episode id
            if (episodes.isEmpty() && currentEpId != null) {
                episodes.add(newEpisode("pgc:$currentEpId") {
                    this.name = "Episode 1"
                    this.episode = 1
                })
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
        }

        // ==== UGC: /video/{bvid} ====
        val videoMatch = Regex("""/video/(BV[a-zA-Z0-9]+)""").find(url)
        if (videoMatch != null) {
            val bvid = videoMatch.groupValues[1]
            return newMovieLoadResponse(title, url, TvType.Movie, "ugc:$bvid") {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Fallback
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
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
        return when {
            data.startsWith("pgc:") -> {
                val epId = data.removePrefix("pgc:").toLongOrNull() ?: return false
                playPgc(epId, subtitleCallback, callback)
            }
            data.startsWith("ugc:") -> {
                val bvid = data.removePrefix("ugc:")
                playUgc(bvid, subtitleCallback, callback)
            }
            data.toLongOrNull() != null -> {
                playPgc(data.toLong(), subtitleCallback, callback)
            }
            else -> {
                val m = Regex("""/play/\d+/(\d+)""").find(data)
                if (m != null) playPgc(m.groupValues[1].toLong(), subtitleCallback, callback)
                else false
            }
        }
    }

    // ================================================================
    //  PLAY PGC (anime/series resmi)
    // ================================================================
    private suspend fun playPgc(
        epId: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playUrl = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&ep_id=$epId" +
                "&tk=&qn=64&type=0&device=wap&tf=0"
        val response = try {
            app.get(playUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
        } catch (e: Exception) { null }

        var any = false

        // FLV (durl)
        response?.data?.durl?.forEach { item ->
            val u = item.url ?: return@forEach
            callback.invoke(newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            })
            any = true
            item.backupUrl?.forEach { b ->
                callback.invoke(newExtractorLink(name, "$name (backup)", b, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
            }
        }

        // DASH fallback
        if (response?.data?.durl.isNullOrEmpty()) {
            response?.data?.dash?.video?.forEach { v ->
                val u = v.baseUrl ?: v.base_url ?: return@forEach
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
                .parsedSafe<SubtitleResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val u = sub.url ?: return@forEach
                    subtitleCallback.invoke(newSubtitleFile(sub.lan ?: "Unknown", u))
                }
        } catch (_: Exception) { }

        return any
    }

    // ================================================================
    //  PLAY UGC (video creator /video/BV...)
    // ================================================================
    private suspend fun playUgc(
        bvid: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Step 1: coba playurl langsung dengan bvid
        val playUrl = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&bvid=$bvid" +
                "&qn=64&type=0&device=wap&tf=0"
        val response = try {
            app.get(playUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
        } catch (e: Exception) { null }

        var any = false
        response?.data?.durl?.forEach { item ->
            val u = item.url ?: return@forEach
            callback.invoke(newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            })
            any = true
        }

        if (response?.data?.durl.isNullOrEmpty()) {
            response?.data?.dash?.video?.forEach { v ->
                val u = v.baseUrl ?: v.base_url ?: return@forEach
                callback.invoke(newExtractorLink(name, "$name ${v.id ?: ""}P", u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                any = true
            }
        }

        return any
    }

    // ================================================================
    //  DATA CLASSES
    // ================================================================
    data class SeriesResponse(@JsonProperty("data") val data: SeriesData?)
    data class SeriesData(@JsonProperty("sections") val sections: List<Section>?)
    data class Section(@JsonProperty("episodes") val episodes: List<SeriesEpisode>?)
    data class SeriesEpisode(
        @JsonProperty("episode_id") val episodeId: Long?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("long_title") val longTitle: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("cover") val cover: String?
    )

    data class PlayUrlResponse(@JsonProperty("data") val data: PlayData?)
    data class PlayData(
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
        @JsonProperty("baseUrl") val baseUrl: String?,
        @JsonProperty("base_url") val base_url: String?
    )

    data class SubtitleResponse(@JsonProperty("data") val data: SubtitleData?)
    data class SubtitleData(@JsonProperty("subtitles") val subtitles: List<Subtitle>?)
    data class Subtitle(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
