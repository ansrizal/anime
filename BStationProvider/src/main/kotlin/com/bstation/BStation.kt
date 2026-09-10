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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "https://api.bilibili.tv"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl
    )

    // ============================================================
    //  POSTER CLEANER — strip @720w_405h AND ?x-oss-process=...
    // ============================================================
    private fun String?.cleanImage(): String? {
        if (this.isNullOrBlank() || this.startsWith("data:")) return null
        var u = this.substringBefore("@").substringBefore("?").substringBefore(" ").trim()
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
            src.attr("srcset").substringBefore(",").substringBefore(" ").cleanImage()?.let { return it }
        }
        return null
    }

    // ============================================================
    //  CARD PARSER
    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val a = if (this.tagName() == "a") this
        else this.selectFirst("a[href*=/play/], a[href*=/video/]") ?: return null

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

    // ============================================================
    //  MAIN PAGE
    // ============================================================
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
            "li.section__list__item, li.scroll-wrap__list__item, div.card-item, div.bstar-video-card"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        if (items.isNotEmpty()) {
            home.add(HomePageList(request.name, items))
        }
        return newHomePageResponse(home, false)
    }

    // ============================================================
    //  SEARCH
    // ============================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/id/search-result?q=${query.replace(" ", "%20")}"
        val document = app.get(url).document
        val items = document.select(
            "li.section__list__item, li.scroll-wrap__list__item, div.card-item, div.bstar-video-card"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return items.toNewSearchResponseList()
    }

    // ============================================================
    //  LOAD — API /v2/ogv/play/series (SEMUA episode, bisa 1000+)
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" HD")?.substringBefore(" | ")?.ifBlank { null }
            ?: document.selectFirst(".bstar-meta__title a")?.text()?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content").cleanImage()

        val description = document.selectFirst(".bstar-meta__desc")?.text()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        val seasonMatch = Regex("""/play/(\d+)""").find(url)
            ?: return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = description
            }
        val seasonId = seasonMatch.groupValues[1]

        // ==== API: GET ALL EPISODES (support 1000+ episode) ====
        val episodes = mutableListOf<Episode>()
        try {
            val apiSeriesUrl = "$apiUrl/intl/gateway/web/v2/ogv/play/series" +
                    "?s_locale=id_ID&platform=web&season_id=$seasonId"
            val resp = app.get(apiSeriesUrl, headers = apiHeaders)
                .parsedSafe<SeriesApiResponse>()

            resp?.data?.sections?.forEach { section ->
                section.episodes?.forEach { ep ->
                    val id = ep.episodeId ?: return@forEach
                    val displayTitle = ep.titleDisplay
                        ?: ep.longTitleDisplay
                        ?: ep.shortTitleDisplay
                        ?: "Episode"
                    val epNum = Regex("""E(\d+)""").find(displayTitle)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode("pgc:$id") {
                        this.name = displayTitle
                        this.episode = epNum
                        this.posterUrl = ep.cover.cleanImage() ?: poster
                    })
                }
            }
        } catch (_: Exception) { /* fallback ke DOM */ }

        // Fallback: parse DOM kalau API gagal (hanya dapat 30)
        if (episodes.isEmpty()) {
            document.select("a.ep-item").forEach { el ->
                val href = el.attr("href").substringBefore("?")
                val m = Regex("""/play/\d+/(\d+)""").find(href) ?: return@forEach
                val epId = m.groupValues[1]
                val shortTitle = el.text().trim()
                val longTitle = el.attr("title")
                val epNum = Regex("""E(\d+)""").find(shortTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode("pgc:$epId") {
                    this.name = if (longTitle.isNotBlank()) "$shortTitle - $longTitle" else shortTitle
                    this.episode = epNum
                })
            }
        }

        if (episodes.isEmpty()) {
            val aidMatch = Regex("""/video/(\d+)""").find(url)
            val data = if (aidMatch != null) "ugc:${aidMatch.groupValues[1]}" else url
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster; this.plot = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ============================================================
    //  LOAD LINKS — Simple robust multi-fallback
    // ============================================================
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val epId = if (data.startsWith("pgc:")) data.removePrefix("pgc:") else data

    // ==== Link 1: tampilkan data yang diterima ====
    callback.invoke(
        newExtractorLink(name, "INFO data='$data' ep='$epId'", "https://example.com/", ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
        }
    )

    if (epId.isBlank()) return true

    try {
        val url = "$apiUrl/intl/gateway/web/playurl?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=1"
        val resp = app.get(url, headers = apiHeaders)
        val text = resp.text

        // ==== Link 2: tampilkan 60 char pertama response ====
        callback.invoke(
            newExtractorLink(name, "RESP[${text.length}]: ${text.take(60).replace("\n", " ")}",
                "https://example.com/", ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
            }
        )

        // Coba parse
        val parsed = resp.parsedSafe<PlayUrlResponse>()
        val play = parsed?.data?.playurl

        val durlCount = play?.durl?.size ?: -1
        val videoCount = play?.video?.size ?: -1

        // ==== Link 3: tampilkan hasil parsing ====
        callback.invoke(
            newExtractorLink(name, "PARSE durl=$durlCount video=$videoCount",
                "https://example.com/", ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
            }
        )

        // ==== Kalau ada durl, kirim sebagai link asli ====
        play?.durl?.forEach { d ->
            val link = d.url
            if (!link.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(name, "$name MP4", link, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                    }
                )
            }
        }

        // ==== Kalau ada video, kirim semua ====
        play?.video
            ?.filter { !it.videoResource?.url.isNullOrBlank() }
            ?.forEach { v ->
                val vUrl = v.videoResource?.url ?: return@forEach
                val q = v.streamInfo?.quality ?: 32
                val label = v.streamInfo?.descWords?.ifBlank { null } ?: "${q}p"
                callback.invoke(
                    newExtractorLink(name, "$name $label", vUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                    }
                )
            }

        return true
    } catch (e: Exception) {
        // ==== Link error kalau API gagal ====
        callback.invoke(
            newExtractorLink(name, "EXC: ${e.message?.take(80)}", "https://example.com/", ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
            }
        )
        return true
    }
}
    // ============================================================
    //  Quality mapper
    // ============================================================
    private fun qualityFromBili(q: Int) = when (q) {
        112, 80 -> Qualities.P1080.value
        64 -> Qualities.P720.value
        32 -> Qualities.P480.value
        16 -> Qualities.P360.value
        6 -> Qualities.P240.value
        5 -> Qualities.P144.value
        else -> Qualities.P480.value
    }

    // ============================================================
    //  DATA CLASSES
    // ============================================================
    data class SeriesApiResponse(@JsonProperty("data") val data: SeriesApiData?)
    data class SeriesApiData(
        @JsonProperty("sectionsList") val sections: List<SeriesApiSection>?
    )
    data class SeriesApiSection(
        @JsonProperty("title") val title: String?,
        @JsonProperty("ep_list_title") val epListTitle: String?,
        @JsonProperty("episodes") val episodes: List<SeriesApiEpisode>?
    )
    data class SeriesApiEpisode(
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("episode_id") val episodeId: Long?,
        @JsonProperty("short_title_display") val shortTitleDisplay: String?,
        @JsonProperty("long_title_display") val longTitleDisplay: String?,
        @JsonProperty("title_display") val titleDisplay: String?,
        @JsonProperty("publish_time") val publishTime: String?
    )

    data class PlayUrlResponse(@JsonProperty("data") val data: PlayUrlData?)
    data class PlayUrlData(@JsonProperty("playurl") val playurl: PlayurlData?)
    data class PlayurlData(
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("duration") val duration: Long?,
        @JsonProperty("expire_at") val expireAt: Long?,
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
        @JsonProperty("stream_info") val streamInfo: StreamInfo?,
        @JsonProperty("audio_quality") val audioQuality: Int?
    )
    data class VideoResource(
        @JsonProperty("id") val id: String?,
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("bandwidth") val bandwidth: Int?,
        @JsonProperty("codecs") val codecs: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?,
        @JsonProperty("segment_base") val segmentBase: SegmentBase?,
        @JsonProperty("width") val width: Int?,
        @JsonProperty("height") val height: Int?,
        @JsonProperty("mime_type") val mimeType: String?
    )
    data class SegmentBase(
        @JsonProperty("range") val range: String?,
        @JsonProperty("index_range") val indexRange: String?
    )
    data class StreamInfo(
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("desc_text") val descText: String?,
        @JsonProperty("desc_words") val descWords: String?
    )
    data class AudioResource(
        @JsonProperty("id") val id: String?,
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("bandwidth") val bandwidth: Int?,
        @JsonProperty("codecs") val codecs: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?,
        @JsonProperty("segment_base") val segmentBase: SegmentBase?,
        @JsonProperty("mime_type") val mimeType: String?
    )

    data class SubtitleApiResponse(@JsonProperty("data") val data: SubtitleApiData?)
    data class SubtitleApiData(@JsonProperty("subtitles") val subtitles: List<SubtitleItem>?)
    data class SubtitleItem(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
