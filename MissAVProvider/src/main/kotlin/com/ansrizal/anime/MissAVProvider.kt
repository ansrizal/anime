package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws" // Base URL, mungkin perlu diupdate jika domain berubah
    override var name = "MissAV"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "id/new" to "Recent Update",
        "id/release" to "Keluaran Terbaru",
        "id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "id/today-hot" to "Paling Banyak Dilihat Hari Ini",
        "id/weekly-hot" to "Paling Banyak Dilihat Per Minggu",
        "id/monthly-hot" to "Paling Banyak Dilihat Berdasarkan Bulan",
        "id/genres/Hd" to "Hd",
        "id/genres/Exclusive" to "Exclusive",
        "id/genres/Creampie" to "Creampie",
        "id/genres/Individual" to "Individual",
        "id/genres/Big%20Breasts" to "Big Breasts",
        "id/genres/Wife" to "Wife",
        "id/genres/Mature%20Woman" to "Mature Woman",
        "id/genres/Ordinary%20Person" to "Ordinary Person",
        "id/genres/Pretty%20Girl" to "Pretty Girl",
        "id/genres/Oral%20Sex" to "Oral Sex",
        "id/genres/Orgy" to "Orgy",
        "id/genres/Ride" to "Ride",
        "id/genres/Slim%20Pixelated" to "Slim Pixelated",
        "id/genres/Slut" to "Slut",
        "id/genres/4%20Hours%20Or%20More" to "4 Hours Or More",
        "id/genres/High%20School%20Girl" to "High School Girl",
        "id/genres/Squirting" to "Squirting",
        "id/genres/Slim" to "Slim",
        "id/genres/Selfie" to "Selfie",
        "id/genres/Tit%20Job" to "Tit Job",
        "id/genres/Collection" to "Collection",
        "id/genres/Beautiful%20Breasts" to "Beautiful Breasts",
        "id/genres/Ntr" to "Ntr",
        "id/genres/Fetish" to "Fetish",
        "id/genres/Planning" to "Planning",
        "id/genres/Incest" to "Incest",
        "id/genres/Hit%20On%20Girls" to "Hit On Girls",
        "id/genres/Bukkake" to "Bukkake",
        "id/genres/Promiscuous" to "Promiscuous",
        "id/genres/Sneak%20Shots" to "Sneak Shots",
        "id/genres/4K" to "4K",
        "id/genres/Plot" to "Plot",
        "id/genres/Masturbate" to "Masturbate",
        "id/genres/Masturbation" to "Masturbation",
        "id/genres/Sister" to "Sister",
        "id/genres/Humiliation" to "Humiliation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        // Cache main page for 60 minutes
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/"), cacheTime = 60).document
        val home = document.select("div.thumbnail").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.text-secondary") ?: selectFirst("a[href*='/id/']") ?: return null
        val title = a.text().trim().ifEmpty { selectFirst("img")?.attr("alt") } ?: return null
        val href = fixUrl(a.attr("href"))
        val posterUrl = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/id/search/${query.replace(" ", "%20")}"
        // Cache search results for 30 minutes
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/"), cacheTime = 30).document
        return document.select("div.thumbnail").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Cache movie details for 1 day
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/"), cacheTime = 60 * 24).document
        val title = document.selectFirst("h1.text-base")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: ""

        val poster = document.selectFirst("video.player")?.attr("data-poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = document.selectFirst("div.mb-4 .text-secondary")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val tags = document.select("a[href*='/genres/'], a[href*='/uncensored-leak']").map { it.text().trim() }
        val actresses = document.select("a[href*='/actresses/']").map { it.text().trim() }

        val year = document.selectFirst("time")?.attr("datetime")?.split("-")?.firstOrNull()?.toIntOrNull()

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = (tags + actresses).distinct()
            this.year = year
            addEpisodes(DubStatus.Subbed, listOf(newEpisode(url) {
                this.name = title
            }))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ambil response HTML dari halaman video
        val response = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).text

        // --- Pendekatan Baru: Ekstrak tautan dari JavaScript ---

        // 1. Cari blok script yang berisi variabel 'source'
        // Pattern ini akan mencocokkan "source='...'" atau 'source="..."' yang berada di dalam tag <script>
        val scriptRegex = Regex("<script[^>]*>.*?(source\\s*=\\s*['\"])(.*?)(['\"])", RegexOption.DOT_MATCHES_ALL)
        val scriptMatch = scriptRegex.find(response)

        if (scriptMatch != null) {
            // Kita sudah menemukan blok script, sekarang ekstrak semua tautan .m3u8 dari dalamnya
            // Karena kita mencari tautan, kita bisa gunakan pattern yang lebih sederhana
            val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            val allLinks = m3u8Regex.findAll(scriptMatch.value).map { it.value }.toList()

            // Filter link untuk mendapatkan yang terbaik (prioritas: 1280x720 > 842x480 > playlist)
            // Urutan prioritas ini bisa disesuaikan
            val prioritizedLinks = allLinks.sortedByDescending { link ->
                when {
                    link.contains("1280x720") -> 3
                    link.contains("842x480") -> 2
                    link.contains("playlist") -> 1
                    else -> 0
                }
            }

            if (prioritizedLinks.isNotEmpty()) {
                // Gunakan link dengan prioritas tertinggi
                val bestLink = prioritizedLinks.first()
                var quality = Qualities.Unknown.value
                val name = when {
                    bestLink.contains("1280x720") -> "Surrit 720p"
                    bestLink.contains("842x480") -> "Surrit 480p"
                    else -> "Surrit Auto"
                }

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        name,
                        bestLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
                return true
            }
        }

        // --- Pendekatan Cadangan (Fallback) ---
        // Jika pendekatan utama gagal, coba cari tautan langsung di HTML (mungkin untuk kasus tertentu)
        val fallbackM3u8 = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8.*?)["']""").find(response)?.groupValues?.get(1)
            ?: Regex("""["']?source["']?\s*:\s*["'](https?://[^"']+\.m3u8.*?)["']""").find(response)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(response)?.groupValues?.get(1)

        if (fallbackM3u8 != null) {
            callback.invoke(
                newExtractorLink(
                    name,
                    "Surrit (Fallback)",
                    fallbackM3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        // Jika semua gagal, tidak ada link ditemukan
        return false
    }
}
