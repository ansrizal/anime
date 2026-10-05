package com.ansrizal.filmapik

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilmApikProvider : MainAPI() {
    override var mainUrl = "https://filmapik.college"
    override var name = "FilmApik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val turnstileInterceptor = TurnstileInterceptor("cf_clearance")

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private val fullHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "max-age=0",
        "DNT" to "1",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "sec-ch-ua" to "\"Chromium\";v=\"154\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"154\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private suspend fun request(url: String): NiceResponse {
        return app.get(url, headers = headers, interceptor = turnstileInterceptor, timeout = 60)
    }

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "category/box-office/" to "Box Office",
        "latest/" to "Film Terbaru",
        "tvshows/" to "Drama Terbaru",
        "tvshows-genre/anime/" to "Anime",
        "tvshows-genre/k-drama/" to "Drama Korea",
        "category/action/" to "Action",
        "category/comedy/" to "Comedy",
        "category/horror/" to "Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            val data = request.data.removeSuffix("/")
            if (data.isEmpty()) "$mainUrl/latest/page/$page/" else "$mainUrl/$data/page/$page/"
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = request(url).document
        val home = mutableListOf<HomePageList>()

        if (request.data.isEmpty() && page <= 1) {
            val boxOffice = document.select("#famv-boxoffice a.group").mapNotNull { it.toSearchResult() }
            if (boxOffice.isNotEmpty()) home.add(HomePageList("Box Office", boxOffice, isHorizontalImages = true))
            val tvShows = document.select("#famv-tvshows a.group").mapNotNull { it.toSearchResult() }
            if (tvShows.isNotEmpty()) home.add(HomePageList("Drama Terbaru", tvShows, isHorizontalImages = true))
            val latest = document.select("article.card, .grid article").mapNotNull { it.toSearchResult() }
            if (latest.isNotEmpty()) home.add(HomePageList("Film Terbaru", latest))
        } else {
            val items = document.select("article.card, .grid article, a.group, div.card").mapNotNull { it.toSearchResult() }
            home.add(HomePageList(request.name, items))
        }
        return newHomePageResponse(home, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, .title, a[title]")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim() ?: return null
        val linkElement = if (this.tagName() == "a") this else this.selectFirst("a")
        val href = fixUrl(linkElement?.attr("href") ?: return null)
        if (href == mainUrl || href == "$mainUrl/" ||
            href.contains("/category/") || href.contains("/release-year/")) return null
        val img = this.selectFirst("img")
        val srcset = img?.attr("srcset") ?: img?.attr("data-srcset")
        val posterUrl = fixUrlNull(
            if (!srcset.isNullOrBlank()) srcset.split(",").last().trim().split(" ").first()
            else img?.attr("abs:data-src") ?: img?.attr("abs:src") ?: img?.attr("src")
        )
        val isSeries = href.contains("/tvshows/") || href.contains("/series/") ||
                href.contains("/tv/") || href.contains("/tv-series/") || href.contains("/episodes/")
        val quality = this.selectFirst(".badge-quality")?.text()?.trim()
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                quality?.let { addQuality(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                quality?.let { addQuality(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = request(searchUrl).document
        return document.select("article.card, .grid article, a.group").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document
        val title = document.selectFirst("h1.entry-title, h1, .title, .name")?.text()?.trim() ?: ""
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.thumb img, img.wp-post-image, .poster img")?.attr("src")
        )
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.entry-content, div.synopsis, [itemprop=description], .description, .prose")?.text()?.trim()
        val isSeries = url.contains("/tvshows/") || url.contains("/series/") ||
                url.contains("/tv/") || url.contains("/tv-series/") || url.contains("/episodes/") ||
                document.selectFirst(".episodios, .list-episode, .eplister, #episodes-list, .famv-episodes, .famv-season-list, .famv-episode-btn") != null
        val isAnime = url.contains("anime") ||
                document.select(".badge-year, .prose").text().contains("anime", true)
        return if (isSeries) {
            val episodes = document.select(".episodios li, .list-episode li, .eplister li, #episodes-list a, .famv-episodes a, .famv-episode-btn, a[href*='/episodes/']").mapNotNull { elem ->
                val a = if (elem.tagName() == "a") elem else elem.selectFirst("a")
                val epUrl = fixUrl(a?.attr("href") ?: return@mapNotNull null)
                val epName = a.text().trim().ifEmpty {
                    elem.selectFirst(".numerando, .epl-num, .eps")?.text()?.trim() ?: "Episode"
                }
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = Regex("""\d+""").findAll(epName).lastOrNull()?.value?.toIntOrNull()
                }
            }.distinctBy { it.data }.sortedBy { it.episode }
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
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

    // ========================================================================
    // LOAD LINKS
    // ========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        println("[FilmApik] ===== loadLinks for $data =====")

        val html = try {
            app.get(data, headers = fullHeaders, interceptor = turnstileInterceptor, timeout = 60).text
        } catch (t: Throwable) {
            println("[FilmApik] request failed: ${t.message}"); ""
        }

        println("[FilmApik] HTML length=${html.length}, has famvServers=${html.contains("famvServers")}")

        val playerUrls = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        fun addPlayer(name: String, url: String) {
            var u = url.trim().replace("\\/", "/")
            if (u.isBlank()) return
            if (u.startsWith("//")) u = "https:$u"
            if (!u.startsWith("http")) return
            if (u.contains("facebook.com") || u.contains("twitter.com") ||
                u.contains("google.com") || u.contains("youtube.com") ||
                u.contains("gstatic.com") || u.contains("googleapis.com") ||
                u.contains("instagram.com") || u.contains("sharethis.com") ||
                u.contains("histats.com") || u.contains("cloudflareinsights.com") ||
                u.contains("googletagmanager.com") || u.contains("wp-json") ||
                u.contains("wp-content") || u.contains("wp-includes") ||
                u.contains("admin-ajax") || u.contains("cutt.ly") ||
                u.contains("image.cdndrive") || u.contains("instarooliths") ||
                u.contains("s10.histats") || u.contains("cdn-cgi")
            ) return
            if (!seen.add(u)) return
            playerUrls.add(name to u)
        }

        // Parse famvServers
        Regex("""window\.famvServers\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { serversJson ->
                Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"select"[^}]*?"url"\s*:\s*"([^"]+)"""")
                    .findAll(serversJson).forEach { m ->
                        addPlayer(m.groupValues[1], m.groupValues[2])
                    }
            }

        // DOM fallback
        if (playerUrls.isEmpty() && html.isNotBlank()) {
            try {
                val doc = org.jsoup.Jsoup.parse(html)
                doc.select("a[data-url], a.player-option, .famv-server-btn, #player-list a").forEach { a ->
                    val url = a.attr("data-url").ifBlank { a.attr("href") }
                    val name = a.attr("data-server").ifBlank { a.text().trim() }
                    addPlayer(name.ifBlank { "player" }, url)
                }
            } catch (_: Throwable) {}
        }

        // Brutal regex fallback for known player domains
        if (playerUrls.isEmpty()) {
            val known = listOf(
                "byseqekaho", "f7hyg4q", "n1mwq", "strp2p", "abyssplayer",
                "efek.stream", "filemoon", "streamwish", "mixdrop", "doodstream",
                "voe.sx", "vidoza", "upstream", "filelions", "mp4upload",
                "streamtape", "turbovidhls", "netu", "waaw", "buzzheavier"
            )
            Regex("""https?://[^\s"'<>\\]+""").findAll(html).forEach { m ->
                if (known.any { m.value.contains(it, true) }) addPlayer("regex", m.value)
            }
        }

        println("[FilmApik] ===== playerUrls (${playerUrls.size}) =====")
        playerUrls.forEach { println("[FilmApik] -> ${it.first} | ${it.second}") }

        // === Process each player ===
        for ((serverName, url) in playerUrls) {
            try {
                val ok = when {
                    url.contains("byseqekaho.com") || url.contains("f7hyg4q.org") || url.contains("n1mwq.org") -> {
                        println("[FilmApik] >>> FILEMOON: $url")
                        extractFilemoon(url, serverName, callback)
                    }
                    url.contains("strp2p.site") -> {
                        println("[FilmApik] >>> STREAMP2P: $url")
                        extractStrp2p(url, serverName, callback)
                    }
                    url.contains("abyssplayer.com") -> {
                        println("[FilmApik] >>> ABYSS/HYDRAX: $url")
                        extractAbyss(url, serverName, callback)
                    }
                    url.contains("efek.stream") -> {
                        println("[FilmApik] >>> VIP SERVER: $url")
                        extractGeneric(url, serverName, "efek", callback)
                    }
                    else -> {
                        println("[FilmApik] >>> loadExtractor fallback: $url")
                        try {
                            loadExtractor(fixUrl(url), subtitleCallback, callback)
                        } catch (_: Throwable) { false }
                    }
                }
                if (ok) found = true
            } catch (t: Throwable) {
                println("[FilmApik] Player $serverName error: ${t.message}")
            }
        }

        println("[FilmApik] ===== DONE, found=$found =====")
        return found
    }

    // ========================================================================
    // STREAMP2P EXTRACTOR
    // ========================================================================

    private suspend fun extractStrp2p(
        playerUrl: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val uri = try { java.net.URI(playerUrl) } catch (_: Throwable) { return false }
            val host = uri.host ?: return false
            val id = (uri.fragment ?: playerUrl.substringAfterLast("#", "")).trim()
            if (id.isBlank()) {
                println("[Strp2p] no fragment id in $playerUrl")
                return false
            }

            val base = "https://$host"
            val apiH = mapOf(
                "User-Agent" to UA,
                "Referer" to "$base/",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
            )

            println("[Strp2p] host=$host id=$id")

            // 1. Info (optional, tapi ikuti flow)
            try { getRaw("$base/api/v1/info?id=$id", apiH) } catch (_: Throwable) {}

            // 2. Ambil token
            val videoRaw = getRaw(
                "$base/api/v1/video?id=$id&w=1366&h=768&r=filmapik.college",
                apiH
            )
            println("[Strp2p] video resp len=${videoRaw.length}")

            var token = ""
            try {
                val j = JSONObject(videoRaw)
                token = j.optString("t", "")
                    .ifBlank { j.optString("token", "") }
                    .ifBlank { j.optString("hash", "") }
                if (token.isBlank()) {
                    val data = j.optJSONObject("data")
                    if (data != null) {
                        token = data.optString("t", "")
                            .ifBlank { data.optString("token", "") }
                            .ifBlank { data.optString("hash", "") }
                    }
                }
            } catch (_: Throwable) {}

            if (token.isBlank()) {
                Regex(""""(?:t|token|hash)"\s*:\s*"([A-Za-z0-9_\-]+)"""")
                    .find(videoRaw)?.let { token = it.groupValues[1] }
            }

            println("[Strp2p] token len=${token.length}")
            if (token.isBlank()) {
                println("[Strp2p] no token, dump: ${videoRaw.take(400)}")
                return false
            }

            // 3. Ambil player (response berisi m3u8 + data URI k/kx)
            val playerRaw = getRaw("$base/api/v1/player?t=$token", apiH)
            println("[Strp2p] player resp len=${playerRaw.length}")

            // 4. Decode data URI -> k, kx
            var k = ""; var kx = ""
            Regex("""data:application/octet-stream;base64,([A-Za-z0-9+/=]+)""")
                .find(playerRaw)?.groupValues?.get(1)?.let { b64 ->
                    try {
                        val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                        val j = JSONObject(decoded)
                        k = j.optString("k", "")
                        kx = j.opt("kx")?.toString() ?: ""
                    } catch (_: Throwable) {}
                }
            println("[Strp2p] k=$k kx=$kx")

            // 5. Ambil m3u8 URL (yang FULL, bukan relative)
            var m3u8: String? = null
            Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                .findAll(playerRaw).forEach {
                    // Prioritaskan URL dari CDN utama (edge2/edge/dst.)
                    if (m3u8 == null || it.value.contains("edge")) m3u8 = it.value
                }
            if (m3u8.isNullOrBlank()) {
                // Coba parse JSON
                try {
                    val j = JSONObject(playerRaw)
                    m3u8 = j.optString("url", "")
                        .ifBlank { j.optString("m3u8", "") }
                        .ifBlank { j.optString("playlist", "") }
                } catch (_: Throwable) {}
            }
            if (m3u8.isNullOrBlank()) {
                println("[Strp2p] no m3u8, dump: ${playerRaw.take(500)}")
                return false
            }

            // 6. Tambahkan k & kx kalau belum ada
            val finalUrl = if (k.isNotBlank() && !m3u8!!.contains("k=")) {
                "$m3u8${if (m3u8!!.contains("?")) "&" else "?"}k=$k&kx=$kx"
            } else m3u8!!

            println("[Strp2p] final m3u8 = $finalUrl")

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name - $serverName",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$base/",
                        "Origin" to base
                    )
                }
            )
            true
        } catch (t: Throwable) {
            println("[FilmApik] extractStrp2p error: ${t.message}")
            false
        }
    }

    // ========================================================================
    // ABYSSPLAYER / HYDRAX EXTRACTOR
    // ========================================================================

    private suspend fun extractAbyss(
        playerUrl: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val base = "https://abyssplayer.com"
            val playerH = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
            )

            val html = try {
                app.get(playerUrl, headers = playerH, timeout = 30).text
            } catch (t: Throwable) {
                println("[Abyss] fetch failed: ${t.message}"); return false
            }
            println("[Abyss] HTML length=${html.length}")

            val candidates = mutableListOf<String>()

            // 1. Cari .fd / .mp4 / .m3u8 langsung
            Regex("""https?://[^\s"'<>\\]+?\.(?:fd|mp4|m3u8)[^\s"'<>\\]*""")
                .findAll(html).forEach { candidates.add(it.value.replace("\\/", "/")) }

            // 2. Cari di src= / file: / source: / url:
            Regex("""(?:src|file|source|url|video_url|videoUrl)"?\s*[:=]\s*["'](https?://[^"']+)["']""",
                RegexOption.IGNORE_CASE).findAll(html).forEach {
                val u = it.groupValues[1].replace("\\/", "/")
                if (u.contains(".fd") || u.contains(".mp4") || u.contains(".m3u8") ||
                    u.contains("sssrr.org") || u.contains("sora")) {
                    candidates.add(u)
                }
            }

            // 3. Cari pola "sssrr.org" atau CDN video lain
            Regex("""https?://[a-z0-9]+\.sssrr\.org/[^\s"'<>\\]+""")
                .findAll(html).forEach { candidates.add(it.value.replace("\\/", "/")) }

            // 4. Decode base64 yang mungkin menyimpan URL
            Regex("""["']([A-Za-z0-9+/=]{40,})["']""").findAll(html).forEach { m ->
                try {
                    val dec = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                    Regex("""https?://[^\s"'<>\\]+""").findAll(dec).forEach {
                        if (it.value.contains(".fd") || it.value.contains(".mp4") || it.value.contains("sssrr"))
                            candidates.add(it.value)
                    }
                } catch (_: Throwable) {}
            }

            println("[Abyss] candidates = ${candidates.distinct()}")

            if (candidates.isEmpty()) {
                println("[Abyss] no candidates found, dump head: ${html.take(500)}")
                return false
            }

            var any = false
            for (u in candidates.distinct()) {
                try {
                    val type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - $serverName",
                            url = u,
                            type = type
                        ) {
                            this.referer = "$base/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to UA,
                                "Referer" to "$base/",
                                "Origin" to base
                            )
                        }
                    )
                    any = true
                } catch (_: Throwable) {}
            }
            any
        } catch (t: Throwable) {
            println("[FilmApik] extractAbyss error: ${t.message}")
            false
        }
    }

    // ========================================================================
    // GENERIC EXTRACTOR — efek.stream / dll
    // ========================================================================

    private suspend fun extractGeneric(
        playerUrl: String,
        serverName: String,
        tag: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val playerHtml = try {
                app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    ),
                    timeout = 30
                ).text
            } catch (t: Throwable) {
                println("[FilmApik] $tag fetch failed: ${t.message}"); return false
            }

            println("[FilmApik] $tag HTML length=${playerHtml.length}")

            val found = mutableListOf<String>()

            Regex("""https?://[^\s"'<>\\]+?\.(m3u8|mp4|fd)[^\s"'<>\\]*""").findAll(playerHtml).forEach { m ->
                found.add(m.value.replace("\\/", "/"))
            }

            Regex(""""(?:file|source|src|url|playlist|hls|video_url|videoUrl)"\s*:\s*"([^"]+\.(?:m3u8|mp4|fd)[^"]*)"""").findAll(playerHtml).forEach { m ->
                val u = m.groupValues[1].replace("\\/", "/")
                if (!found.contains(u)) found.add(u)
            }

            Regex("""(https?:)?\\?/\\?/[^\s"'<>\\]+?\.(?:m3u8|mp4|fd)""").findAll(playerHtml).forEach { m ->
                val u = m.value.replace("\\/", "/").replace("\\", "")
                if (u.startsWith("http") && !found.contains(u)) found.add(u)
            }

            Regex("""(?:src|data-src)=["'](https?://[^"']+\.(?:m3u8|mp4|fd)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(playerHtml).forEach { m ->
                val u = m.groupValues[1].replace("\\/", "/")
                if (!found.contains(u)) found.add(u)
            }

            println("[FilmApik] $tag found ${found.size} url(s): ${found.take(5)}")
            if (found.isEmpty()) return false

            var any = false
            val referer = try { "${java.net.URI(playerUrl).scheme}://${java.net.URI(playerUrl).host}/" }
                catch (_: Throwable) { "$mainUrl/" }
            for (videoUrl in found.distinct()) {
                try {
                    val type = when {
                        videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - $serverName",
                            url = videoUrl,
                            type = type
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to UA,
                                "Referer" to referer,
                                "Origin" to referer.trimEnd('/')
                            )
                        }
                    )
                    any = true
                } catch (_: Throwable) {}
            }
            any
        } catch (t: Throwable) {
            println("[FilmApik] extractGeneric($tag) error: ${t.message}")
            false
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun b64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun b64UrlEncode(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun rand16(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b); return b64UrlEncode(b)
    }

    private fun hexDecode(s: String): ByteArray {
        val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) +
                      Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun u32LE(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun u32BE(v: Long) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )
    private fun u64LE(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        ((v shr 32) and 0xFF).toByte(), ((v shr 40) and 0xFF).toByte(),
        ((v shr 48) and 0xFF).toByte(), ((v shr 56) and 0xFF).toByte()
    )
    private fun u64BE(v: Long) = byteArrayOf(
        ((v shr 56) and 0xFF).toByte(), ((v shr 48) and 0xFF).toByte(),
        ((v shr 40) and 0xFF).toByte(), ((v shr 32) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun postJsonRaw(url: String, body: String, headers: Map<String, String>): String {
        return try {
            val mt = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder().url(url)
                .post(body.toRequestBody(mt))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            app.baseClient.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (t: Throwable) {
            println("[FilmApik] postJsonRaw error: ${t.message}"); ""
        }
    }

    private fun getRaw(url: String, headers: Map<String, String>): String {
        return try {
            val req = Request.Builder().url(url).get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            app.baseClient.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (t: Throwable) {
            println("[FilmApik] getRaw error: ${t.message}"); ""
        }
    }

    private fun hasLeadingZeroBits(hash: ByteArray, difficulty: Int): Boolean {
        var bitsLeft = difficulty
        for (b in hash) {
            if (bitsLeft <= 0) break
            val byte = b.toInt() and 0xFF
            if (bitsLeft >= 8) {
                if (byte != 0) return false
                bitsLeft -= 8
            } else {
                val mask = (0xFF shl (8 - bitsLeft)) and 0xFF
                if ((byte and mask) != 0) return false
                bitsLeft = 0
            }
        }
        return bitsLeft == 0
    }

    // ========================================================================
    // POW MULTI-FORMAT
    // ========================================================================

    private fun solveWithFormat(
        nonce: String, difficulty: Int,
        buildInput: (String, Long) -> ByteArray
    ): Long? {
        val md = MessageDigest.getInstance("SHA-256")
        var counter = 0L
        val limit = 15_000_000L
        while (counter < limit) {
            md.reset()
            val hash = md.digest(buildInput(nonce, counter))
            if (hasLeadingZeroBits(hash, difficulty)) return counter
            counter++
        }
        return null
    }

    private data class CaptchaData(val powNonce: String, val powToken: String, val powDiff: Int)

    private fun fetchCaptcha(
        apiBase: String, code: String,
        apiHeaders: Map<String, String>, fingerprint: JSONObject
    ): CaptchaData? {
        val body = JSONObject().apply { put("fingerprint", fingerprint) }.toString()
        val raw = postJsonRaw("$apiBase/api/videos/$code/embed/captcha", body, apiHeaders)
        val json = try { JSONObject(raw) } catch (_: Throwable) { return null }
        val nonce = json.optString("pow_nonce", "")
        val token = json.optString("pow_token", "")
        val diff = json.optInt("pow_difficulty", 16)
        if (nonce.isBlank() || token.isBlank()) return null
        return CaptchaData(nonce, token, diff)
    }

    // ========================================================================
    // KEY DERIVATION
    // ========================================================================

    private fun pickKeyPartsByVersion(keyParts: List<String>, version: String?): List<String> {
        val v = version?.trim() ?: return emptyList()
        val n = v.toIntOrNull() ?: return emptyList()
        if (n < 1 || n > 20) return emptyList()
        val idx1 = n; val idx2 = 31 - n; val size = keyParts.size
        if (idx1 < 1 || idx2 < 1 || idx1 > size || idx2 > size) return emptyList()
        val p1 = keyParts[idx1 - 1]; val p2 = keyParts[idx2 - 1]
        if (p1.isEmpty() || p2.isEmpty()) return emptyList()
        return listOf(p1, p2)
    }

    private fun buildKeyFromParts(parts: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        parts.forEach { p -> try { baos.write(b64UrlDecode(p)) } catch (_: Throwable) {} }
        return baos.toByteArray()
    }

    private fun tryAesGcmDecrypt(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray? {
        return try {
            if (key.size != 16 && key.size != 24 && key.size != 32) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(payload)
        } catch (_: Throwable) { null }
    }

    // ========================================================================
    // M3U8 URL EXTRACTION
    // ========================================================================

    private fun extractM3u8Url(plain: String): String? {
        var s = plain
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")

        s = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
            try { m.groupValues[1].toInt(16).toChar().toString() } catch (_: Throwable) { m.value }
        }

        try {
            val json = JSONObject(s)
            val found = mutableListOf<String>()
            fun walk(o: Any?) {
                when (o) {
                    is JSONObject -> o.keys().forEach { k -> walk(o.opt(k)) }
                    is JSONArray -> (0 until o.length()).forEach { i -> walk(o.opt(i)) }
                    is String -> if (o.contains(".m3u8")) found.add(o)
                }
            }
            walk(json)
            if (found.isNotEmpty()) return found.first()
        } catch (_: Throwable) {}

        return Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""").find(s)?.value
    }

    // ========================================================================
    // FILEMOON / BYSEQEKOAH EXTRACTOR
    // ========================================================================

    private suspend fun extractFilemoon(
        embedUrl: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val fixed = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
            val code = fixed.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) return false

            val host = try { java.net.URI(fixed).host } catch (_: Throwable) { return false }
            val playerBase = "https://$host"

            try { getRaw(fixed, mapOf("User-Agent" to UA)) } catch (_: Throwable) {}

            val embedHeaders = mapOf(
                "User-Agent" to UA, "accept" to "*/*",
                "x-embed-origin" to "filmapik.college",
                "x-embed-parent" to fixed,
                "x-embed-referer" to "$mainUrl/"
            )
            val detailsText = getRaw("$playerBase/api/videos/$code/embed/details", embedHeaders)
            val embedFrameUrl = try { JSONObject(detailsText).optString("embed_frame_url", "") }
                catch (_: Throwable) { "" }

            val apiBase = if (embedFrameUrl.isNotBlank()) {
                try { val u = java.net.URI(embedFrameUrl); "${u.scheme}://${u.host}" }
                catch (_: Throwable) { "https://$host" }
            } else "https://$host"

            println("[FilmApik] filemoon: apiBase=$apiBase code=$code")

            val apiHeaders = mapOf(
                "User-Agent" to UA, "accept" to "*/*",
                "content-type" to "application/json",
                "x-embed-origin" to "filmapik.college",
                "x-embed-parent" to fixed,
                "x-embed-referer" to "$mainUrl/"
            )

            val challengeJson = JSONObject(postJsonRaw("$apiBase/api/videos/access/challenge", "{}", apiHeaders))
            val challengeId = challengeJson.optString("challenge_id", "")
            val nonce = challengeJson.optString("nonce", "")
            if (challengeId.isBlank() || nonce.isBlank()) return false

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val pub = kp.public as ECPublicKey

            fun fixed32(v: BigInteger): ByteArray {
                val b = v.toByteArray()
                return when {
                    b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                    b.size < 32 -> ByteArray(32 - b.size) + b
                    else -> b
                }
            }

            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(kp.private)
            signer.update(nonce.toByteArray())
            val sig = b64UrlEncode(signer.sign())

            // FIX: kirim kosong sesuai capture asli (bukan random)
            val viewerId = ""
            val deviceId = ""

            val clientObj = JSONObject().apply {
                put("user_agent", UA)
                put("architecture", "x86"); put("bitness", "64")
                put("platform", "Windows"); put("platform_version", "10.0.0")
                put("model", ""); put("ua_full_version", "154.0.8037.93")
                put("brand_full_versions", JSONArray().apply {
                    put(JSONObject().apply { put("brand", "Chromium"); put("version", "154.0.8037.93") })
                    put(JSONObject().apply { put("brand", "Google Chrome"); put("version", "154.0.8037.93") })
                    put(JSONObject().apply { put("brand", "Not A(Brand"); put("version", "99.0.0.0") })
                })
                put("pixel_ratio", 1)
                put("screen_width", 1366); put("screen_height", 768)
                put("color_depth", 24)
                put("languages", JSONArray(listOf("en-US", "en", "id")))
                put("timezone", "Asia/Jakarta")
                put("hardware_concurrency", 2); put("device_memory", 16)
                put("touch_points", 0)
                put("webgl_vendor", "Google Inc. (Intel)")
                put("webgl_renderer", "ANGLE (Intel, Intel(R) HD Graphics (0x00000402) Direct3D11 vs_5_0 ps_5_0, D3D11)")
                put("canvas_hash", "YxgFI9NNQJBFA9bS1P_Ynao8KRnJeEKWQ-ujfsto5oI")
                put("audio_hash", "Q6FIrN6OYk4-8qILTIDrwcAVs_cBZ_9oao7-UJIYBF0")
                put("webgl_params_hash", "BIoy8SYxo8-2WtAM5ui9D8sDkpzLPvGjVdblDUiGQ-c")
                put("fonts_hash", "A3NvW7_xc4imEb2Z_dU5M6k6vDZTjWR7YiuZjLqys2o")
                put("codecs_hash", "qJye5DfMLC0co_nw835Vyx_VcUOEnA01Coov9OtwHZs")
                put("media_devices", "ai1ao1vi1")
                put("pointer_type", "fine,hover")
                put("extra", JSONObject().apply {
                    put("vendor", "Google Inc.")
                    put("appVersion", "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36")
                })
            }

            val attestBody = JSONObject().apply {
                put("viewer_id", viewerId); put("device_id", deviceId)
                put("challenge_id", challengeId); put("nonce", nonce)
                put("signature", sig)
                put("public_key", JSONObject().apply {
                    put("crv", "P-256"); put("ext", true)
                    put("key_ops", JSONArray(listOf("verify")))
                    put("kty", "EC")
                    put("x", b64UrlEncode(fixed32(pub.w.affineX)))
                    put("y", b64UrlEncode(fixed32(pub.w.affineY)))
                })
                put("client", clientObj)
                put("storage", JSONObject())
                put("attributes", JSONObject().apply { put("entropy", "high") })
            }.toString()

            val attestJson = JSONObject(postJsonRaw("$apiBase/api/videos/access/attest", attestBody, apiHeaders))
            val fpToken = attestJson.optString("token", "")
            val realViewer = attestJson.optString("viewer_id", viewerId)
            val realDevice = attestJson.optString("device_id", deviceId)
            if (fpToken.isBlank()) {
                println("[FilmApik] filemoon: no attest token, dump=${attestJson.toString().take(300)}")
                return false
            }

            val fingerprint = JSONObject().apply {
                put("token", fpToken)
                put("viewer_id", realViewer)
                put("device_id", realDevice)
                put("confidence", 0.77)
            }

            // === MULTI-FORMAT PoW ===
            val formats = listOf(
                Triple("str_cat",
                    { n: String, c: Long -> (n + c.toString()).toByteArray() },
                    { c: Long -> c.toString() }),
                Triple("hex_nonce_str_cat",
                    { n: String, c: Long -> hexDecode(n) + c.toString().toByteArray() },
                    { c: Long -> c.toString() }),
                Triple("hex_nonce_u32le",
                    { n: String, c: Long -> hexDecode(n) + u32LE(c) },
                    { c: Long -> c.toString() }),
                Triple("hex_nonce_u32be",
                    { n: String, c: Long -> hexDecode(n) + u32BE(c) },
                    { c: Long -> c.toString() }),
                Triple("hex_nonce_u64le",
                    { n: String, c: Long -> hexDecode(n) + u64LE(c) },
                    { c: Long -> c.toString() }),
                Triple("hex_nonce_u64be",
                    { n: String, c: Long -> hexDecode(n) + u64BE(c) },
                    { c: Long -> c.toString() }),
                Triple("str_cat_colon",
                    { n: String, c: Long -> "$n:$c".toByteArray() },
                    { c: Long -> c.toString() }),
                Triple("hex_nonce_hex_sol",
                    { n: String, c: Long -> hexDecode(n) + c.toString().toByteArray() },
                    { c: Long -> java.lang.Long.toHexString(c) }),
                Triple("str_cat_hex_sol",
                    { n: String, c: Long -> (n + c.toString()).toByteArray() },
                    { c: Long -> java.lang.Long.toHexString(c) })
            )

            var captchaToken: String? = null

            for ((formatName, buildInput, buildSolution) in formats) {
                val captcha = fetchCaptcha(apiBase, code, apiHeaders, fingerprint)
                if (captcha == null) {
                    println("[FilmApik] filemoon: [$formatName] captcha fetch failed")
                    continue
                }

                val counter = withContext(Dispatchers.Default) {
                    solveWithFormat(captcha.powNonce, captcha.powDiff, buildInput)
                }
                if (counter == null) {
                    println("[FilmApik] filemoon: [$formatName] timeout")
                    continue
                }

                val solutionStr = buildSolution(counter)

                val verifyBody = JSONObject().apply {
                    put("pow_token", captcha.powToken)
                    put("solution", solutionStr)
                    put("fingerprint", fingerprint)
                }.toString()

                val verifyRaw = postJsonRaw(
                    "$apiBase/api/videos/$code/embed/captcha/verify",
                    verifyBody, apiHeaders
                )

                val verifyJson = try { JSONObject(verifyRaw) } catch (_: Throwable) { continue }
                val token = verifyJson.optString("token", "")
                val status = verifyJson.optString("status", "")

                if (token.isNotBlank()) {
                    println("[FilmApik] filemoon: >>> OK format=$formatName counter=$counter")
                    captchaToken = token
                    break
                }
                println("[FilmApik] filemoon: [$formatName] fail status=$status counter=$counter")
                delay(250)
            }

            if (captchaToken == null) {
                println("[FilmApik] filemoon: ALL FORMATS FAILED")
                return false
            }

            // === PLAYBACK ===
            val playbackBody = JSONObject().apply { put("fingerprint", fingerprint) }.toString()
            val playbackRaw = postJsonRaw(
                "$apiBase/api/videos/$code/embed/playback",
                playbackBody, apiHeaders + mapOf("x-captcha-token" to captchaToken)
            )

            val playbackJson = JSONObject(playbackRaw).optJSONObject("playback") ?: return false
            val version = playbackJson.optString("version", "")
            val iv = b64UrlDecode(playbackJson.optString("iv"))
            val payload = b64UrlDecode(playbackJson.optString("payload"))
            val kpArr = playbackJson.optJSONArray("key_parts") ?: return false
            val allParts = (0 until kpArr.length()).map { kpArr.getString(it) }

            println("[FilmApik] filemoon: version=$version, parts=${allParts.size}")

            var plainStr: String? = null
            val picked = pickKeyPartsByVersion(allParts, version)
            if (picked.isNotEmpty()) {
                val key = buildKeyFromParts(picked)
                val pt = tryAesGcmDecrypt(key, iv, payload)
                if (pt != null) {
                    plainStr = String(pt, Charsets.UTF_8)
                    println("[FilmApik] filemoon: DECRYPT OK (v=$version)")
                }
            }
            if (plainStr == null) {
                for (n in 1..20) {
                    val pair = pickKeyPartsByVersion(allParts, n.toString())
                    if (pair.isEmpty()) continue
                    val key = buildKeyFromParts(pair)
                    val pt = tryAesGcmDecrypt(key, iv, payload) ?: continue
                    plainStr = String(pt, Charsets.UTF_8)
                    println("[FilmApik] filemoon: DECRYPT OK (pair n=$n)")
                    break
                }
            }
            if (plainStr == null) return false

            val m3u8 = extractM3u8Url(plainStr) ?: run {
                println("[FilmApik] filemoon: no m3u8 in: ${plainStr.take(500)}")
                return false
            }

            println("[FilmApik] filemoon: m3u8 = $m3u8")

            val apiHost = try { java.net.URI(apiBase).host } catch (_: Throwable) { "f7hyg4q.org" }

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name - $serverName",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://$apiHost/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "https://$apiHost/"
                    )
                }
            )
            true
        } catch (t: Throwable) {
            println("[FilmApik] extractFilemoon error: ${t.message}")
            false
        }
    }
}
