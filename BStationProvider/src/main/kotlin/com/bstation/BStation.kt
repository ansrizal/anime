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
            src.attr("srcset").substringBefore(",").substringBefore(" ").cleanImage()?.let { return it }
        }
        return null
    }

    // ============================================================
    //  XML ESCAPE — WAJIB untuk MPD
    // ============================================================
    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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
    //  LOAD
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

        // ==== UGC video ====
        if (url.contains("/video/")) {
            val aidMatch = Regex("""/video/(\d+)""").find(url)
            val aid = aidMatch?.groupValues?.get(1) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, "ugc:$aid") {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ==== PGC series ====
        val seasonMatch = Regex("""/play/(\d+)""").find(url) ?: return null
        val seasonId = seasonMatch.groupValues[1]

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

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ============================================================
    //  LOAD LINKS
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            data.startsWith("pgc:") -> loadPgc(data.removePrefix("pgc:"), callback, subtitleCallback)
            data.startsWith("ugc:") -> loadUgc(data.removePrefix("ugc:"), callback, subtitleCallback)
            else -> false
        }
    }

    // ============================================================
    //  PGC
    // ============================================================
    private suspend fun loadPgc(
        epId: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var any = false

        try {
            val url = "$apiUrl/intl/gateway/web/playurl" +
                    "?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=0"
            val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
            val play = resp?.data?.playurl

            val videos = play?.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
            val audios = play?.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
            val durationSec = (play?.duration ?: 0L) / 1000
            val audio = audios.firstOrNull()

            // Fallback MP4 langsung jika video list kosong
            if (videos.isEmpty() && play?.durl?.isNotEmpty() == true) {
                play.durl.forEach { d ->
                    d.url?.let { directUrl ->
                        callback.invoke(
                            newExtractorLink(name, name, directUrl, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                            }
                        )
                        any = true
                    }
                }
            }

            videos.sortedByDescending { it.streamInfo?.quality ?: 0 }.forEach { v ->
                val vRes = v.videoResource ?: return@forEach
                val vUrlRaw = vRes.url ?: return@forEach
                val vQual = v.streamInfo?.quality ?: 32
                val vLabel = v.streamInfo?.descWords?.ifBlank { null } ?: "${vQual}p"

                // ==== MPD manifest ====
                if (audio != null && !audio.url.isNullOrBlank()) {
                    try {
                        val mpd = buildMpd(
                            vUrl = vUrlRaw,
                            vW = vRes.width ?: 852,
                            vH = vRes.height ?: 480,
                            vBw = vRes.bandwidth ?: 245000,
                            vCodecs = vRes.codecs ?: "avc1.64001F",
                            vInitRange = vRes.segmentBase?.range ?: "",
                            vIndexRange = vRes.segmentBase?.indexRange ?: "",
                            aUrl = audio.url!!,
                            aBw = audio.bandwidth ?: 67000,
                            aCodecs = audio.codecs ?: "mp4a.40.2",
                            aInitRange = audio.segmentBase?.range ?: "",
                            aIndexRange = audio.segmentBase?.indexRange ?: "",
                            durSec = durationSec
                        )
                        val b64 = Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val dataUri = "data:application/dash+xml;base64,$b64"

                        callback.invoke(
                            newExtractorLink(name, "$name $vLabel", dataUri, ExtractorLinkType.DASH) {
                                this.referer = "$mainUrl/"
                            }
                        )
                        any = true
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }

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

        return any
    }

    // ============================================================
    //  UGC  (FIXED: cid opsional, param sesuai browser)
    // ============================================================
    private suspend fun loadUgc(
        aid: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var any = false

        // 1. Coba dapatkan cid (OPSIONAL — untuk video multi-part)
        var cid: String? = null
        try {
            val viewUrl = "$apiUrl/intl/gateway/web/view?aid=$aid&platform=web&s_locale=id_ID"
            val viewResp = app.get(viewUrl, headers = apiHeaders)
            val viewParsed = viewResp.parsedSafe<ViewApiResponse>()
            cid = viewParsed?.data?.pages?.firstOrNull()?.cid?.toString()
                ?: viewParsed?.data?.cid?.toString()
                ?: Regex(""""cid":(\d+)""").find(viewResp.text)?.groupValues?.get(1)
        } catch (_: Exception) { }

        // 2. Panggil playurl — JIKA cid null, TETAP coba dengan aid saja
        try {
            val urlBuilder = StringBuilder("$apiUrl/intl/gateway/web/playurl")
                .append("?s_locale=id_ID")
                .append("&platform=web")
                .append("&aid=$aid")           // ← pakai aid (bukan avid)
                .append("&qn=64")
                .append("&type=0")             // ← sesuai request browser
                .append("&device=wap")         // ← sesuai request browser
                .append("&tf=0")               // ← sesuai request browser
                .append("&fnval=0")

            // cid hanya ditambahkan jika berhasil didapat
            if (cid != null) {
                urlBuilder.append("&cid=$cid")
            }

            val resp = app.get(urlBuilder.toString(), headers = apiHeaders)
                .parsedSafe<PlayUrlResponse>()
            val play = resp?.data?.playurl

            val videos = play?.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
            val audios = play?.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
            val durationSec = (play?.duration ?: 0L) / 1000
            val audio = audios.firstOrNull()

            // Fallback 1: MP4 langsung via durl
            if (videos.isEmpty() && play?.durl?.isNotEmpty() == true) {
                play.durl.forEach { d ->
                    d.url?.let { directUrl ->
                        callback.invoke(
                            newExtractorLink(name, name, directUrl, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                            }
                        )
                        any = true
                    }
                }
            }

            // Proses video + audio menjadi MPD
            videos.sortedByDescending { it.streamInfo?.quality ?: 0 }.forEach { v ->
                val vRes = v.videoResource ?: return@forEach
                val vUrlRaw = vRes.url ?: return@forEach
                val vQual = v.streamInfo?.quality ?: 32
                val vLabel = v.streamInfo?.descWords?.ifBlank { null } ?: "${vQual}p"

                // Fallback 2: Kalau tidak ada audio resource, pakai video URL langsung
                if (audio == null || audio.url.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(name, "$name $vLabel", vUrlRaw, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    any = true
                    return@forEach
                }

                // Utama: gabung video + audio jadi MPD DASH
                try {
                    val mpd = buildMpd(
                        vUrl = vUrlRaw,
                        vW = vRes.width ?: 852,
                        vH = vRes.height ?: 480,
                        vBw = vRes.bandwidth ?: 245000,
                        vCodecs = vRes.codecs ?: "avc1.64001F",
                        vInitRange = vRes.segmentBase?.range ?: "",
                        vIndexRange = vRes.segmentBase?.indexRange ?: "",
                        aUrl = audio.url!!,
                        aBw = audio.bandwidth ?: 67000,
                        aCodecs = audio.codecs ?: "mp4a.40.2",
                        aInitRange = audio.segmentBase?.range ?: "",
                        aIndexRange = audio.segmentBase?.indexRange ?: "",
                        durSec = durationSec
                    )
                    val b64 = Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val dataUri = "data:application/dash+xml;base64,$b64"

                    callback.invoke(
                        newExtractorLink(name, "$name $vLabel", dataUri, ExtractorLinkType.DASH) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    any = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Subtitle (endpoint UGC pakai aid, bukan episode_id)
        try {
            val subUrl = "$apiUrl/intl/gateway/web/v2/subtitle?s_locale=id_ID&platform=web&aid=$aid" +
                    (cid?.let { "&cid=$it" } ?: "")
            app.get(subUrl, headers = apiHeaders)
                .parsedSafe<SubtitleApiResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val u = sub.url?.ifBlank { null } ?: return@forEach
                    subtitleCallback.invoke(newSubtitleFile(sub.lan ?: "Unknown", u))
                }
        } catch (_: Exception) { }

        return any
    }

    // ============================================================
    //  BUILD MPD — dengan XML escape WAJIB
    // ============================================================
    private fun buildMpd(
        vUrl: String, vW: Int, vH: Int, vBw: Int, vCodecs: String,
        vInitRange: String, vIndexRange: String,
        aUrl: String, aBw: Int, aCodecs: String,
        aInitRange: String, aIndexRange: String,
        durSec: Long
    ): String {
        val dur = if (durSec > 0) "PT${durSec}S" else "PT24M"

        // ESCAPE URL — WAJIB karena XML tidak boleh ada "&" mentah
        val vUrlEsc = escapeXml(vUrl)
        val aUrlEsc = escapeXml(aUrl)

        val videoSegment = if (vInitRange.isNotBlank() && vIndexRange.isNotBlank()) {
            "<SegmentBase indexRange=\"${escapeXml(vIndexRange)}\" timescale=\"1000\">" +
                    "<Initialization range=\"${escapeXml(vInitRange)}\"/>" +
                    "</SegmentBase>"
        } else ""

        val audioSegment = if (aInitRange.isNotBlank() && aIndexRange.isNotBlank()) {
            "<SegmentBase indexRange=\"${escapeXml(aIndexRange)}\" timescale=\"1000\">" +
                    "<Initialization range=\"${escapeXml(aInitRange)}\"/>" +
                    "</SegmentBase>"
        } else ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="$dur" minBufferTime="PT2S">
  <Period id="0" start="PT0S">
    <AdaptationSet id="1" contentType="video" mimeType="video/mp4" segmentAlignment="true" startWithSAP="1">
      <Representation id="v" bandwidth="$vBw" width="$vW" height="$vH" codecs="${escapeXml(vCodecs)}" scanType="progressive">
        <BaseURL>$vUrlEsc</BaseURL>$videoSegment
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="2" contentType="audio" mimeType="audio/mp4" segmentAlignment="true" startWithSAP="1" lang="und">
      <Representation id="a" bandwidth="$aBw" codecs="${escapeXml(aCodecs)}" audioSamplingRate="44100">
        <BaseURL>$aUrlEsc</BaseURL>$audioSegment
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

    data class ViewApiResponse(@JsonProperty("data") val data: ViewApiData?)
    data class ViewApiData(
        @JsonProperty("aid") val aid: Long?,
        @JsonProperty("cid") val cid: Long?,
        @JsonProperty("pages") val pages: List<ViewPage>?
    )
    data class ViewPage(@JsonProperty("cid") val cid: Long?)

    data class PlayUrlResponse(@JsonProperty("data") val data: PlayUrlData?)
    data class PlayUrlData(@JsonProperty("playurl") val playurl: PlayurlData?)
    data class PlayurlData(
        @JsonProperty("quality") val quality: Int?,
        @JsonProperty("duration") val duration: Long?,
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
        @JsonProperty("segment_base") val segmentBase: SegmentBase?
    )

    data class SubtitleApiResponse(@JsonProperty("data") val data: SubtitleApiData?)
    data class SubtitleApiData(@JsonProperty("subtitles") val subtitles: List<SubtitleItem>?)
    data class SubtitleItem(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
