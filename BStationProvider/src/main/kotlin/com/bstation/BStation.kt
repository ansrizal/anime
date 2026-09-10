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

    override val mainPage = mainPageOf(
        "$mainUrl/id/" to "Populer",
        "$mainUrl/id/anime" to "Anime",
        "$mainUrl/id/trending" to "Trending",
        "$mainUrl/id/short-drama" to "Dracin",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        val items = document.select("a[href*=/play/], a[href*=/video/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (items.isNotEmpty()) {
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/id/search-result?q=${query.replace(" ", "%20")}"
        val document = app.get(url).document

        val items = document.select("a[href*=/play/], a[href*=/video/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return items.toNewSearchResponseList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "$mainUrl$it"
                else -> it
            }
        }
        if (!href.contains("/play/") && !href.contains("/video/")) return null

        // Ambil title dari berbagai kemungkinan selector
        val title = this.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: this.selectFirst(".bstar-video-card__title")?.text()?.ifBlank { null }
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()?.ifBlank { null }
            ?: this.selectFirst("h3, .title, .card-title")?.text()?.ifBlank { null }
            ?: return null

        // Poster
        val posterUrl = this.selectFirst("img")?.attr("src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("source")?.attr("srcset")

        val finalPoster = posterUrl?.substringBefore("@")?.substringBefore(" ")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = finalPoster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.ifBlank { null }
            ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst(".video-info-desc, .desc")?.text()

        // Ekstrak season_id dan episode_id dari URL /play/{season_id}/{episode_id}
        val playMatch = Regex("""/play/(\d+)/(\d+)""").find(url)

        if (playMatch != null) {
            val seasonId = playMatch.groupValues[1]
            val episodeId = playMatch.groupValues[2]

            // Ambil daftar episode dari series API
            val seriesUrl = "$apiUrl/intl/gateway/web/v2/ogv/play/series" +
                    "?s_locale=id_ID&platform=web&season_id=$seasonId"
            val seriesResponse = app.get(seriesUrl, headers = apiHeaders)
                .parsedSafe<SeriesResponse>()

            val episodes = seriesResponse?.data?.sections?.flatMap { section ->
                section.episodes?.mapNotNull { ep ->
                    val epId = ep.episodeId?.toString() ?: return@mapNotNull null
                    newEpisode(epId) {
                        this.name = ep.title ?: "Episode ${ep.episodeNumber ?: ""}"
                        this.episode = ep.episodeNumber
                    }
                } ?: emptyList()
            } ?: listOf(
                newEpisode(episodeId) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Fallback untuk /video/{aid} (single video)
        return newMovieLoadResponse(title, url, TvType.Anime, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data bisa berupa episode_id langsung atau URL
        val episodeId: Long? = data.toLongOrNull() ?: run {
            Regex("""(\d+)(?:\?|$)""").find(data)?.groupValues?.get(1)?.toLongOrNull()
        }
        if (episodeId == null) return false

        // 1. Ambil play URL
        val playUrl = "$apiUrl/intl/gateway/web/playurl" +
                "?s_locale=id_ID&platform=web&ep_id=$episodeId&tk=&qn=64&type=0&device=wap&tf=0"
        val response = app.get(playUrl, headers = apiHeaders).parsedSafe<PlayUrlResponse>()

        // Coba durl (FLV) dulu, lalu dash (M4S)
        val videoUrl = response?.data?.durl?.firstOrNull()?.url
            ?: response?.data?.dash?.video?.firstOrNull()?.baseUrl
            ?: response?.data?.dash?.video?.firstOrNull()?.base_url

        if (videoUrl.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                videoUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )

        // 2. Ambil subtitle (opsional)
        try {
            val subtitleUrl = "$apiUrl/intl/gateway/web/v2/subtitle" +
                    "?s_locale=id_ID&platform=web&episode_id=$episodeId"
            app.get(subtitleUrl, headers = apiHeaders)
                .parsedSafe<SubtitleResponse>()?.data?.subtitles
                ?.forEach { sub ->
                    val subUrl = sub.url
                    if (subUrl.isNullOrBlank()) return@forEach
                    val subLang = sub.lan ?: "Unknown"
                    subtitleCallback.invoke(newSubtitleFile(subLang, subUrl))
                }
        } catch (_: Exception) { /* subtitle opsional */ }

        return true
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
        @JsonProperty("episode_number") val episodeNumber: Int?
    )

    data class PlayUrlResponse(@JsonProperty("data") val data: PlayData?)
    data class PlayData(
        @JsonProperty("durl") val durl: List<Durl>?,
        @JsonProperty("dash") val dash: Dash?
    )
    data class Durl(
        @JsonProperty("url") val url: String?
    )
    data class Dash(
        @JsonProperty("video") val video: List<DashVideo>?
    )
    data class DashVideo(
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
