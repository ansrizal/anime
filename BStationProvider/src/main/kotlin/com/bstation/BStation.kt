package com.bstation

import android.util.Base64
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
        TvType.Movie, TvType.TvSeries, TvType.Anime,
    )

    private val apiUrl = "https://api.bilibili.tv"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl
    )

    // ============================================================
    //  POSTER CLEANER
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
            val c = src.attr("srcset").substringBefore(",").substringBefore(" ")
            c.cleanImage()?.let { return it }
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

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/id/search-result?q=${query.replace(" ", "%20")}"
        val document = app.get(url).document
        val items = document.select(
            "li.section__list__item, li.scroll-wrap__list__item, div.card-item, div.bstar-video-card"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return items.toNewSearchResponseList()
    }

    // ============================================================
    //  LOAD — API /v2/ogv/play/series (semua episode)
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

        // Extract season_id from URL /play/{season_id} atau /play/{season_id}/{ep_id}
        val seasonMatch = Regex("""/play/(\d+)""").find(url)
            ?: return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = description
            }
        val seasonId = seasonMatch.groupValues[1]

        // ==== API call: GET ALL EPISODES ====
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

        // Fallback: parse dari DOM kalau API gagal
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
            // Single video tanpa episode → Movie
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
    //  LOAD LINKS — DASH manifest atau FLV fallback
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = when {
            data.startsWith("pgc:") -> data.removePrefix("pgc:")
            else -> data
        }
        if (epId.isBlank()) return false

        // Step 1: coba fnval=1 (MP4/FLV muxed) — paling mudah
        var any = false
        try {
            val flvUrl = "$apiUrl/intl/gateway/web/playurl" +
                    "?s_locale=id_ID&platform=web&ep_id=$epId&tk=&qn=64&type=0&device=wap&tf=0&fnval=1"
            val flvResp = app.get(flvUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()

            flvResp?.data?.playurl?.durl?.forEach { d ->
                val u = d.url?.ifBlank { null } ?: return@forEach
                callback.invoke(newExtractorLink(name, "$name (MP4)", u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                })
                any = true
            }
        } catch (_: Exception) { }

        // Step 2: DASH manifest
        if (!any) {
            try {
                val dashUrl = "$apiUrl/intl/gateway/web/playurl" +
                        "?s_locale=id_ID&platform=web&ep_id=$epId&tk=&qn=64&type=0&device=wap&tf=0"
                val resp = app.get(dashUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
                val playData = resp?.data?.playurl

                if (playData != null) {
                    val videos = playData.video ?: emptyList()
                    val audios = playData.audioResource ?: emptyList()
                    val durationSec = (playData.duration ?: 0L) / 1000

                    videos.forEach { vItem ->
                        val vRes = vItem.videoResource ?: return@forEach
                        val vUrl = vRes.url?.ifBlank { null } ?: return@forEach
                        val vQual = vItem.streamInfo?.quality ?: vRes.quality ?: 0
                        val vLabel = vItem.streamInfo?.descWords?.ifBlank { null } ?: "${vQual}p"

                        // Match audio by audio_quality
                        val audioItem = audios.firstOrNull { it.quality == vItem.audioQuality }
                            ?: audios.firstOrNull()
                        val audioUrl = audioItem?.url?.ifBlank { null }

                        if (audioUrl == null) {
                            // Video-only (mungkin tanpa suara)
                            callback.invoke(newExtractorLink(name, "$name $vLabel", vUrl, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                                this.quality = qualityFromBili(vQual)
                            })
                        } else {
                            // Build DASH manifest (video + audio)
                            val mpd = buildDashManifest(
                                videoUrl = vUrl,
                                vW = vRes.width ?: 1280,
                                vH = vRes.height ?: 720,
                                vBw = vRes.bandwidth ?: 0,
                                vCodecs = vRes.codecs ?: "avc1.64001E",
                                vInitRange = vRes.segmentBase?.range ?: "",
                                vIndexRange = vRes.segmentBase?.indexRange ?: "",
                                audioUrl = audioUrl,
                                aBw = audioItem?.bandwidth ?: 0,
                                aCodecs = audioItem?.codecs ?: "mp4a.40.2",
                                aInitRange = audioItem?.segmentBase?.range ?: "",
                                aIndexRange = audioItem?.segmentBase?.indexRange ?: "",
                                durationSec = durationSec
                            )
                            val b64 = Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP)
                            val dataUri = "data:application/dash+xml;base64,$b64"

                            callback.invoke(newExtractorLink(name, "$name $vLabel", dataUri, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                                this.quality = qualityFromBili(vQual)
                            })
                        }
                        any = true
                    }
                }
            } catch (_: Exception) { }
        }

        // Step 3: Subtitle
        try {
            val subUrl = "$apiUrl/intl/gateway/web/v2/subtitle" +
                    "?s_locale=id_ID&platform=web&episode_id=$epId"
            app.get(subUrl, headers = apiHeaders)
                .parsedSafe<SubtitleApiResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val u = sub.url?.ifBlank { null } ?: return@forEach
                    subtitleCallback.invoke(newSubtitleFile(sub.lan ?: "Unknown", u))
                }
        } catch (_: Exception) { }

        return any
    }

    private fun qualityFromBili(q: Int) = when (q) {
        112, 80 -> Qualities.P1080.value
        64 -> Qualities.P720.value
        32 -> Qualities.P480.value
        16 -> Qualities.P360.value
        6 -> Qualities.P240.value
        5 -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    // ============================================================
    //  BUILD DASH MANIFEST (data URI)
    // ============================================================
    private fun buildDashManifest(
        videoUrl: String, vW: Int, vH: Int, vBw: Int, vCodecs: String,
        vInitRange: String, vIndexRange: String,
        audioUrl: String, aBw: Int, aCodecs: String,
        aInitRange: String, aIndexRange: String,
        durationSec: Long
    ): String {
        val dur = if (durationSec > 0) "PT${durationSec}S" else "PT1H"
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="$dur" minBufferTime="PT2S">
  <Period start="PT0S">
    <AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true" startWithSAP="1">
      <Representation id="v" bandwidth="$vBw" width="$vW" height="$vH" codecs="$vCodecs" scanType="progressive">
        <BaseURL>$videoUrl</BaseURL>
        <SegmentBase indexRange="$vIndexRange">
          <Initialization range="$vInitRange"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
    <AdaptationSet contentType="audio" mimeType="audio/mp4" segmentAlignment="true" startWithSAP="1">
      <Representation id="a" bandwidth="$aBw" codecs="$aCodecs">
        <BaseURL>$audioUrl</BaseURL>
        <SegmentBase indexRange="$aIndexRange">
          <Initialization range="$aInitRange"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
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
