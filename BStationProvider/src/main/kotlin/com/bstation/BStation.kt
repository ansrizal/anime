package com.bstation

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
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private val apiUrl = "https://api.bilibili.tv"
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "Referer" to "https://www.bilibili.tv/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://www.bilibili.tv"
    )

    // ================================================================
    //  HELPER — bersihkan URL gambar
    //  Bilibili CDN pakai format: //i0.hdslb.com/xxx.jpg@480w_270h_1c
    //  Kita harus: tambah https:, buang @suffix
    // ================================================================
    private fun String?.cleanImageUrl(): String? {
        if (this.isNullOrBlank()) return null
        var u = this.substringBefore("@").substringBefore(" ").trim()
        u = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> u
        }
        return u.ifBlank { null }
    }

    // ================================================================
    //  HELPER — parse kartu video dari HTML
    // ================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        // Cari link ke /play/ atau /video/
        val a = if (this.tagName() == "a") this
        else this.selectFirst("a[href*=/play/], a[href*=/video/]")
        ?: return null

        val rawHref = a.attr("href").ifBlank { return null }
        val href = when {
            rawHref.startsWith("//") -> "https:$rawHref"
            rawHref.startsWith("/") -> "$mainUrl$rawHref"
            else -> rawHref
        }
        if (!href.contains("/play/") && !href.contains("/video/")) return null

        // Title: coba dari img.alt, lalu berbagai class
        val img = a.selectFirst("img") ?: this.selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: a.selectFirst(".bstar-video-card__title")?.text()?.ifBlank { null }
            ?: a.selectFirst(".card-title")?.text()?.ifBlank { null }
            ?: a.selectFirst("h3, .title")?.text()?.ifBlank { null }
            ?: a.attr("title").ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null

        // Poster
        val poster = img?.attr("src").cleanImageUrl()
            ?: img?.attr("data-src").cleanImageUrl()
            ?: a.selectFirst("source")?.attr("srcset")?.cleanImageUrl()

        // Tipe: /play/ = anime/series (PGC), /video/ = UGC (movie)
        val type = if (href.contains("/play/")) TvType.Anime else TvType.Movie

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ================================================================
    //  MAIN PAGE
    // ================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/id/" to "Populer",
        "$mainUrl/id/anime" to "Anime",
        "$mainUrl/id/trending" to "Trending",
        "$mainUrl/id/short-drama" to "Dracin",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        // Ambil semua elemen yang punya link ke /play/ atau /video/
        val items = document.select("a[href*=/play/], a[href*=/video/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (items.isNotEmpty()) {
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, false)
    }

    // ================================================================
    //  SEARCH — pakai SSR HTML
    // ================================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/id/search-result?q=${query.replace(" ", "%20")}"
        val document = app.get(url).document

        val items = document.select("a[href*=/play/], a[href*=/video/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return items.toNewSearchResponseList()
    }

    // ================================================================
    //  LOAD — ambil detail + daftar episode
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content").cleanImageUrl()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst(".video-info-desc, .desc, .bstar-meta__desc")?.text()

        // Parse URL: /play/{season_id}/{episode_id}
        val playMatch = Regex("""/play/(\d+)/(\d+)""").find(url)

        if (playMatch != null) {
            val seasonId = playMatch.groupValues[1]
            val currentEpId = playMatch.groupValues[2]

            // Panggil API series untuk daftar episode
            val seriesUrl = "$apiUrl/intl/gateway/web/v2/ogv/play/series" +
                    "?s_locale=id_ID&platform=web&season_id=$seasonId"
            val seriesResponse = try {
                app.get(seriesUrl, headers = apiHeaders).parsedSafe<SeriesResponse>()
            } catch (e: Exception) { null }

            val episodes = mutableListOf<Episode>()
            seriesResponse?.data?.sections?.forEach { section ->
                section.episodes?.forEach { ep ->
                    val epId = ep.episodeId
                    if (epId != null) {
                        episodes.add(newEpisode(epId.toString()) {
                            this.name = ep.longTitle
                                ?: ep.title
                                ?: "Episode ${ep.episodeNumber ?: ""}"
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.cover.cleanImageUrl()
                        })
                    }
                }
            }

            // Fallback: kalau API gagal, minimal 1 episode (yang sedang dibuka)
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(currentEpId) {
                    this.name = "Episode 1"
                    this.episode = 1
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Single UGC video: /video/{aid}
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ================================================================
    //  LOAD LINKS — panggil /playurl API
    // ================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = episode_id (string angka) atau aid
        val episodeId = data.toLongOrNull()
            ?: Regex("""(\d+)""").find(data)?.groupValues?.get(1)?.toLongOrNull()
            ?: return false

        // 1. Ambil play URL
        val playUrl = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&ep_id=$episodeId" +
                "&tk=&qn=64&type=0&device=wap&tf=0&fnval=1"
        val response = try {
            app.get(playUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()
        } catch (e: Exception) { null }

        var anyLink = false

        // 2. Coba durl (FLV - muxed audio+video, langsung playable)
        response?.data?.durl?.forEach { item ->
            val url = item.url ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            anyLink = true

            // Backup URLs
            item.backupUrl?.forEach { backup ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "${this.name} (backup)",
                        backup,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // 3. Fallback: dash (video-only, butuh audio terpisah — player modern bisa handle)
        if (response?.data?.durl.isNullOrEmpty()) {
            response?.data?.dash?.video?.forEach { video ->
                val url = video.baseUrl ?: video.base_url ?: return@forEach
                val quality = when (video.id) {
                    80 -> Qualities.P1080.value
                    64 -> Qualities.P720.value
                    32 -> Qualities.P480.value
                    16 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "${this.name} ${video.id ?: ""}P",
                        url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
                anyLink = true
            }
        }

        // 4. Subtitle (opsional)
        try {
            val subUrl = "$apiUrl/intl/gateway/web/v2/subtitle" +
                    "?s_locale=id_ID&platform=web&episode_id=$episodeId"
            app.get(subUrl, headers = apiHeaders)
                .parsedSafe<SubtitleResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val u = sub.url ?: return@forEach
                    subtitleCallback.invoke(
                        newSubtitleFile(sub.lan ?: "Unknown", u)
                    )
                }
        } catch (_: Exception) { }

        return anyLink
    }

    // ================================================================
    //  DATA CLASSES
    // ================================================================
    data class SeriesResponse(@JsonProperty("data") val data: SeriesData?)
    data class SeriesData(
        @JsonProperty("sections") val sections: List<Section>?
    )
    data class Section(
        @JsonProperty("episodes") val episodes: List<SeriesEpisode>?
    )
    data class SeriesEpisode(
        @JsonProperty("episode_id") val episodeId: Long?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("long_title") val longTitle: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("cover") val cover: String?
    )

    data class PlayUrlResponse(@JsonProperty("data") val data: PlayData?)
    data class PlayData(
        @JsonProperty("durl") val durl: List<Durl>?,
        @JsonProperty("dash") val dash: Dash?
    )
    data class Durl(
        @JsonProperty("url") val url: String?,
        @JsonProperty("backup_url") val backupUrl: List<String>?
    )
    data class Dash(
        @JsonProperty("video") val video: List<DashVideo>?
    )
    data class DashVideo(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("baseUrl") val baseUrl: String?,
        @JsonProperty("base_url") val base_url: String?
    )

    data class SubtitleResponse(@JsonProperty("data") val data: SubtitleData?)
    data class SubtitleData(
        @JsonProperty("subtitles") val subtitles: List<Subtitle>?
    )
    data class Subtitle(
        @JsonProperty("lan") val lan: String?,
        @JsonProperty("url") val url: String?
    )
}
