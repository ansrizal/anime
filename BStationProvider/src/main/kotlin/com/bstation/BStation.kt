package com.bstation

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.io.File

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

    private val TAG = "BStation"

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
    //  WRITE MPD KE CACHE FILE  (SOLUSI CRONET!)
    // ============================================================
    /**
     * Menulis MPD ke file lokal di cache CloudStream.
     * Mengembalikan file:// URL yang bisa dibaca oleh FileDataSource.
     * Ini menghindari masalah Cronet yang tidak mendukung skema "data:".
     */
    private fun writeMpdToCache(mpd: String): String? {
        return try {
            val cacheDir = File(app.cacheDir, "bstation_mpd")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            // Bersihkan file lama (lebih dari 1 jam)
            try {
                val now = System.currentTimeMillis()
                cacheDir.listFiles()?.forEach { f ->
                    if (now - f.lastModified() > 60L * 60L * 1000L) f.delete()
                }
            } catch (_: Exception) { }

            val fileName = "bs_${System.currentTimeMillis()}_${mpd.hashCode()}.mpd"
            val file = File(cacheDir, fileName)
            file.writeText(mpd, Charsets.UTF_8)

            println("$TAG: MPD written to ${file.absolutePath} (${mpd.length} chars)")
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            println("$TAG: Gagal tulis MPD ke cache: ${e.message}")
            e.printStackTrace()
            null
        }
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
            println("$TAG: [PGC-LOAD] Fetch series -> $apiSeriesUrl")
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
            println("$TAG: [PGC-LOAD] Total episodes dari API: ${episodes.size}")
        } catch (e: Exception) {
            println("$TAG: [PGC-LOAD] Error fetch series: ${e.message}")
        }

        if (episodes.isEmpty()) {
            println("$TAG: [PGC-LOAD] Fallback scraping HTML untuk episodes")
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
            println("$TAG: [PGC-LOAD] Total episodes dari HTML: ${episodes.size}")
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
    //  PGC  (FIXED: file:// URL + debug logging)
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
            println("$TAG: [PGC] Fetch playurl -> $url")

            val resp = app.get(url, headers = apiHeaders).parsedSafe<PlayUrlResponse>()

            if (resp == null) {
                println("$TAG: [PGC] ERROR - parsedSafe returned null")
                return false
            }
            if (resp.data == null) {
                println("$TAG: [PGC] ERROR - resp.data null")
                return false
            }
            if (resp.data.playurl == null) {
                println("$TAG: [PGC] ERROR - playurl null")
                return false
            }

            val play = resp.data.playurl
            val videos = play.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
            val audios = play.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
            val durationSec = (play.duration ?: 0L) / 1000
            val audio = audios.firstOrNull()

            println("$TAG: [PGC] videos=${videos.size}, audios=${audios.size}, duration=$durationSec")

            // Fallback 1: durl (MP4 langsung)
            if (videos.isEmpty() && play.durl?.isNotEmpty() == true) {
                println("$TAG: [PGC] Menggunakan fallback durl")
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

                // Fallback 2: video-only (tanpa audio)
                if (audio == null || audio.url.isNullOrBlank()) {
                    println("$TAG: [PGC] Tidak ada audio, pakai video langsung: $vLabel")
                    callback.invoke(
                        newExtractorLink(name, "$name $vLabel", vUrlRaw, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    any = true
                    return@forEach
                }

                // Utama: gabung video + audio jadi MPD DASH, tulis ke file
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

                    val fileUrl = writeMpdToCache(mpd)
                    if (fileUrl != null) {
                        println("$TAG: [PGC] Sukses buat MPD file: $vLabel")
                        callback.invoke(
                            newExtractorLink(name, "$name $vLabel", fileUrl, ExtractorLinkType.DASH) {
                                this.referer = "$mainUrl/"
                                this.isLocal = true
                            }
                        )
                        any = true
                    } else {
                        println("$TAG: [PGC] Gagal tulis MPD ke cache, fallback ke video-only")
                        callback.invoke(
                            newExtractorLink(name, "$name $vLabel", vUrlRaw, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                            }
                        )
                        any = true
                    }
                } catch (e: Exception) {
                    println("$TAG: [PGC] Exception buildMpd: ${e.message}")
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            println("$TAG: [PGC] Exception di loadPgc: ${e.message}")
            e.printStackTrace()
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

        println("$TAG: [PGC] Selesai, any=$any")
        return any
    }

    // ============================================================
    //  UGC  (FIXED: file:// URL + debug logging)
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
            println("$TAG: [UGC] cid=$cid")
        } catch (e: Exception) {
            println("$TAG: [UGC] Gagal ambil cid: ${e.message}")
        }

        // 2. Panggil playurl
        try {
            val urlBuilder = StringBuilder("$apiUrl/intl/gateway/web/playurl")
                .append("?s_locale=id_ID")
                .append("&platform=web")
                .append("&aid=$aid")
                .append("&qn=64")
                .append("&type=0")
                .append("&device=wap")
                .append("&tf=0")
                .append("&fnval=0")

            if (cid != null) {
                urlBuilder.append("&cid=$cid")
            }

            val finalUrl = urlBuilder.toString()
            println("$TAG: [UGC] Fetch playurl -> $finalUrl")

            val resp = app.get(finalUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()

            if (resp == null || resp.data == null || resp.data.playurl == null) {
                println("$TAG: [UGC] ERROR - resp null / data null / playurl null")
                return false
            }

            val play = resp.data.playurl
            val videos = play.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
            val audios = play.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
            val durationSec = (play.duration ?: 0L) / 1000
            val audio = audios.firstOrNull()

            println("$TAG: [UGC] videos=${videos.size}, audios=${audios.size}, duration=$durationSec")

            // Fallback 1: durl
            if (videos.isEmpty() && play.durl?.isNotEmpty() == true) {
                println("$TAG: [UGC] Fallback ke durl")
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

                // Fallback 2: video-only
                if (audio == null || audio.url.isNullOrBlank()) {
                    println("$TAG: [UGC] Video-only: $vLabel")
                    callback.invoke(
                        newExtractorLink(name, "$name $vLabel", vUrlRaw, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    any = true
                    return@forEach
                }

                // Utama: MPD DASH ke file
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

                    val fileUrl = writeMpdToCache(mpd)
                    if (fileUrl != null) {
                        println("$TAG: [UGC] Sukses buat MPD file: $vLabel")
                        callback.invoke(
                            newExtractorLink(name, "$name $vLabel", fileUrl, ExtractorLinkType.DASH) {
                                this.referer = "$mainUrl/"
                                this.isLocal = true
                            }
                        )
                        any = true
                    } else {
                        println("$TAG: [UGC] Gagal tulis MPD, fallback ke video-only")
                        callback.invoke(
                            newExtractorLink(name, "$name $vLabel", vUrlRaw, ExtractorLinkType.VIDEO) {
                                this.referer = "$mainUrl/"
                            }
                        )
                        any = true
                    }
                } catch (e: Exception) {
                    println("$TAG: [UGC] Exception buildMpd: ${e.message}")
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            println("$TAG: [UGC] Exception di loadUgc: ${e.message}")
            e.printStackTrace()
        }

        // 3. Subtitle
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

        println("$TAG: [UGC] Selesai, any=$any")
        return any
    }

    // ============================================================
    //  BUILD MPD
    // ============================================================
    private fun buildMpd(
        vUrl: String, vW: Int, vH: Int, vBw: Int, vCodecs: String,
        vInitRange: String, vIndexRange: String,
        aUrl: String, aBw: Int, aCodecs: String,
        aInitRange: String, aIndexRange: String,
        durSec: Long
    ): String {
        val dur = if (durSec > 0) "PT${durSec}S" else "PT24M"

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
