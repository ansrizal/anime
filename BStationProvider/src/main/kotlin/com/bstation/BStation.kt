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
    //  POSTER CLEANER — buang @720w_405h DAN ?x-oss-process=...
    // ================================================================
    private fun String?.cleanImage(): String? {
        if (this.isNullOrBlank()) return null
        if (this.startsWith("data:")) return null
        var u = this.substringBefore("@")
            .substringBefore("?")
            .substringBefore(" ")
            .trim()
        u = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> u
        }
        return u.ifBlank { null }
    }

    private fun Element.extractPoster(): String? {
        this.select("img").forEach { img ->
            img.attr("src").cleanImage()?.let { return it }
            img.attr("data-src").cleanImage()?.let { return it }
        }
        this.select("source").forEach { src ->
            val c = src.attr("srcset").substringBefore(",").substringBefore(" ")
            c.cleanImage()?.let { return it }
        }
        return null
    }

    // ================================================================
    //  CARD PARSER
    // ================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val a = if (this.tagName() == "a") this
        else this.selectFirst("a[href*=/play/], a[href*=/video/]")
        ?: return null

        val rawHref = a.attr("href").ifBlank { return null }
        val cleanHref = rawHref.substringBefore("?")
        val fullUrl = when {
            cleanHref.startsWith("//") -> "https:$cleanHref"
            cleanHref.startsWith("/") -> "$mainUrl$cleanHref"
            else -> cleanHref
        }
        if (!fullUrl.contains("/play/") && !fullUrl.contains("/video/")) return null

        val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst(".card-title")?.text()?.ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null
        if (title.length < 2) return null

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
        val items = document.select(
            "li.section__list__item, li.scroll-wrap__list__item, " +
            "div.card-item, div.bstar-video-card"
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
            "li.section__list__item, li.scroll-wrap__list__item, " +
            "div.card-item, div.bstar-video-card"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return items.toNewSearchResponseList()
    }

    // ================================================================
    //  LOAD — pakai Jsoup selector .ep-item (jauh lebih reliable)
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" HD")?.substringBefore(" | ")
            ?.ifBlank { null }
            ?: document.selectFirst(".bstar-meta__title a")?.text()?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content").cleanImage()

        val description = document.selectFirst(".bstar-meta__desc")?.text()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        // Episode list dari DOM — paling simpel & akurat
        val episodes = document.select("a.ep-item").mapNotNull { epEl ->
            val href = epEl.attr("href").substringBefore("?")
            val epMatch = Regex("""/play/(\d+)/(\d+)""").find(href) ?: return@mapNotNull null
            val episodeId = epMatch.groupValues[2]
            val shortTitle = epEl.text().trim()          // "E1"
            val longTitle = epEl.attr("title")           // "Keseharian Baru"
            val epNum = Regex("""E(\d+)""").find(shortTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode("pgc:$episodeId") {
                this.name = if (longTitle.isNotBlank()) "$shortTitle - $longTitle" else shortTitle
                this.episode = epNum
                this.posterUrl = poster
            }
        }.distinctBy { it.data }

        return when {
            // Series dengan banyak episode
            episodes.isNotEmpty() -> newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }

            // URL /play/{season_id}/{ep_id} tapi episode list tidak ke-load → minimal 1 episode
            Regex("""/play/\d+/(\d+)""").containsMatchIn(url) -> {
                val epId = Regex("""/play/\d+/(\d+)""").find(url)!!.groupValues[1]
                val single = listOf(newEpisode("pgc:$epId") {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                })
                newTvSeriesLoadResponse(title, url, TvType.Anime, single) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }

            // URL /play/{season_id} tanpa episode_id — coba fetch HTML lagi dengan redirect
            // BStation akan otomatis redirect ke episode pertama
            Regex("""/play/\d+$""").containsMatchIn(url) -> {
                // Ambil halaman yang di-redirect (dengan episode pertama)
                val redirected = try {
                    val resp = app.get(url, allowRedirects = true)
                    resp.url
                } catch (e: Exception) { url }

                val epMatch = Regex("""/play/\d+/(\d+)""").find(redirected)
                if (epMatch != null) {
                    val epId = epMatch.groupValues[1]
                    val single = listOf(newEpisode("pgc:$epId") {
                        this.name = title
                        this.episode = 1
                    })
                    newTvSeriesLoadResponse(title, url, TvType.Anime, single) {
                        this.posterUrl = poster
                        this.plot = description
                    }
                } else {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.plot = description
                    }
                }
            }

            // UGC video /video/{aid}
            else -> {
                val aidMatch = Regex("""/video/(\d+)""").find(url)
                val data = if (aidMatch != null) "ugc:${aidMatch.groupValues[1]}" else url
                newMovieLoadResponse(title, url, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
        }
    }

    // ================================================================
    //  LOAD LINKS
    //  Data structure: data.playurl.video[].video_resource.url
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

        // 1. Coba FLV muxed (durl) via fnval=1
        val flvUrl = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&ep_id=$epId" +
                "&tk=&qn=64&type=0&device=wap&tf=0&fnval=1"
        val flvResp = try {
            app.get(flvUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
        } catch (e: Exception) { null }

        var any = false

        flvResp?.data?.playurl?.durl?.forEach { d ->
            val u = d.url?.ifBlank { null } ?: return@forEach
            callback.invoke(newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            })
            any = true
        }

        // 2. Fallback DASH (m4s terpisah audio+video)
        if (!any) {
            val dashUrl = "$apiUrl/intl/gateway/web/playurl" +
                    "?s_locale=id_ID&platform=web&ep_id=$epId" +
                    "&tk=&qn=64&type=0&device=wap&tf=0"
            val dashResp = try {
                app.get(dashUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
            } catch (e: Exception) { null }

            // Cari audio URL dulu (biar bisa di-fallback kalau player support)
            val audioUrl = dashResp?.data?.playurl?.audioResource
                ?.firstOrNull { !it.url.isNullOrBlank() }?.url

            dashResp?.data?.playurl?.video?.forEach { v ->
                val u = v.videoResource?.url?.ifBlank { null } ?: return@forEach
                val q = when (v.streamInfo?.quality) {
                    112, 80 -> Qualities.P1080.value
                    64 -> Qualities.P720.value
                    32 -> Qualities.P480.value
                    16 -> Qualities.P360.value
                    6 -> Qualities.P240.value
                    5 -> Qualities.P144.value
                    else -> Qualities.Unknown.value
                }
                val label = v.streamInfo?.descWords?.ifBlank { null } ?: "${v.streamInfo?.quality ?: ""}P"

                callback.invoke(newExtractorLink(name, "$name $label", u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = q
                })
                any = true
            }
        }

        // 3. Subtitle
        try {
            val subUrl = "$apiUrl/intl/gateway/web/v2/subtitle" +
                    "?s_locale=id_ID&platform=web&episode_id=$epId"
            app.get(subUrl, headers = apiHeaders)
                .parsedSafe<SubtitleApiResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val u = sub.url?.ifBlank { null } ?: return@forEach
                    subtitleCallback.invoke(newSubtitleFile(sub.lan ?: "Unknown", u))
                }
        } catch (_: Exception) {}

        return any
    }

    // ================================================================
    //  DATA CLASSES
    // ================================================================
    data class PlayUrlResponse(@JsonProperty("data") val data: PlayUrlData?)
    data class PlayUrlData(
        @JsonProperty("playurl") val playurl: PlayurlData?
    )
    data class PlayurlData(
        @JsonProperty("durl") val durl: List<Durl>?,
        @JsonProperty("video") val video: List<VideoItem>?,
        @JsonProperty("audio_resource") val audioResource: List<AudioResource>?
    )
    data class Durl(
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?
    )
    data class VideoItem(
        @JsonProperty("video_resource") val videoResource: VideoResource?,
        @JsonProperty("stream_info") val streamInfo: StreamInfo?
    )
    data class VideoResource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?,
        @JsonProperty("quality") val quality: Int?
    )
    data class StreamInfo(
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("desc_words") val descWords: String?
    )
    data class AudioResource(
        @JsonProperty("url") val url: String?
    )

    data class SubtitleApiResponse(@JsonProperty("data") val data: SubtitleApiData?)
    data class SubtitleApiData(@JsonProperty("subtitles") val subtitles: List<SubtitleItem>?)
    data class SubtitleItem(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
