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
    if (epId.isBlank()) return false

    try {
        // Ambil playurl — fnval=0 = DASH
        val url = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=0"
        val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
        val play = resp?.data?.playurl ?: return false

        val videos = play.video ?: emptyList()
        val audios = play.audioResource ?: emptyList()
        val durationSec = (play.duration ?: 0L) / 1000

        // Filter video yang punya URL
        val validVideos = videos
            .filter { !it.videoResource?.url.isNullOrBlank() }
            .sortedByDescending { it.streamInfo?.quality ?: 0 }

        if (validVideos.isEmpty()) return false

        // Ambil 1 audio (yang pertama punya URL)
        val audio = audios.firstOrNull { !it.url.isNullOrBlank() } ?: return false
        val audioUrl = audio.url!!

        // ==== Buat MPD manifest untuk setiap kualitas video ====
        validVideos.forEach { v ->
            val vRes = v.videoResource ?: return@forEach
            val vUrl = vRes.url ?: return@forEach
            val vQual = v.streamInfo?.quality ?: 32
            val vLabel = v.streamInfo?.descWords?.ifBlank { null } ?: "${vQual}p"

            val mpdXml = buildMpd(
                videoUrl = vUrl,
                videoW = vRes.width ?: 852,
                videoH = vRes.height ?: 480,
                videoBandwidth = vRes.bandwidth ?: 245000,
                videoCodecs = vRes.codecs ?: "avc1.64001F",
                audioUrl = audioUrl,
                audioBandwidth = audio.bandwidth ?: 67000,
                audioCodecs = audio.codecs ?: "mp4a.40.2",
                durationSec = durationSec
            )

            val b64 = android.util.Base64.encodeToString(
                mpdXml.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val dataUri = "data:application/dash+xml;base64,$b64"

            callback.invoke(
                newExtractorLink(name, "$name $vLabel", dataUri, ExtractorLinkType.DASH) {
                    this.referer = "$mainUrl/"
                }
            )
        }

    } catch (e: Exception) {
        // Fallback: tetap coba video-only kalau MPD gagal
        try {
            val url = "$apiUrl/intl/gateway/web/playurl" +
                    "?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=0"
            val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
            resp?.data?.playurl?.video
                ?.filter { !it.videoResource?.url.isNullOrBlank() }
                ?.forEach { v ->
                    val vUrl = v.videoResource?.url ?: return@forEach
                    val q = v.streamInfo?.quality ?: 32
                    callback.invoke(
                        newExtractorLink(name, "$name ${q}p", vUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                        }
                    )
                }
        } catch (_: Exception) { }
    }

    // Subtitle
    try {
        val subUrl = "$apiUrl/intl/gateway/web/v2/subtitle?s_locale=id_ID&platform=web&episode_id=$epId"
        app.get(subUrl, headers = apiHeaders)
            .parsedSafe<SubtitleApiResponse>()?.data?.subtitles
            ?.forEach { sub ->
                val u = sub.url?.ifBlank { null } ?: return@forEach
                subtitleCallback.invoke(newSubtitleFile(sub.lan ?: "Unknown", u))
            }
    } catch (_: Exception) { }

    return true
}

// ============================================================
//  BUILD MPD MANIFEST (DASH)
// ============================================================
private fun buildMpd(
    videoUrl: String, videoW: Int, videoH: Int,
    videoBandwidth: Int, videoCodecs: String,
    audioUrl: String, audioBandwidth: Int, audioCodecs: String,
    durationSec: Long
): String {
    val dur = if (durationSec > 0) "PT${durationSec}S" else "PT24M"
    return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="$dur" minBufferTime="PT2S">
  <Period id="0" start="PT0S">
    <AdaptationSet id="1" contentType="video" mimeType="video/mp4" segmentAlignment="true" startWithSAP="1" par="16:9">
      <Representation id="v" bandwidth="$videoBandwidth" width="$videoW" height="$videoH" codecs="$videoCodecs" scanType="progressive">
        <BaseURL>$videoUrl</BaseURL>
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="2" contentType="audio" mimeType="audio/mp4" segmentAlignment="true" startWithSAP="1" lang="und">
      <Representation id="a" bandwidth="$audioBandwidth" codecs="$audioCodecs" audioSamplingRate="44100">
        <BaseURL>$audioUrl</BaseURL>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
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
