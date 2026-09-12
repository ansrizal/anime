package com.bstation

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
    private val TAG = "BStation"

    private val apiHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/id/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Chromium\";v=\"137\";\"Not/A)Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-site"
    )

    private val PREFIX_PGC = "PGCEP_"
    private val PREFIX_UGC = "UGC_"

    private val pgcRegex = Regex("""PGCEP_(\d+)""")
    private val ugcRegex = Regex("""UGC_(\d+)""")

    private val recommendEndpoint =
        "$apiUrl/intl/gateway/web/v2/home/recommend?s_locale=id_ID&platform=web"

    @Volatile
    private var cookiesReady = false

    // ============================================================
    //  WARM-UP
    // ============================================================
    private suspend fun ensureCookies() {
        if (cookiesReady) return
        synchronized(this) {
            if (cookiesReady) return
        }
        try {
            println("$TAG: [COOKIE] Warm-up kunjungi $mainUrl/id/ ...")
            val resp = app.get(
                "$mainUrl/id/",
                headers = mapOf(
                    "User-Agent" to (apiHeaders["User-Agent"] ?: ""),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            )
            // headers bertipe Map<String, String> → ambil sebagai String saja
            val setCookie: String? = resp.headers["Set-Cookie"]
            println("$TAG: [COOKIE] Set-Cookie=${setCookie?.take(120)}")
            cookiesReady = true
        } catch (e: Exception) {
            println("$TAG: [COOKIE] Warm-up gagal: ${e.message}")
        }
    }

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
        this.select("img").forEach { img: Element ->
            img.attr("src").cleanImage()?.let { return it }
            img.attr("data-src").cleanImage()?.let { return it }
        }
        this.select("source").forEach { src: Element ->
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
    //  URI NORMALIZER
    // ============================================================
    private fun String?.normalizeRecommendUri(): String? {
        if (this.isNullOrBlank()) return null
        val u = this.trim()
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            u.startsWith("bilibili://video/") -> {
                val id = u.removePrefix("bilibili://video/").substringBefore("/").substringBefore("?")
                if (id.isBlank()) null else "$mainUrl/id/video/$id"
            }
            u.startsWith("bilibili://pgc/") -> {
                val rest = u.removePrefix("bilibili://pgc/")
                val parts = rest.split("/").filter { it.isNotBlank() }
                when {
                    parts.size >= 2 && parts[0] == "season" -> "$mainUrl/id/play/${parts[1]}"
                    parts.size >= 2 && parts[0] == "ep" -> "$mainUrl/id/play/${parts[1]}"
                    else -> null
                }
            }
            else -> null
        }
    }

    // ============================================================
    //  CARD PARSER
    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val a: Element = if (this.tagName() == "a") this
        else (this.selectFirst("a[href*=/play/], a[href*=/video/]") ?: return null)

        val rawHref = a.attr("href").ifBlank { return null }
        val cleanHref = rawHref.substringBefore("?")
        val fullUrl: String = when {
            cleanHref.startsWith("//") -> "https:$cleanHref"
            cleanHref.startsWith("/") -> "$mainUrl$cleanHref"
            else -> cleanHref
        }
        if (!fullUrl.contains("/play/") && !fullUrl.contains("/video/")) return null

        val title: String = (a.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst(".card-title")?.text()?.ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null)
        if (title.length < 2) return null

        val poster: String? = a.extractPoster() ?: this.extractPoster()
        val type: TvType = if (fullUrl.contains("/play/")) TvType.Anime else TvType.Movie

        return newAnimeSearchResponse(title, fullUrl, type) {
            this.posterUrl = poster
        }
    }

    // ============================================================
    //  MAIN PAGE
    // ============================================================
    override val mainPage = mainPageOf(
        recommendEndpoint to "Populer",
        "$mainUrl/id/anime" to "Anime",
        "$mainUrl/id/trending" to "Trending",
        "$mainUrl/id/short-drama" to "Dracin",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureCookies()

        if (request.data.contains("/intl/gateway/web/v2/home/recommend")) {
            return getRecommendPage(page, request)
        }

        val document: org.jsoup.nodes.Document = app.get(request.data).document
        val elements: List<Element> = document.select(
            "li.section__list__item, li.scroll-wrap__list__item, div.card-item, div.bstar-video-card"
        ).toList()

        val items: List<SearchResponse> = elements
            .mapNotNull { el: Element -> el.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    // ============================================================
    //  RECOMMEND PAGE (API)
    // ============================================================
    private suspend fun getRecommendPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageSize = if (page == 1) 50 else 20
        val url = "${request.data}&pn=$page&ps=$pageSize"

        println("$TAG: [RECOMMEND] GET page=$page -> $url")

        var rawText: String = ""
        var response: HomeRecommendResponse? = null

        try {
            val resp = app.get(url, headers = apiHeaders)
            rawText = resp.text
            println("$TAG: [RECOMMEND RAW] page=$page len=${rawText.length} preview=${rawText.take(500)}")
            // ✅ pakai parseJson (fungsi statis) agar tidak tergantung resolusi extension
            response = parseJson<HomeRecommendResponse>(rawText)
        } catch (e: Exception) {
            println("$TAG: [RECOMMEND] ❌ Error page $page: ${e.message}")
        }

        if (rawText.contains("错误号: 412")) {
            println("$TAG: [RECOMMEND] ⚠️ Terkena Error 412, refresh cookie & retry...")
            cookiesReady = false
            ensureCookies()
            try {
                val retry = app.get(url, headers = apiHeaders)
                rawText = retry.text
                response = parseJson<HomeRecommendResponse>(rawText)
            } catch (e: Exception) {
                println("$TAG: [RECOMMEND] Retry gagal: ${e.message}")
            }
        }

        return buildRecommendResponse(response, request, page)
    }

    private fun buildRecommendResponse(
        response: HomeRecommendResponse?,
        request: MainPageRequest,
        page: Int
    ): HomePageResponse {
        val rawItems: List<HomeRecommendItem> = response?.data?.items ?: emptyList()

        val items: List<SearchResponse> = rawItems.mapNotNull { item: HomeRecommendItem ->
            val uri: String = item.uri.normalizeRecommendUri()
                ?: item.args?.aid?.let { "$mainUrl/id/video/$it" }
                ?: item.args?.seasonId?.let { "$mainUrl/id/play/$it" }
                ?: return@mapNotNull null

            val title: String = item.title?.ifBlank { null }
                ?: item.args?.title?.ifBlank { null }
                ?: return@mapNotNull null

            if (title.length < 2) return@mapNotNull null

            val poster: String? = item.cover.cleanImage() ?: item.pic.cleanImage()
            val type: TvType = if (uri.contains("/play/")) TvType.Anime else TvType.Movie

            newAnimeSearchResponse(title, uri, type) {
                this.posterUrl = poster
            }
        }

        println("$TAG: [RECOMMEND] page=$page -> items=${items.size}")

        val hasNext = items.isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    // ============================================================
    //  SEARCH
    // ============================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/id/search-result?q=${query.replace(" ", "%20")}"
        val document: org.jsoup.nodes.Document = app.get(url).document
        val elements: List<Element> = document.select(
            "li.section__list__item, li.scroll-wrap__list__item, div.card-item, div.bstar-video-card"
        ).toList()

        val items: List<SearchResponse> = elements
            .mapNotNull { el: Element -> el.toSearchResult() }
            .distinctBy { it.url }

        return items.toNewSearchResponseList()
    }

    // ============================================================
    //  LOAD
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        println("$TAG: [LOAD] URL = $url")
        ensureCookies()

        val document: org.jsoup.nodes.Document = try {
            app.get(url).document
        } catch (e: Exception) {
            println("$TAG: [LOAD] ❌ app.get GAGAL: ${e.message}")
            return null
        }

        val title: String = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" HD")?.substringBefore(" | ")?.ifBlank { null }
            ?: document.selectFirst(".bstar-meta__title a")?.text()?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: "Unknown"

        val poster: String? = document.selectFirst("meta[property=og:image]")?.attr("content").cleanImage()
        val description: String? = document.selectFirst(".bstar-meta__desc")?.text()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        if (url.contains("/video/")) {
            val aidMatch = Regex("""/video/(\d+)""").find(url)
            val aid = aidMatch?.groupValues?.get(1) ?: return null
            println("$TAG: [LOAD] → UGC aid=$aid, data=$PREFIX_UGC$aid")
            return newMovieLoadResponse(title, url, TvType.Movie, "$PREFIX_UGC$aid") {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val seasonMatch = Regex("""/play/(\d+)""").find(url) ?: return null
        val primarySeasonId = seasonMatch.groupValues[1]
        println("$TAG: [LOAD] → primary seasonId=$primarySeasonId")

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

                    val resp = try {
                        app.get(u, headers = apiHeaders)
                    } catch (e: Exception) {
                        println("$TAG: [$tag] $pageParam=$page err: ${e.message}")
                        break
                    }

                    if (!rawLogged) {
                        println("$TAG: [RAW] $pageParam=$page: ${resp.text.take(2500)}")
                        rawLogged = true
                    }

                    val parsed: SeriesApiResponse? = parseJson<SeriesApiResponse>(resp.text)
                    val sections: List<SeriesApiSection>? =
                        parsed?.data?.sectionsList ?: parsed?.data?.sections
                    if (sections.isNullOrEmpty()) {
                        println("$TAG: [$tag] $pageParam=$page: sections null/kosong")
                        break
                    }

                    val before = episodes.size
                    sections.forEach { s: SeriesApiSection ->
                        s.episodes?.forEach { ep: SeriesApiEpisode ->
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

                    val hasNext = parsed?.data?.pagination?.hasNext
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

        fetchSeason(primarySeasonId, "primary")

        val htmlBefore = episodes.size
        document.select("a.ep-item").forEach { el: Element ->
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

        val related = mutableSetOf<String>()
        document.select("a[href]").forEach { el: Element ->
            val href = el.attr("href").substringBefore("?").trimEnd('/')
            Regex("""/play/(\d+)$""").find(href)?.let {
                val sid = it.groupValues[1]
                if (sid != primarySeasonId) related.add(sid)
            }
        }
        if (related.isNotEmpty()) {
            println("$TAG: [LOAD] Related seasons: $related")
            related.forEach { sid: String -> fetchSeason(sid, "related") }
        }

        try {
            val u = "$apiUrl/intl/gateway/web/v2/ogv/play/series?" +
                    "s_locale=id_ID&platform=web&season_id=$primarySeasonId"
            val resp = app.get(u, headers = apiHeaders)
            val parsed: SeriesApiResponse? = parseJson<SeriesApiResponse>(resp.text)
            parsed?.data?.seasons?.forEach { s: RelatedSeason ->
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
        ensureCookies()

        val pgcMatch = pgcRegex.find(data)
        val ugcMatch = ugcRegex.find(data)

        return when {
            pgcMatch != null -> {
                val epId = pgcMatch.groupValues[1]
                println("$TAG: [loadLinks] ✅ PGC MATCH, epId='$epId'")
                loadPgc(epId, callback, subtitleCallback)
            }
            ugcMatch != null -> {
                val aid = ugcMatch.groupValues[1]
                println("$TAG: [loadLinks] ✅ UGC MATCH, aid='$aid'")
                loadUgc(aid, callback, subtitleCallback)
            }
            else -> {
                println("$TAG: [loadLinks] ❌ TIDAK ADA MATCH di '$data'")
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

            try {
                var resp = app.get(attemptUrl, headers = apiHeaders)

                if (resp.text.contains("错误号: 412")) {
                    println("$TAG: [PGC] ⚠️ 412, refresh cookie & retry")
                    cookiesReady = false
                    ensureCookies()
                    resp = app.get(attemptUrl, headers = apiHeaders)
                }

                val parsed: PlayUrlResponse? = parseJson<PlayUrlResponse>(resp.text)

                if (parsed?.data?.playurl == null) {
                    println("$TAG: [PGC] Attempt ${idx + 1}: data/playurl null")
                    continue
                }

                val play = parsed.data!!.playurl!!
                val videos = play.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
                val audios = play.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
                val durlCount = play.durl?.size ?: 0
                val durationSec = (play.duration ?: 0L) / 1000
                val audio = audios.firstOrNull()

                println("$TAG: [PGC] Attempt ${idx + 1}: videos=${videos.size}, audios=${audios.size}, durl=$durlCount, dur=$durationSec")

                if (videos.isEmpty() && play.durl?.isNotEmpty() == true) {
                    play.durl.forEach { d: Durl ->
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

                videos.sortedByDescending { it.streamInfo?.quality ?: 0 }.forEach { v: VideoItem ->
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
                            val dataUri = DashServer.publish(mpd)

                            callback.invoke(
                                newExtractorLink(name, "🎬 $vLabel (DASH)", dataUri, ExtractorLinkType.DASH) {
                                    this.referer = "$mainUrl/"
                                }
                            )
                        } catch (e: Exception) {
                            println("$TAG: [PGC] buildMpd err: ${e.message}")
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
            val resp = app.get(subUrl, headers = apiHeaders)
            val parsed: SubtitleApiResponse? = parseJson<SubtitleApiResponse>(resp.text)
            parsed?.data?.subtitles?.forEach { sub: SubtitleItem ->
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
            val viewParsed: ViewApiResponse? = parseJson<ViewApiResponse>(viewResp.text)
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

            val resp = app.get(finalUrl, headers = apiHeaders)
            val parsed: PlayUrlResponse? = parseJson<PlayUrlResponse>(resp.text)

            if (parsed?.data?.playurl == null) {
                println("$TAG: [UGC] ERROR - data/playurl null")
                return false
            }

            val play = parsed.data!!.playurl!!
            val videos = play.video?.filter { !it.videoResource?.url.isNullOrBlank() } ?: emptyList()
            val audios = play.audioResource?.filter { !it.url.isNullOrBlank() } ?: emptyList()
            val durationSec = (play.duration ?: 0L) / 1000
            val audio = audios.firstOrNull()

            println("$TAG: [UGC] videos=${videos.size}, audios=${audios.size}, dur=$durationSec")

            if (videos.isEmpty() && play.durl?.isNotEmpty() == true) {
                play.durl.forEach { d: Durl ->
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

            videos.sortedByDescending { it.streamInfo?.quality ?: 0 }.forEach { v: VideoItem ->
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
                        val dataUri = DashServer.publish(mpd)

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
            val resp = app.get(subUrl, headers = apiHeaders)
            val parsed: SubtitleApiResponse? = parseJson<SubtitleApiResponse>(resp.text)
            parsed?.data?.subtitles?.forEach { sub: SubtitleItem ->
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

    data class HomeRecommendResponse(@JsonProperty("data") val data: HomeRecommendData?)
    data class HomeRecommendData(
        @JsonProperty("items") val items: List<HomeRecommendItem>?,
        @JsonProperty("has_more") val hasMore: Boolean?,
        @JsonProperty("next_offset") val nextOffset: String?
    )
    data class HomeRecommendItem(
        @JsonProperty("uri") val uri: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("pic") val pic: String?,
        @JsonProperty("goto") val goto: String?,
        @JsonProperty("card_type") val cardType: String?,
        @JsonProperty("args") val args: RecommendArgs?
    )
    data class RecommendArgs(
        @JsonProperty("aid") val aid: Long?,
        @JsonProperty("bvid") val bvid: String?,
        @JsonProperty("up_id") val upId: Long?,
        @JsonProperty("up_name") val upName: String?,
        @JsonProperty("season_id") val seasonId: Long?,
        @JsonProperty("ep_id") val epId: Long?,
        @JsonProperty("title") val title: String?
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
