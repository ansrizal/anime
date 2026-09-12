package com.bstation

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

// ============================================================
//  DASH LOCAL SERVER
// ============================================================
object DashServer {
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0
    private val manifests = LinkedHashMap<String, String>()

    @Synchronized
    fun ensureStarted(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) return port
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        thread(name = "BStationDashServer", isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    thread(isDaemon = true) { serve(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return port
    }

    fun publish(mpd: String): String {
        ensureStarted()
        val id = UUID.randomUUID().toString().replace("-", "")
        synchronized(manifests) {
            manifests[id] = mpd
            while (manifests.size > 10) {
                val k = manifests.keys.firstOrNull() ?: break
                manifests.remove(k)
            }
        }
        return "http://127.0.0.1:$port/dash/$id.mpd"
    }

    private fun serve(client: Socket) {
        try {
            client.use { sock ->
                sock.soTimeout = 5000
                val reader = sock.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: return
                val id = path.substringAfterLast("/").substringBefore("?").substringBefore(".")
                val body = synchronized(manifests) { manifests[id] }
                val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val status = if (body == null) "404 Not Found" else "200 OK"
                val headers = "HTTP/1.1 $status\r\n" +
                        "Content-Type: application/dash+xml\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n" +
                        "Accept-Ranges: none\r\n" +
                        "\r\n"
                val out = sock.getOutputStream()
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}

// ============================================================
//  MAIN API
// ============================================================
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

    private val PREFIX_PGC = "PGCEP_"
    private val PREFIX_UGC = "UGC_"

    private val pgcRegex = Regex("""PGCEP_(\d+)""")
    private val ugcRegex = Regex("""UGC_(\d+)""")

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
        return newHomePageResponse(home, true)
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
    //  LOAD (dengan deteksi episode berhalaman + multi-season)
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        println("$TAG: [LOAD] URL = $url")

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            println("$TAG: [LOAD] âŒ app.get GAGAL: ${e.message}")
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
            println("$TAG: [LOAD] â†’ UGC aid=$aid, data=$PREFIX_UGC$aid")
            return newMovieLoadResponse(title, url, TvType.Movie, "$PREFIX_UGC$aid") {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ==== PGC series ====
        val seasonMatch = Regex("""/play/(\d+)""").find(url) ?: return null
        val primarySeasonId = seasonMatch.groupValues[1]
        println("$TAG: [LOAD] â†’ primary seasonId=$primarySeasonId")

        val seenIds = mutableSetOf<String>()
        val episodes = mutableListOf<Episode>()

        fun addEp(id: String, name: String, num: Int?, cover: String?) {
            if (!seenIds.add(id)) return
            episodes.add(newEpisode("$PREFIX_PGC$id") {
                this.name = name
                this.episode = num
                this.posterUrl = cover
            })
        }

        var rawLogged = false

        suspend fun fetchSeason(sid: String, tag: String) {
            var successWithParam: String? = null
            for (pageParam in listOf("page", "pn")) {
                var page = 1
                var anyAdded = false
                while (page <= 100) {
                    val u = "$apiUrl/intl/gateway/web/v2/ogv/play/series?" +
                            "s_locale=id_ID&platform=web&season_id=$sid" +
                            "&$pageParam=$page"

                    val response = try {
                        app.get(u, headers = apiHeaders)
                    } catch (e: Exception) {
                        println("$TAG: [$tag] $pageParam=$page err: ${e.message}")
                        break
                    }

                    if (!rawLogged) {
                        println("$TAG: [RAW] $pageParam=$page: ${response.text.take(2500)}")
                        rawLogged = true
                    }

                    val resp = response.parsedSafe<SeriesApiResponse>()
                    val sections = resp?.data?.sectionsList ?: resp?.data?.sections
                    if (sections.isNullOrEmpty()) {
                        println("$TAG: [$tag] $pageParam=$page: sections null/kosong")
                        break
                    }

                    val before = episodes.size
                    sections.forEach { s ->
                        s.episodes?.forEach { ep ->
                            val id = ep.episodeId?.toString() ?: return@forEach
                            val t = ep.titleDisplay ?: ep.longTitleDisplay
                                ?: ep.shortTitleDisplay ?: "Episode"
                            val n = Regex("""E(\d+)""").find(t)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            addEp(id, t, n, ep.cover.cleanImage() ?: poster)
                        }
                    }
                    val added = episodes.size - before
                    println("$TAG: [$tag] $pageParam=$page: added=$added total=${episodes.size}")

                    val hasNext = resp?.data?.pagination?.hasNext
                    if (added == 0) break
                    anyAdded = true
                    if (hasNext == false) break
                    page++
                }
                if (anyAdded) {
                    successWithParam = pageParam
                    break
                }
            }
            if (successWithParam == null) {
                println("$TAG: [$tag] season=$sid: tidak ada episode didapat")
            }
        }

        // 1. Fetch primary season dengan pagination
        fetchSeason(primarySeasonId, "primary")

        // 2. Merge HTML (fallback / tambahan)
        val htmlBefore = episodes.size
        document.select("a.ep-item").forEach { el ->
            val href = el.attr("href").substringBefore("?")
            val m = Regex("""/play/\d+/(\d+)""").find(href) ?: return@forEach
            val epId = m.groupValues[1]
            val shortTitle = el.text().trim()
            val longTitle = el.attr("title")
            val num = Regex("""E(\d+)""").find(shortTitle)
                ?.groupValues?.get(1)?.toIntOrNull()
            val name = if (longTitle.isNotBlank()) "$shortTitle - $longTitle" else shortTitle
            addEp(epId, name, num, null)
        }
        println("$TAG: [LOAD] HTML added=${episodes.size - htmlBefore} total=${episodes.size}")

        // 3. Deteksi season terkait dari HTML
        val related = mutableSetOf<String>()
        document.select("a[href]").forEach { el ->
            val href = el.attr("href").substringBefore("?").trimEnd('/')
            Regex("""/play/(\d+)$""").find(href)?.let {
                val sid = it.groupValues[1]
                if (sid != primarySeasonId) related.add(sid)
            }
        }
        if (related.isNotEmpty()) {
            println("$TAG: [LOAD] Related seasons: $related")
            related.forEach { sid -> fetchSeason(sid, "related") }
        }

        // 4. Deteksi season dari field API (data.seasons)
        try {
            val u = "$apiUrl/intl/gateway/web/v2/ogv/play/series?" +
                    "s_locale=id_ID&platform=web&season_id=$primarySeasonId"
            val parsed = app.get(u, headers = apiHeaders).parsedSafe<SeriesApiResponse>()
            parsed?.data?.seasons?.forEach { s ->
                val sid = s.seasonId?.toString() ?: return@forEach
                if (sid != primarySeasonId && sid !in related) {
                    println("$TAG: [LOAD] API related season: $sid (${s.title})")
                    fetchSeason(sid, "api-rel")
                }
            }
        } catch (_: Exception) { }

        println("$TAG: [LOAD] FINAL total=${episodes.size}")

        val sorted = episodes.sortedWith(
            compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name })
        )

        return newTvSeriesLoadResponse(title, url, TvType.Anime, sorted) {
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
        println("$TAG: [loadLinks] DATA RAW = '$data'")
        println("$TAG: ################################")

        val pgcMatch = pgcRegex.find(data)
        val ugcMatch = ugcRegex.find(data)

        return when {
            pgcMatch != null -> {
                val epId = pgcMatch.groupValues[1]
                println("$TAG: [loadLinks] âœ… PGC MATCH, epId='$epId'")
                loadPgc(epId, callback, subtitleCallback)
            }
            ugcMatch != null -> {
                val aid = ugcMatch.groupValues[1]
                println("$TAG: [loadLinks] âœ… UGC MATCH, aid='$aid'")
                loadUgc(aid, callback, subtitleCallback)
            }
            else -> {
                println("$TAG: [loadLinks] âŒ TIDAK ADA MATCH di '$data'")
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
                                newExtractorLink(name, "ðŸ“º $name (MP4)", directUrl, ExtractorLinkType.VIDEO) {
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
                        newExtractorLink(name, "ðŸ“º $vLabel (video)", vUrlRaw, ExtractorLinkType.VIDEO) {
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
                            val dataUri = DashServer.publish(mpd)

                            callback.invoke(
                                newExtractorLink(name, "ðŸŽ¬ $vLabel (DASH)", dataUri, ExtractorLinkType.DASH) {
                                    this.referer = "$mainUrl/"
                                }
                            )
                        } catch (e: Exception) {
                            println("$TAG: [PGC] Attempt ${idx + 1}: buildMpd err: ${e.message}")
                        }
                    }
                }

                if (any) {
                    println("$TAG: [PGC] âœ… BERHASIL attempt ${idx + 1}")
                    break
                }
            } catch (e: Exception) {
                println("$TAG: [PGC] Attempt ${idx + 1} exception: ${e.message}")
            }
        }

        if (!any) {
            println("$TAG: [PGC] âŒ SEMUA ENDPOINT GAGAL untuk ep_id=$epId")
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
                            newExtractorLink(name, "ðŸ“º $name (MP4)", directUrl, ExtractorLinkType.VIDEO) {
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
                    newExtractorLink(name, "ðŸ“º $vLabel (video)", vUrlRaw, ExtractorLinkType.VIDEO) {
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
                        val dataUri = DashServer.publish(mpd)

                        callback.invoke(
                            newExtractorLink(name, "ðŸŽ¬ $vLabel (DASH)", dataUri, ExtractorLinkType.DASH) {
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
    data class SeriesApiData(
        @JsonProperty("sectionsList") val sectionsList: List<SeriesApiSection>?,
        @JsonProperty("sections") val sections: List<SeriesApiSection>?,
        @JsonProperty("seasons") val seasons: List<RelatedSeason>?,
        @JsonProperty("pagination") val pagination: PaginationInfo?
    )
    data class RelatedSeason(
        @JsonProperty("season_id") val seasonId: Long?,
        @JsonProperty("title") val title: String?
    )
    data class PaginationInfo(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("page_size") val pageSize: Int?,
        @JsonProperty("total") val total: Int?,
        @JsonProperty("has_next") val hasNext: Boolean?
    )
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
