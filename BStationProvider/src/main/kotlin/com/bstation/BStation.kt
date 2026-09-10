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

    private val TAG = "BStation"

    // Prefix anti-resolve (tanpa ":")
    private val PREFIX_PGC = "PGCEP_"
    private val PREFIX_UGC = "UGC_"

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
    //  XML ESCAPE
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
        println("$TAG: [LOAD] URL = $url")

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            println("$TAG: [LOAD] ❌ app.get GAGAL: ${e.message}")
            return null
        }

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
            println("$TAG: [LOAD] → UGC aid=$aid, data=$PREFIX_UGC$aid")
            return newMovieLoadResponse(title, url, TvType.Movie, "$PREFIX_UGC$aid") {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ==== PGC series ====
        val seasonMatch = Regex("""/play/(\d+)""").find(url) ?: return null
        val seasonId = seasonMatch.groupValues[1]
        println("$TAG: [LOAD] → PGC seasonId=$seasonId")

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
                    episodes.add(newEpisode("$PREFIX_PGC$id") {
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
            println("$TAG: [PGC-LOAD] Fallback scraping HTML")
            val epElements = document.select("a.ep-item")
            println("$TAG: [PGC-LOAD] Jumlah elemen a.ep-item = ${epElements.size}")
            epElements.forEach { el ->
                val href = el.attr("href").substringBefore("?")
                val m = Regex("""/play/\d+/(\d+)""").find(href) ?: return@forEach
                val epId = m.groupValues[1]
                val shortTitle = el.text().trim()
                val longTitle = el.attr("title")
                val epNum = Regex("""E(\d+)""").find(shortTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode("$PREFIX_PGC$epId") {
                    this.name = if (longTitle.isNotBlank()) "$shortTitle - $longTitle" else shortTitle
                    this.episode = epNum
                })
            }
            println("$TAG: [PGC-LOAD] Total episodes dari HTML: ${episodes.size}")
            episodes.firstOrNull()?.let { ep ->
                println("$TAG: [PGC-LOAD] Contoh episode -> name='${ep.name}', data='${ep.data}'")
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
        println("$TAG: ################################")
        println("$TAG: [loadLinks] DATA RECEIVED = '$data'")
        println("$TAG: ################################")

        return when {
            data.startsWith(PREFIX_PGC) -> {
                val epId = data.removePrefix(PREFIX_PGC)
                println("$TAG: [loadLinks] → PGC epId='$epId'")
                loadPgc(epId, callback, subtitleCallback)
            }
            data.startsWith(PREFIX_UGC) -> {
                val aid = data.removePrefix(PREFIX_UGC)
                println("$TAG: [loadLinks] → UGC aid='$aid'")
                loadUgc(aid, callback, subtitleCallback)
            }
            else -> {
                println("$TAG: [loadLinks] ❌ UNKNOWN PREFIX: '$data'")
                false
            }
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

        val attempts = listOf(
            "$apiUrl/intl/gateway/web/playurl?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=16",
            "$apiUrl/intl/gateway/web/playurl?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=16&type=0&device=wap&tf=0",
            "$apiUrl/intl/gateway/web/playurl?s_locale=id_ID&platform=web&ep_id=$epId&qn=64&fnval=0&type=0&device=wap&tf=0",
            "$apiUrl/pgc/player/web/playurl?ep_id=$epId&platform=web&s_locale=id_ID&qn=64&fnval=16",
            "$apiUrl/pgc/player/web/playurl?ep_id=$epId&platform=web&s_locale=id_ID&qn=64&fnval=0",
        )

        for ((idx, attemptUrl) in attempts.withIndex()) {
            println("$TAG: [PGC] === Attempt ${idx + 1}/${attempts.size} ===")
            println("$TAG: [PGC] URL -> $attemptUrl")

            try {
                val resp = app.get(attemptUrl, headers = apiHeaders)
                    .parsedSafe<PlayUrlResponse>()

                if (resp == null) {
                    println("$TAG: [PGC] Attempt ${idx + 1}: parsedSafe=null")
                    continue
                }
                if (resp.data == null) {
                    println("$TAG: [PGC] Attempt ${idx + 1}: resp.data=null")
                    continue
                }
                if (resp.data.playurl == null) {
                    println("$TAG: [PGC] Attempt ${idx + 1}: playurl=null")
                    continue
                }

                val play = resp.data.playurl
                val videos = play.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
                val audios = play.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
                val durlCount = play.durl?.size ?: 0
                val durationSec = (play.duration ?: 0L) / 1000
                val audio = audios.firstOrNull()

                println("$TAG: [PGC] Attempt ${idx + 1}: videos=${videos.size}, audios=${audios.size}, durl=$durlCount, dur=$durationSec")

                if (videos.isEmpty() && play.durl?.isNotEmpty() == true) {
                    println("$TAG: [PGC] Attempt ${idx + 1}: pakai durl")
                    play.durl.forEach { d ->
                        d.url?.let { directUrl ->
                            callback.invoke(
                                newExtractorLink(name, "📺 $name (MP4)", directUrl, ExtractorLinkType.VIDEO) {
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

                    callback.invoke(
                        newExtractorLink(name, "📺 $vLabel (video)", vUrlRaw, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    any = true

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
                                newExtractorLink(name, "🎬 $vLabel (DASH)", dataUri, ExtractorLinkType.DASH) {
                                    this.referer = "$mainUrl/"
                                }
                            )
                        } catch (e: Exception) {
                            println("$TAG: [PGC] Attempt ${idx + 1}: buildMpd err: ${e.message}")
                        }
                    }
                }

                if (any) {
                    println("$TAG: [PGC] ✅ BERHASIL attempt ${idx + 1}")
                    break
                }
            } catch (e: Exception) {
                println("$TAG: [PGC] Attempt ${idx + 1} exception: ${e.message}")
            }
        }

        if (!any) {
            println("$TAG: [PGC] ❌ SEMUA ENDPOINT GAGAL untuk ep_id=$epId")
        }

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
    //  UGC
    // ============================================================
    private suspend fun loadUgc(
        aid: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var any = false

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
                println("$TAG: [UGC] ERROR - resp/data/playurl null")
                return false
            }

            val play = resp.data.playurl
            val videos = play.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
            val audios = play.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
            val durationSec = (play.duration ?: 0L) / 1000
            val audio = audios.firstOrNull()

            println("$TAG: [UGC] videos=${videos.size}, audios=${audios.size}, dur=$durationSec")

            if (videos.isEmpty() && play.durl?.isNotEmpty() == true) {
                play.durl.forEach { d ->
                    d.url?.let { directUrl ->
                        callback.invoke(
                            newExtractorLink(name, "📺 $name (MP4)", directUrl, ExtractorLinkType.VIDEO) {
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

                callback.invoke(
                    newExtractorLink(name, "📺 $vLabel (video)", vUrlRaw, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                    }
                )
                any = true

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
                            newExtractorLink(name, "🎬 $vLabel (DASH)", dataUri, ExtractorLinkType.DASH) {
                                this.referer = "$mainUrl/"
                            }
                        )
                    } catch (e: Exception) {
                        println("$TAG: [UGC] buildMpd err: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("$TAG: [UGC] Exception di loadUgc: ${e.message}")
            e.printStackTrace()
        }

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
