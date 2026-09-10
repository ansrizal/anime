package com.bstation

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class BStation : MainAPI() {
    override var mainUrl = "https://www.bilibili.tv/id"
    private val apiUrl = "https://api.bilibili.tv/intl/gateway/web"
    override var name = "BStation"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    // Header yang meniru browser untuk menghindari blokir
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "https://www.bilibili.tv/",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://www.bilibili.tv"
    )

    override val mainPage = mainPageOf(
        "https://api.bilibili.tv/intl/gateway/web/playlist?platform=web&s_locale=id_ID&page=1&per_page=20&type=0" to "Populer",
        "https://api.bilibili.tv/intl/gateway/web/playlist?platform=web&s_locale=id_ID&page=1&per_page=20&type=1" to "Anime",
        "https://api.bilibili.tv/intl/gateway/web/playlist?platform=web&s_locale=id_ID&page=1&per_page=20&type=2" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Endpoint playlist mungkin perlu disesuaikan. Ini adalah contoh.
        val url = request.data.replace("page=1", "page=$page")
        val response = app.get(url, headers = apiHeaders).parsedSafe<BiliPlaylistResponse>()
        
        val items = response?.data?.playlist?.mapNotNull { item ->
            newAnimeSearchResponse(
                item.title ?: "",
                item.aid.toString(), // Simpan aid sebagai URL internal
                TvType.Anime
            ) {
                this.posterUrl = item.cover
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Menggunakan endpoint pencarian internal
        val searchUrl = "$apiUrl/search?keyword=$query&platform=web&s_locale=id_ID&page=1"
        val response = app.get(searchUrl, headers = apiHeaders).parsedSafe<BiliSearchResponse>()

        return response?.data?.mapNotNull { item ->
            val aid = item.aid ?: return@mapNotNull null
            newAnimeSearchResponse(
                item.title ?: "Tanpa Judul",
                aid.toString(), // Gunakan aid sebagai ID unik
                TvType.Anime
            ) {
                this.posterUrl = item.cover
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // 'url' di sini adalah 'aid' dari fungsi search
        val aid = url.toLongOrNull() ?: return null

        // Ambil detail video untuk mendapatkan daftar episode (jika ada)
        // Untuk video single, kita hanya perlu info dasar
        val detailUrl = "$apiUrl/v2/ugc/playlist?aid=$aid&platform=web&s_locale=id_ID"
        val detailResponse = app.get(detailUrl, headers = apiHeaders).parsedSafe<BiliVideoDetailResponse>()
        val videoData = detailResponse?.data?.playlist?.firstOrNull()

        val title = videoData?.title ?: "Video"
        val poster = videoData?.cover

        // Untuk saat ini, kita asumsikan ini adalah film single.
        // Untuk seri, diperlukan logika tambahan untuk mengambil daftar episode.
        return newMovieLoadResponse(title, url, TvType.Anime, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val aid = data.toLongOrNull() ?: return false

        // Panggil API playurl untuk mendapatkan tautan stream
        val playUrl = "$apiUrl/playurl?aid=$aid&qn=64&platform=web&s_locale=id_ID&device=wap"
        val response = app.get(playUrl, headers = apiHeaders).parsedSafe<BiliPlayUrlResponse>()

        val videoUrl = response?.data?.durl?.firstOrNull()?.url
            ?: response?.data?.dash?.video?.firstOrNull()?.baseUrl

        if (videoUrl.isNullOrBlank()) {
            return false
        }

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                videoUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.bilibili.tv/"
                this.quality = Qualities.Unknown.value
            }
        )

        // Ambil subtitle (jika ada)
        val subtitleUrl = "$apiUrl/v2/subtitle?aid=$aid&platform=web&s_locale=id_ID"
        app.get(subtitleUrl, headers = apiHeaders).parsedSafe<BiliSubtitleResponse>()?.data?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.lan,
                    sub.url
                )
            )
        }
        
        return true
    }

    // ==== Data Classes untuk API ====
    data class BiliPlaylistResponse(@JsonProperty("data") val data: Data?) {
        data class Data(@JsonProperty("playlist") val playlist: List<Item>?)
    }
    data class BiliSearchResponse(@JsonProperty("data") val data: List<Item>?)
    data class Item(
        @JsonProperty("aid") val aid: Long?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("cover") val cover: String?
    )
    data class BiliVideoDetailResponse(@JsonProperty("data") val data: Data?) {
        data class Data(@JsonProperty("playlist") val playlist: List<Item>?)
    }
    data class BiliPlayUrlResponse(@JsonProperty("data") val data: Data?) {
        data class Data(
            @JsonProperty("durl") val durl: List<Durl>?,
            @JsonProperty("dash") val dash: Dash?
        )
        data class Durl(@JsonProperty("url") val url: String?)
        data class Dash(@JsonProperty("video") val video: List<Video>?)
        data class Video(@JsonProperty("baseUrl") val baseUrl: String?)
    }
    data class BiliSubtitleResponse(@JsonProperty("data") val data: Data?) {
        data class Data(@JsonProperty("subtitles") val subtitles: List<Subtitle>?)
        data class Subtitle(
            @JsonProperty("lan") val lan: String?,
            @JsonProperty("url") val url: String?
        )
    }
}
