package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.lagradost.nicehttp.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MovieboxProvider : MainAPI() {
    override var mainUrl = "https://movieboxhd.net"
    private val apiUrl = "https://h5-api.aoneroom.com"
    private val apiPath = "/wefeed-h5api-bff"

    // ==== JWT TOKEN (valid s/d ~Des 2026) ====
    // Kalau expired, ambil token baru dari movieboxhd.net:
    // DevTools -> Network -> request apapun ke h5-api.aoneroom.com -> Headers -> Authorization
    private val authToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcyMjczODQ2OTQ1ODkyNDcwNzIsImF0cCI6MywiZXh0IjoiMTc4ODk1MDUyNSIsImV4cCI6MTc5NjcyNjUyNSwiaWF0IjoxNzg4OTUwMjI1fQ.5UOiHLYcY9GSNzm6J8aw0T5AqBRdyiSuQF4xHDwCTqU"

    private val commonHeaders = mapOf(
        "authorization"           to "Bearer $authToken",
        "accept"                  to "application/json",
        "content-type"            to "application/json",
        "origin"                  to mainUrl,
        "referer"                 to "$mainUrl/",
        "user-agent"              to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "x-client-info"           to """{"timezone":"Asia/Jakarta"}""",
        "x-request-lang"          to "en",
        "x-no-high-risk-restrict" to "0",
        "x-vip-restrict"          to "1"
    )

    override val instantLinkLoading = true
    override var name = "MovieBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage: List<MainPageData> = mainPageOf(
        "872031290915189720" to "Trending Now",
        "997144265920760504" to "Popular Movie",
        "5283462032510044280" to "Latest Indonesian Drama",
        "6528093688173053896" to "Trending Indonesian Movies",
        "4380734070238626200" to "K-Drama",
        "7736026911486755336" to "Western TV",
        "8624142774394406504" to "Most Popular C-Drama",
        "5404290953194750296" to "Trending Anime",
        "5848753831881965888" to "Indonesian Horror Stories",
        "1164329479448281992" to "Thai-Drama",
        "7132534597631837112" to "Animated Film",
        "1,ForYou" to "Movie ForYou",
        "1,Hottest" to "Movie Hottest",
        "1,Latest" to "Movie Latest",
        "1,Rating" to "Movie Rating",
        "2,ForYou" to "TVShow ForYou",
        "2,Hottest" to "TVShow Hottest",
        "2,Latest" to "TVShow Latest",
        "2,Rating" to "TVShow Rating",
        "1006,ForYou" to "Animation ForYou",
        "1006,Hottest" to "Animation Hottest",
        "1006,Latest" to "Animation Latest",
        "1006,Rating" to "Animation Rating")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest): HomePageResponse {

        val home = mutableListOf<SearchResponse>()

        if (!request.data.contains(",")) {
            val url = "$apiUrl$apiPath/ranking-list/content?id=${request.data}&page=$page&perPage=12"

            val index = app.get(url, headers = commonHeaders)
                .parsedSafe<Media>()?.data?.subjectList?.map {
                    it.toSearchResponse(this)
                } ?: throw ErrorLoadingException("No Data Found")

            home.addAll(index)
        } else {
            val params = request.data.split(",")
            val body = mapOf(
                "channelId" to params.first(),
                "page" to page,
                "perPage" to "28",
                "sort" to params.last()
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val index = app.post(
                "$apiUrl$apiPath/subject/filter",
                requestBody = body,
                headers = commonHeaders
            ).parsedSafe<Media>()?.data?.items?.map {
                it.toSearchResponse(this)
            } ?: throw ErrorLoadingException("No Data Found")

            home.addAll(index)
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ================================================================
    //  SEARCH — POST /wefeed-h5api-bff/subject/search
    //  Diverifikasi bekerja (totalCount 131 untuk "one piece")
    // ================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val body = mapOf(
            "keyword"     to query,
            "page"        to "1",
            "perPage"     to "24",
            "subjectType" to "0"   // 0 = All, 1 = Movie, 2 = TV, 5 = Education
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return app.post(
            "$apiUrl$apiPath/subject/search",
            requestBody = body,
            headers = commonHeaders
        ).parsedSafe<Media>()?.data?.items
            ?.mapNotNull { it.toSearchResponse(this) }
            ?: emptyList()
    }

    // ================================================================
    //  LOAD — parse HTML detail page (__NUXT_DATA__)
    //  Karena MovieBox tidak expose endpoint API detail terpisah.
    // ================================================================
    override suspend fun load(url: String): LoadResponse {
        // URL dari search berformat "subjectId|detailPath"
        val parts = url.split("|")
        val subjectId = parts.getOrNull(0) ?: throw ErrorLoadingException("ID subjek tidak ditemukan")
        val detailPath = parts.getOrNull(1) ?: ""

        // 1. Ambil HTML halaman detail
        val html = app.get(
            "$mainUrl/moviedetail/$detailPath?id=$subjectId",
            headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "accept-language" to "en-US,en;q=0.9"
            )
        ).text

        // 2. Ekstrak konten __NUXT_DATA__
        val nuxtData = Regex(
            """<script type="application/json" id="__NUXT_DATA__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Data detail tidak ditemukan di halaman")

        // 3. Fungsi bantu untuk extract nilai via regex
        fun extract(pattern: String): String? =
            Regex(pattern).find(nuxtData)?.groupValues?.getOrNull(1)

        // 4. Ekstrak field utama
        val title       = extract(""""title":"([^"]*)"""") ?: "Tanpa Judul"
        val poster      = extract(""""cover":\{"url":"([^"]*)"""")
        val description = extract(""""description":"([^"]*)"""") ?: "Sinopsis tidak tersedia."
        val releaseDate = extract(""""releaseDate":"([^"]*)"""") ?: ""
        val genre       = extract(""""genre":"([^"]*)"""") ?: ""
        val rating      = extract(""""imdbRatingValue":"([^"]*)"""") ?: "0"
        val trailer     = extract(""""trailer":\{"videoAddress":\{"url":"([^"]*)"""")

        // 5. Tentukan tipe & tahun
        val year = releaseDate.substringBefore("-").toIntOrNull()
        val isSeries = genre.contains("Animation", ignoreCase = true) &&
                Regex(""""se":\d+""").containsMatchIn(nuxtData)
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        // 6. Ekstrak episode (jika series)
        val episodes = mutableListOf<Episode>()
        if (tvType == TvType.TvSeries) {
            val episodeRegex = Regex(""""se":(\d+).*?"ep":(\d+)""", RegexOption.DOT_MATCHES_ALL)
            episodeRegex.findAll(nuxtData).forEach { match ->
                val se = match.groupValues[1].toIntOrNull() ?: 0
                val ep = match.groupValues[2].toIntOrNull() ?: 0
                if (episodes.none { it.season == se && it.episode == ep }) {
                    episodes.add(
                        newEpisode(
                            LoadData(subjectId, se, ep, detailPath).toJson()
                        ) {
                            this.season = se
                            this.episode = ep
                            this.name = "Episode $ep"
                        }
                    )
                }
            }
        }

        // 7. Recommendations (endpoint ini sudah terverifikasi bekerja)
        val recommendations = try {
            app.get(
                "$apiUrl$apiPath/subject/detail-rec?subjectId=$subjectId&page=1&perPage=12",
                headers = commonHeaders
            ).parsedSafe<Media>()?.data?.items?.map { it.toSearchResponse(this) }
        } catch (e: Exception) {
            null
        }

        // 8. Bangun response
        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genre.split(",").map { it.trim() }.filter { it.isNotBlank() }
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (!trailer.isNullOrBlank()) addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title, url, TvType.Movie,
                LoadData(subjectId, detailPath = detailPath).toJson()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genre.split(",").map { it.trim() }.filter { it.isNotBlank() }
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (!trailer.isNullOrBlank()) addTrailer(trailer, addRaw = true)
            }
        }
    }

    // ================================================================
    //  LOAD LINKS — pakai endpoint /subject/play (belum diverifikasi,
    //  akan disesuaikan setelah load() berjalan)
    // ================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media = parseJson<LoadData>(data)
        val referer = "$mainUrl/spa/videoPlayPage/movies/${media.detailPath}?id=${media.id}&type=/movie/detail&lang=en"

        val streams = app.get(
            "$apiUrl$apiPath/subject/play?subjectId=${media.id}&se=${media.season ?: 0}&ep=${media.episode ?: 0}",
            headers = commonHeaders + mapOf("referer" to referer)
        ).parsedSafe<Media>()?.data?.streams

        streams?.reversed()?.distinctBy { it.url }?.map { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source.url ?: return@map,
                    INFER_TYPE
                ) {
                    this.referer = "$apiUrl/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val id = streams?.first()?.id
        val format = streams?.first()?.format

        app.get(
            "$apiUrl$apiPath/subject/caption?format=$format&id=$id&subjectId=${media.id}",
            headers = commonHeaders + mapOf("referer" to referer)
        ).parsedSafe<Media>()?.data?.captions?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.lanName ?: "",
                    subtitle.url ?: return@map
                )
            )
        }

        return true
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null)

    data class Media(
        @JsonProperty("data") val data: Data? = null) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf()) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
                @JsonProperty("url") val url: String? = null,
                @JsonProperty("resolutions") val resolutions: String? = null)

            data class Captions(
                @JsonProperty("lan") val lan: String? = null,
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null)
        }
    }

    data class MediaDetail(
        @JsonProperty("data") val data: Data? = null) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null) {
            data class Stars(
                @JsonProperty("name") val name: String? = null,
                @JsonProperty("character") val character: String? = null,
                @JsonProperty("avatarUrl") val avatarUrl: String? = null)

            data class Resource(
                @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf()) {
                data class Seasons(
                    @JsonProperty("se") val se: Int? = null,
                    @JsonProperty("maxEp") val maxEp: Int? = null,
                    @JsonProperty("allEp") val allEp: String? = null)
            }
        }
    }

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("detailPath") val detailPath: String? = null) {

        fun toSearchResponse(provider: MovieboxProvider): SearchResponse {
            val type = when (subjectType) {
                1, 49 -> TvType.Movie
                2, 24 -> TvType.TvSeries
                6     -> TvType.Music
                else  -> TvType.TvSeries
            }

            // URL = "subjectId|detailPath"
            val url = "${subjectId ?: ""}|${detailPath ?: ""}"

            return provider.newMovieSearchResponse(
                title ?: "",
                url,
                type,
                false
            ) {
                this.posterUrl = cover?.url
            }
        }

        data class Cover(
            @JsonProperty("url") val url: String? = null)

        data class Trailer(
            @JsonProperty("videoAddress") val videoAddress: VideoAddress? = null) {
            data class VideoAddress(
                @JsonProperty("url") val url: String? = null)
        }
    }
}
