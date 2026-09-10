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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "https://api.bilibili.tv"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl
    )

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
            ?: a.text().trim().ifBlank { null } ?: return null
        if (title.length < 2) return null

        val poster = a.extractPoster() ?: this.extractPoster()
        val type = if (fullUrl.contains("/play/")) TvType.Anime else TvType.Movie

        return newAnimeSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
    }

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

        if (items.isNotEmpty()) home.add(HomePageList(request.name, items))
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
    //  LOAD — Pakai API, dapat SEMUA episode
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

        // ==== API: GET ALL EPISODES ====
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
        } catch (_: Exception) { }

        // Fallback: parse DOM
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
    //  LOAD LINKS — Auto probe multiple servers & formats
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = if (data.startsWith("pgc:")) data.removePrefix("pgc:") else data
        if (epId.isBlank()) return false

        var any = false

        // ---------- SERVER 1: FLV/MP4 muxed (fnval=1) ----------
        // Coba beberapa qn — kalau muxed MP4 tersedia, ini paling reliable
        for (qn in listOf("80", "64", "32", "16")) {
            if (any) break
            try {
                val url = "$apiUrl/intl/gateway/web/playurl" +
                        "?s_locale=id_ID&platform=web&ep_id=$epId&tk=" +
                        "&qn=$qn&type=0&device=wap&tf=0&fnval=1&fourk=1&high_quality=1"
                val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
                val durl = resp?.data?.playurl?.durl
                if (!durl.isNullOrEmpty()) {
                    durl.forEach { d ->
                        val u = d.url?.ifBlank { null } ?: return@forEach
                        callback.invoke(
                            newExtractorLink(name, "$name ${qn}p (MP4)", u, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                                this.quality = qualityFromBili(qn.toInt())
                            }
                        )
                        any = true
                    }
                }
            } catch (_: Exception) { }
        }

        // ---------- SERVER 2: DASH (MPD data URI) ----------
        if (!any) {
            try {
                val dashUrl = "$apiUrl/intl/gateway/web/playurl" +
                        "?s_locale=id_ID&platform=web&ep_id=$epId&tk=" +
                        "&qn=80&type=0&device=wap&tf=0&fourk=1&high_quality=1"
                val resp = app.get(dashUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
                val playData = resp?.data?.playurl

                if (playData != null) {
                    val videos = playData.video ?: emptyList()
                    val audios = playData.audioResource ?: emptyList()
                    val durationSec = (playData.duration ?: 0L) / 1000

                    // Sort video by quality desc, prioritize yang punya URL
                    val validVideos = videos
                        .filter { !it.videoResource?.url.isNullOrBlank() }
                        .sortedByDescending { it.streamInfo?.quality ?: 0 }

                    validVideos.forEach { vItem ->
                        val vRes = vItem.videoResource ?: return@forEach
                        val vUrl = vRes.url ?: return@forEach
                        val vQual = vItem.streamInfo?.quality ?: vRes.quality ?: 0
                        val vLabel = vItem.streamInfo?.descWords?.ifBlank { null } ?: "${vQual}p"

                        // Cari audio dengan quality matching
                        val audioItem = audios.firstOrNull { it.quality == vItem.audioQuality }
                            ?: audios.firstOrNull { !it.url.isNullOrBlank() }
                        val audioUrl = audioItem?.url

                        if (audioUrl.isNullOrBlank()) {
                            // Fallback: video-only
                            callback.invoke(
                                newExtractorLink(name, "$name $vLabel (no audio)", vUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = "$mainUrl/"
                                    this.quality = qualityFromBili(vQual)
                                }
                            )
                        } else {
                            // Build MPD dengan audio + video
                            val mpd = buildMpd(
                                vUrl, vRes.width ?: 1280, vRes.height ?: 720,
                                vRes.bandwidth ?: 1_000_000, vRes.codecs ?: "avc1.64001E",
                                vRes.segmentBase?.range ?: "", vRes.segmentBase?.indexRange ?: "",
                                audioUrl, audioItem.bandwidth ?: 128_000,
                                audioItem.codecs ?: "mp4a.40.2",
                                audioItem.segmentBase?.range ?: "", audioItem.segmentBase?.indexRange ?: "",
                                durationSec
                            )
                            val dataUri = "data:application/dash+xml;base64," +
                                    Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP)
                            callback.invoke(
                                newExtractorLink(name, "$name $vLabel (DASH)", dataUri, ExtractorLinkType.VIDEO) {
                                    this.referer = "$mainUrl/"
                                    this.quality = qualityFromBili(vQual)
                                }
                            )
                        }
                        any = true
                    }
                }
            } catch (_: Exception) { }
        }

        // ---------- SERVER 3: Alternative platform=android ----------
        if (!any) {
            try {
                val url = "$apiUrl/intl/gateway/web/playurl" +
                        "?s_locale=id_ID&platform=android&ep_id=$epId&qn=32&fnval=1&device=android"
                val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
                resp?.data?.playurl?.durl?.forEach { d ->
                    val u = d.url?.ifBlank { null } ?: return@forEach
                    callback.invoke(
                        newExtractorLink(name, "$name (android)", u, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P480.value
                        }
                    )
                    any = true
                }
            } catch (_: Exception) { }
        }

        // ---------- SERVER 4: Domain utama (fallback) ----------
        if (!any) {
            try {
                val url = "$mainUrl/intl/gateway/web/playurl" +
                        "?s_locale=id_ID&platform=web&ep_id=$epId&qn=32&fnval=1&device=wap"
                val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
                resp?.data?.playurl?.durl?.forEach { d ->
                    val u = d.url?.ifBlank { null } ?: return@forEach
                    callback.invoke(
                        newExtractorLink(name, "$name (alt)", u, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    any = true
                }
            } catch (_: Exception) { }
        }

        // ---------- Subtitle ----------
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
    //  BUILD DASH MPD MANIFEST
    // ============================================================
    private fun buildMpd(
        vUrl: String, vW: Int, vH: Int, vBw: Int, vCodecs: String,
        vInit: String, vIndex: String,
        aUrl: String, aBw: Int, aCodecs: String,
        aInit: String, aIndex: String,
        durationSec: Long
    ): String {
        val dur = if (durationSec > 0) "PT${durationSec}S" else "PT24M"
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="$dur" minBufferTime="PT2S">
  <Period id="0" start="PT0S">
    <AdaptationSet id="1" contentType="video" mimeType="video/mp4" segmentAlignment="true" startWithSAP="1" par="16:9">
      <Representation id="v" bandwidth="$vBw" width="$vW" height="$vH" codecs="$vCodecs" scanType="progressive">
        <BaseURL>$vUrl</BaseURL>
        <SegmentBase indexRange="$vIndex" timescale="1000">
          <Initialization range="$vInit"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="2" contentType="audio" mimeType="audio/mp4" segmentAlignment="true" startWithSAP="1" lang="und">
      <Representation id="a" bandwidth="$aBw" codecs="$aCodecs" audioSamplingRate="44100">
        <BaseURL>$aUrl</BaseURL>
        <SegmentBase indexRange="$aIndex" timescale="1000">
          <Initialization range="$aInit"/>
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
    data class SeriesApiData(@JsonProperty("sectionsList") val sections: List<SeriesApiSection>?)
    data class SeriesApiSection(
        @JsonProperty("title") val title: String?,
        @JsonProperty("episodes") val episodes: List<SeriesApiEpisode>?
    )
    data class SeriesApiEpisode(
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("episode_id") val episodeId: Long?,
        @JsonProperty("short_title_display") val shortTitleDisplay: String?,
        @JsonProperty("long_title_display") val longTitleDisplay: String?,
        @JsonProperty("title_display") val titleDisplay: String?
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
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("bandwidth") val bandwidth: Int?,
        @JsonProperty("codecs") val codecs: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?,
        @JsonProperty("segment_base") val segmentBase: SegmentBase?,
        @JsonProperty("width") val width: Int?,
        @JsonProperty("height") val height: Int?
    )
    data class SegmentBase(
        @JsonProperty("range") val range: String?,
        @JsonProperty("index_range") val indexRange: String?
    )
    data class StreamInfo(
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("desc_words") val descWords: String?
    )
    data class AudioResource(
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("bandwidth") val bandwidth: Int?,
        @JsonProperty("codecs") val codecs: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?,
        @JsonProperty("segment_base") val segmentBase: SegmentBase?
    )

    data class SubtitleApiResponse(@JsonProperty("data") val data: SubtitleApiData?)
    data class SubtitleApiData(@JsonProperty("subtitles") val subtitles: List<SubtitleItem>?)
    data class SubtitleItem(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
