package com.ansrizal.filmapik

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private val fullHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "max-age=0",
        "DNT" to "1",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "sec-ch-ua" to "\"Chromium\";v=\"128\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"128\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Referer" to "$mainUrl/"
    )

    private suspend fun request(url: String): NiceResponse {
        return app.get(
            url,
            headers = headers,
            interceptor = turnstileInterceptor,
            timeout = 60
        )
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
        "category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            val data = request.data.removeSuffix("/")
            if (data.isEmpty()) {
                "$mainUrl/latest/page/$page/"
            } else {
                "$mainUrl/$data/page/$page/"
            }
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
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val linkElement = if (this.tagName() == "a") this else this.selectFirst("a")
        val href = fixUrl(linkElement?.attr("href") ?: return null)
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/category/") || href.contains("/release-year/")) return null

        val img = this.selectFirst("img")
        val srcset = img?.attr("srcset") ?: img?.attr("data-srcset")
        val posterUrl = fixUrlNull(
            if (!srcset.isNullOrBlank()) {
                srcset.split(",").last().trim().split(" ").first()
            } else {
                img?.attr("abs:data-src") ?: img?.attr("abs:src") ?: img?.attr("src")
            }
        )

        val isSeries = href.contains("/tvshows/") || href.contains("/series/") || href.contains("/tv/") || href.contains("/tv-series/") || href.contains("/episodes/")
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

        val isSeries = url.contains("/tvshows/") || url.contains("/series/") || url.contains("/tv/") ||
                url.contains("/tv-series/") || url.contains("/episodes/") ||
                document.selectFirst(".episodios, .list-episode, .eplister, #episodes-list, .famv-episodes, .famv-season-list, .famv-episode-btn") != null

        val isAnime = url.contains("anime") || document.select(".badge-year, .prose").text().contains("anime", true)

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        println("[FilmApik] loadLinks for $data")

        val htmlFull = try {
            app.get(data, headers = fullHeaders, interceptor = turnstileInterceptor, timeout = 60).text
        } catch (t: Throwable) {
            println("[FilmApik] full-header request failed: ${t.message}")
            ""
        }

        println("[FilmApik] HTML length=${htmlFull.length}")
        println("[FilmApik] has famvServers=${htmlFull.contains("famvServers")}")
        println("[FilmApik] has player-section=${htmlFull.contains("player-section")}")
        println("[FilmApik] has player-main=${htmlFull.contains("player-main")}")
        println("[FilmApik] has byseqekaho=${htmlFull.contains("byseqekaho")}")
        println("[FilmApik] has iframe=${htmlFull.contains("<iframe")}")

        var html = htmlFull

        if (!html.contains("famvServers") && html.length < 50000) {
            println("[FilmApik] retry without interceptor")
            html = try {
                app.get(data, headers = fullHeaders, timeout = 60).text
            } catch (t: Throwable) {
                println("[FilmApik] no-interceptor failed: ${t.message}")
                html
            }
            println("[FilmApik] no-interceptor length=${html.length}")
        }

        if (!html.contains("famvServers")) {
            println("[FilmApik] retry with mobile UA")
            val mobileUA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            val mobileHeaders = fullHeaders.toMutableMap().apply {
                put("User-Agent", mobileUA)
                put("sec-ch-ua-mobile", "?1")
                put("sec-ch-ua-platform", "\"Android\"")
                put("Sec-Fetch-Site", "none")
            }
            html = try {
                app.get(data, headers = mobileHeaders, interceptor = turnstileInterceptor, timeout = 60).text
            } catch (t: Throwable) {
                html
            }
            println("[FilmApik] mobile length=${html.length}")
        }

        if (!html.contains("famvServers") && !data.contains("?")) {
            println("[FilmApik] retry with nocache param")
            html = try {
                app.get("$data?nocache=${System.currentTimeMillis()}", headers = fullHeaders, interceptor = turnstileInterceptor, timeout = 60).text
            } catch (t: Throwable) {
                html
            }
            println("[FilmApik] nocache length=${html.length}")
        }

        val slug = data.trimEnd('/').substringAfterLast('/')
        println("[FilmApik] slug=$slug")

        if (!html.contains("famvServers") && slug.isNotBlank()) {
            println("[FilmApik] trying WP REST API for slug=$slug")
            try {
                val wpUrl = "$mainUrl/wp-json/wp/v2/posts?slug=$slug&_fields=content"
                val wpText = app.get(wpUrl, headers = fullHeaders, interceptor = turnstileInterceptor, timeout = 60).text
                println("[FilmApik] WP API length=${wpText.length}")
                if (wpText.contains("famvServers") || wpText.contains("byseqekaho")) {
                    html = wpText
                    println("[FilmApik] WP API has player data!")
                }
            } catch (t: Throwable) {
                println("[FilmApik] WP API failed: ${t.message}")
            }
        }

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
                u.contains("s10.histats")
            ) return
            if (!seen.add(u)) return
            playerUrls.add(name to u)
        }

        Regex("""window\.famvServers\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { serversJson ->
                println("[FilmApik] famvServers found, length=${serversJson.length}")
                Regex(""""url"\s*:\s*"([^"]+)"""").findAll(serversJson).forEach { m ->
                    addPlayer("player", m.groupValues[1])
                }
            }

        if (playerUrls.isEmpty() && html.isNotBlank()) {
            try {
                val doc = Jsoup.parse(html)
                doc.select("a[data-url], a.player-option, a.famv-server-btn, #player-list a, .player-option").forEach { a ->
                    val url = a.attr("data-url").ifBlank { a.attr("href") }
                    val name = a.attr("data-server").ifBlank { a.text().trim() }
                    addPlayer(name.ifBlank { "player" }, url)
                }
                if (playerUrls.isEmpty()) {
                    doc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                        addPlayer("iframe", src)
                    }
                }
            } catch (_: Throwable) {}
        }

        if (playerUrls.isEmpty()) {
            println("[FilmApik] brutal regex fallback")
            val knownPlayers = listOf(
                "byseqekaho", "f7hyg4q", "strp2p", "abyssplayer", "efek.stream",
                "filemoon", "streamwish", "mixdrop", "doodstream", "voe.sx",
                "vidoza", "upstream", "filelions", "mp4upload", "streamtape",
                "turbovidhls", "netu", "waaw", "buzzheavier"
            )
            Regex("""https?://[^\s"'<>\\]+""").findAll(html).forEach { m ->
                val u = m.value
                if (knownPlayers.any { u.contains(it, ignoreCase = true) }) {
                    addPlayer("regex", u)
                }
            }
        }

        println("[FilmApik] Found ${playerUrls.size} player(s): $playerUrls")

        for ((serverName, playerUrl) in playerUrls) {
            try {
                when {
                    playerUrl.contains("byseqekaho.com") || playerUrl.contains("f7hyg4q.org") -> {
                        println("[FilmApik] Custom byseqekaho: $serverName -> $playerUrl")
                        if (extractByseqekaho(playerUrl, serverName, callback)) found = true
                    }
                    else -> {
                        println("[FilmApik] Built-in: $serverName -> $playerUrl")
                        if (loadExtractor(fixUrl(playerUrl), subtitleCallback, callback)) found = true
                    }
                }
            } catch (t: Throwable) {
                println("[FilmApik] Player $serverName failed: ${t.message}")
            }
        }

        return found
    }

    private fun b64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun b64UrlEncode(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun rand16(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b64UrlEncode(b)
    }

    private fun solvePowSync(powToken: String, difficulty: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val fullBytes = difficulty / 8
        val extraBits = difficulty % 8
        var nonce = 0L
        while (nonce < 50_000_000L) {
            md.reset()
            md.update(powToken.toByteArray())
            md.update(nonce.toString().toByteArray())
            val hash = md.digest()
            var ok = true
            for (i in 0 until fullBytes) {
                if (hash[i].toInt() != 0) { ok = false; break }
            }
            if (ok && extraBits > 0) {
                val mask = (0xFF shl (8 - extraBits)) and 0xFF
                if ((hash[fullBytes].toInt() and mask) != 0) ok = false
            }
            if (ok) return nonce.toString()
            nonce++
        }
        return "0"
    }

    private fun postJsonRaw(url: String, body: String, headers: Map<String, String>): String {
        return try {
            val mt = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody(mt))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            app.baseClient.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (t: Throwable) {
            println("[FilmApik] postJsonRaw error: ${t.message}")
            ""
        }
    }

    private fun getRaw(url: String, headers: Map<String, String>): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            app.baseClient.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (t: Throwable) {
            println("[FilmApik] getRaw error: ${t.message}")
            ""
        }
    }

    private fun pickKeyPartsByVersion(keyParts: List<String>, version: String?): List<String> {
        val v = version?.trim() ?: return emptyList()
        val n = v.toIntOrNull() ?: return emptyList()
        if (n < 1 || n > 20) return emptyList()

        val idx1 = n
        val idx2 = 31 - n
        val size = keyParts.size

        if (idx1 < 1 || idx2 < 1 || idx1 > size || idx2 > size) return emptyList()

        val part1 = keyParts[idx1 - 1]
        val part2 = keyParts[idx2 - 1]

        if (part1.isEmpty() || part2.isEmpty()) return emptyList()
        return listOf(part1, part2)
    }

    private fun buildKeyFromParts(parts: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        parts.forEach { p ->
            try {
                baos.write(b64UrlDecode(p))
            } catch (t: Throwable) {
                println("[FilmApik] b64 decode failed for part: ${t.message}")
            }
        }
        return baos.toByteArray()
    }

    private fun tryAesGcmDecrypt(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray? {
        return try {
            if (key.size != 16 && key.size != 24 && key.size != 32) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )
            cipher.doFinal(payload)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun extractByseqekaho(
        embedUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val fixed = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
            val code = fixed.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) return false

            val host = try { java.net.URI(fixed).host } catch (_: Throwable) { return false }
            val playerBase = "https://$host"

            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

            try { getRaw(fixed, mapOf("User-Agent" to ua)) } catch (_: Throwable) {}

            val embedHeaders = mapOf(
                "User-Agent" to ua,
                "accept" to "*/*",
                "x-embed-origin" to "filmapik.college",
                "x-embed-parent" to fixed,
                "x-embed-referer" to "$mainUrl/"
            )
            val detailsText = getRaw("$playerBase/api/videos/$code/embed/details", embedHeaders)
            val embedFrameUrl = try {
                JSONObject(detailsText).optString("embed_frame_url", "")
            } catch (_: Throwable) { "" }

            val apiBase = if (embedFrameUrl.isNotBlank()) {
                try {
                    val u = java.net.URI(embedFrameUrl)
                    "${u.scheme}://${u.host}"
                } catch (_: Throwable) { "https://$host" }
            } else "https://$host"

            println("[FilmApik] byseqekaho: apiBase=$apiBase code=$code")

            val apiHeaders = mapOf(
                "User-Agent" to ua,
                "accept" to "*/*",
                "content-type" to "application/json",
                "x-embed-origin" to "filmapik.college",
                "x-embed-parent" to fixed,
                "x-embed-referer" to "$mainUrl/"
            )

            val challengeJson = JSONObject(postJsonRaw("$apiBase/api/videos/access/challenge", "{}", apiHeaders))
            val challengeId = challengeJson.optString("challenge_id", "")
            val nonce = challengeJson.optString("nonce", "")
            if (challengeId.isBlank() || nonce.isBlank()) {
                println("[FilmApik] byseqekaho: challenge invalid")
                return false
            }

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

            val viewerId = rand16()
            val deviceId = rand16()

            val clientObj = JSONObject().apply {
                put("user_agent", ua)
                put("architecture", "x86")
                put("bitness", "64")
                put("platform", "Windows")
                put("platform_version", "10.0.0")
                put("model", "")
                put("ua_full_version", "128.0.0.0")
                put("brand_full_versions", JSONArray().apply {
                    put(JSONObject().apply {
                        put("brand", "Google Chrome")
                        put("version", "128.0.0.0")
                    })
                })
                put("pixel_ratio", 1)
                put("screen_width", 1366)
                put("screen_height", 768)
                put("color_depth", 24)
                put("languages", JSONArray(listOf("en-GB", "en-US", "en")))
                put("timezone", "Asia/Jakarta")
                put("hardware_concurrency", 2)
                put("device_memory", 8)
                put("touch_points", 0)
                put("webgl_vendor", "Google Inc. (Intel)")
                put("webgl_renderer", "ANGLE (Intel, Intel(R) HD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)")
            }

            val attestBody = JSONObject().apply {
                put("viewer_id", viewerId)
                put("device_id", deviceId)
                put("challenge_id", challengeId)
                put("nonce", nonce)
                put("signature", sig)
                put("public_key", JSONObject().apply {
                    put("crv", "P-256")
                    put("ext", true)
                    put("key_ops", JSONArray(listOf("verify")))
                    put("kty", "EC")
                    put("x", b64UrlEncode(fixed32(pub.w.affineX)))
                    put("y", b64UrlEncode(fixed32(pub.w.affineY)))
                })
                put("client", clientObj)
                put("storage", JSONObject().apply {
                    put("cookie", viewerId)
                    put("local_storage", viewerId)
                    put("indexed_db", "$viewerId:$deviceId")
                    put("cache_storage", "$viewerId:$deviceId")
                })
                put("attributes", JSONObject().apply {
                    put("entropy", "high")
                })
            }.toString()

            val attestJson = JSONObject(postJsonRaw("$apiBase/api/videos/access/attest", attestBody, apiHeaders))
            val fpToken = attestJson.optString("token", "")
            val realViewer = attestJson.optString("viewer_id", viewerId)
            val realDevice = attestJson.optString("device_id", deviceId)
            if (fpToken.isBlank()) {
                println("[FilmApik] byseqekaho: attest failed")
                return false
            }

            val fingerprint = JSONObject().apply {
                put("token", fpToken)
                put("viewer_id", realViewer)
                put("device_id", realDevice)
                put("confidence", 0.95)
            }

            val captchaBody = JSONObject().apply { put("fingerprint", fingerprint) }.toString()
            val captchaJson = JSONObject(postJsonRaw("$apiBase/api/videos/$code/embed/captcha", captchaBody, apiHeaders))
            val powToken = captchaJson.optString("pow_token", "")
            val powDiff = captchaJson.optInt("pow_difficulty", 16)
            if (powToken.isBlank()) {
                println("[FilmApik] byseqekaho: captcha invalid")
                return false
            }

            val solution = withContext(Dispatchers.Default) { solvePowSync(powToken, powDiff) }
            println("[FilmApik] byseqekaho: PoW nonce=$solution")

            val verifyBody = JSONObject().apply {
                put("pow_token", powToken)
                put("solution", solution)
                put("fingerprint", fingerprint)
            }.toString()
            val verifyJson = JSONObject(postJsonRaw("$apiBase/api/videos/$code/embed/captcha/verify", verifyBody, apiHeaders))
            val captchaToken = verifyJson.optString("token", "")
            if (captchaToken.isBlank()) {
                println("[FilmApik] byseqekaho: captcha verify failed")
                return false
            }

            val playbackBody = JSONObject().apply { put("fingerprint", fingerprint) }.toString()
            val playbackRoot = JSONObject(postJsonRaw(
                "$apiBase/api/videos/$code/embed/playback",
                playbackBody,
                apiHeaders + mapOf("x-captcha-token" to captchaToken)
            ))
            val playbackJson = playbackRoot.optJSONObject("playback") ?: run {
                println("[FilmApik] byseqekaho: no playback object")
                return false
            }

            val version = playbackJson.optString("version", "")
            val iv = b64UrlDecode(playbackJson.optString("iv"))
            val payload = b64UrlDecode(playbackJson.optString("payload"))
            val kpArr = playbackJson.optJSONArray("key_parts") ?: run {
                println("[FilmApik] byseqekaho: no key_parts")
                return false
            }
            val allParts = (0 until kpArr.length()).map { kpArr.getString(it) }
            println("[FilmApik] byseqekaho: version=$version, parts=${allParts.size}")

            var plainStr: String? = null

            val pickedParts = pickKeyPartsByVersion(allParts, version)
            println("[FilmApik] byseqekaho: picked ${pickedParts.size} parts")
            if (pickedParts.isNotEmpty()) {
                val key = buildKeyFromParts(pickedParts)
                println("[FilmApik] byseqekaho: derived key length=${key.size}")
                val pt = tryAesGcmDecrypt(key, iv, payload)
                if (pt != null) {
                    plainStr = String(pt, Charsets.UTF_8)
                    println("[FilmApik] byseqekaho: DECRYPT OK (version-based)")
                }
            }

            if (plainStr == null) {
                println("[FilmApik] byseqekaho: trying all version pairs")
                for (n in 1..20) {
                    val pair = pickKeyPartsByVersion(allParts, n.toString())
                    if (pair.isEmpty()) continue
                    val key = buildKeyFromParts(pair)
                    val pt = tryAesGcmDecrypt(key, iv, payload) ?: continue
                    plainStr = String(pt, Charsets.UTF_8)
                    println("[FilmApik] byseqekaho: DECRYPT OK (n=$n)")
                    break
                }
            }

            if (plainStr == null) {
                println("[FilmApik] byseqekaho: brute-forcing all pairs")
                outer@ for (i in allParts.indices) {
                    for (j in allParts.indices) {
                        if (i == j) continue
                        val key = buildKeyFromParts(listOf(allParts[i], allParts[j]))
                        val pt = tryAesGcmDecrypt(key, iv, payload) ?: continue
                        plainStr = String(pt, Charsets.UTF_8)
                        println("[FilmApik] byseqekaho: DECRYPT OK (i=$i j=$j)")
                        break@outer
                    }
                }
            }

            if (plainStr == null) {
                println("[FilmApik] byseqekaho: ALL DECRYPT FAILED")
                return false
            }

            val m3u8Regex = Regex("""https?://[^\s"'\\]+?\.m3u8[^\s"'\\]*""")
            val m3u8 = m3u8Regex.find(plainStr)?.value?.replace("\\/", "/") ?: run {
                println("[FilmApik] byseqekaho: no m3u8 in plaintext: ${plainStr.take(300)}")
                return false
            }

            println("[FilmApik] byseqekaho: m3u8=$m3u8")

            val apiHost = try { java.net.URI(apiBase).host } catch (_: Throwable) { host }

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
                        "User-Agent" to ua,
                        "Referer" to "https://$apiHost/",
                        "Origin" to "https://$apiHost"
                    )
                }
            )
            return true
        } catch (t: Throwable) {
            println("[FilmApik] extractByseqekaho error: ${t.message}")
            return false
        }
    }
}
