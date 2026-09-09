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
import java.net.URLEncoder

class MovieboxProvider : MainAPI() {
    override var mainUrl = "https://moviebox.ph"
    private val mainAPIUrl = "https://h5-api.aoneroom.com"
    private val secondAPIUrl = "https://filmboom.top"
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
        "1006,Rating" to "Animation Rating"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val home = mutableListOf<SearchResponse>()

        if (!request.data.contains(",")) {
            val url = "$mainAPIUrl/wefeed-h5api-bff/ranking-list/content?id=${request.data}&page=$page&perPage=12"

            val index = app.get(url).parsedSafe<Media>()?.data?.subjectList?.map {
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

            val index = app.post("$mainAPIUrl/wefeed-h5api-bff/subject/filter", requestBody = body)
                .parsedSafe<Media>()?.data?.items?.map {
                    it.toSearchResponse(this)
                } ?: throw ErrorLoadingException("No Data Found")

            home.addAll(index)
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        // Langsung coba scraping HTML (paling andal)
        try {
            val htmlUrl = "$mainUrl/web/searchResult?keyword=${URLEncoder.encode(query, "UTF-8")}"
            val htmlResponse = app.get(
                htmlUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to mainUrl
                )
            )
            val html = htmlResponse.text

            // Ekstrak data dari __NUXT_DATA__ (pendekatan substring)
            val startTag = """<script id="__NUXT_DATA__" type="application/json">"""
            val endTag = "</script>"
            val startIndex = html.indexOf(startTag)
            if (startIndex != -1) {
                val endIndex = html.indexOf(endTag, startIndex + startTag.length)
                if (endIndex != -1) {
                    val jsonString = html.substring(startIndex + startTag.length, endIndex)
                    val itemsList = findItemsRecursively(jsonString)
                    if (itemsList.isNotEmpty()) {
                        return itemsList.mapNotNull { itemMap ->
                            try {
                                val title = itemMap["title"] as? String ?: ""
                                val subjectId = itemMap["subjectId"] as? String ?: ""
                                val subjectType = (itemMap["subjectType"] as? Number)?.toInt() ?: 1
                                val cover = (itemMap["cover"] as? Map<*, *>)?.get("url") as? String

                                this.newMovieSearchResponse(
                                    title,
                                    subjectId,
                                    if (subjectType == 1) TvType.Movie else TvType.TvSeries,
                                    false
                                ) {
                                    this.posterUrl = cover
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Jika scraping gagal, coba API
        }

        // Fallback: coba API dengan header lengkap
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // Coba beberapa variasi endpoint dan parameter
        val endpoints = listOf(
            "$mainAPIUrl/wefeed-h5-bff/web/subject/search",
            "$secondAPIUrl/wefeed-h5-bff/web/subject/search"
        )
        val bodyVariants = listOf(
            mapOf("keyword" to query, "page" to "1", "perPage" to "20", "subjectType" to "0"),
            mapOf("keyword" to query, "page" to "1", "perPage" to "20"),
            mapOf("keyword" to query, "page" to "1", "perPage" to "20", "subjectType" to "-1")
        )

        for (url in endpoints) {
            for (bodyMap in bodyVariants) {
                try {
                    val body = bodyMap.toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                    val response = app.post(url, requestBody = body, headers = headers)
                    val parsed = response.parsedSafe<Media>()
                    val items = parsed?.data?.items
                    if (!items.isNullOrEmpty()) {
                        return items.map { it.toSearchResponse(this) }
                    }
                } catch (_: Exception) { /* lanjut */ }
            }
        }

        // Terakhir coba GET
        try {
            val url = "$mainAPIUrl/wefeed-h5-bff/web/subject/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=1&perPage=20&subjectType=0"
            val response = app.get(url, headers = headers)
            val parsed = response.parsedSafe<Media>()
            val items = parsed?.data?.items
            if (!items.isNullOrEmpty()) {
                return items.map { it.toSearchResponse(this) }
            }
        } catch (_: Exception) { /* lanjut */ }

        return emptyList()
    }

    // Fungsi pembantu untuk mencari array "items" di dalam JSON secara rekursif
    private fun findItemsRecursively(json: String): List<Map<*, *>> {
        return try {
            val parsed = parseJson<Any>(json)
            extractItems(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractItems(obj: Any?): List<Map<*, *>> {
        if (obj == null) return emptyList()
        return when (obj) {
            is Map<*, *> -> {
                // Cek apakah ada key "items" yang berisi List
                val items = obj["items"]
                if (items is List<*>) {
                    val result = items.mapNotNull { it as? Map<*, *> }
                    if (result.isNotEmpty()) return result
                }
                // Cari di semua value
                for (value in obj.values) {
                    val found = extractItems(value)
                    if (found.isNotEmpty()) return found
                }
                emptyList()
            }
            is List<*> -> {
                for (item in obj) {
                    val found = extractItems(item)
                    if (found.isNotEmpty()) return found
                }
                emptyList()
            }
            else -> emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val document = app.get("$secondAPIUrl/wefeed-h5-bff/web/subject/detail?subjectId=$id")
            .parsedSafe<MediaDetail>()?.data
        val subject = document?.subject
        val title = subject?.title ?: ""
        val poster = subject?.cover?.url
        val tags = subject?.genre?.split(",")?.map { it.trim() }

        val year = subject?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val tvType = if (subject?.subjectType == 2) TvType.TvSeries else TvType.Movie
        val description = subject?.description
        val trailer = subject?.trailer?.videoAddress?.url
        val rating = subject?.imdbRatingValue?.toIntOrNull()
        val actors = document?.stars?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: return@mapNotNull null,
                    cast.avatarUrl
                ),
                roleString = cast.character
            )
        }?.distinctBy { it.actor }

        val recommendations =
            app.get("$mainUrl/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=12")
                .parsedSafe<Media>()?.data?.items?.map {
                    it.toSearchResponse(this)
                }

        return if (tvType == TvType.TvSeries) {
            val episode = document?.resource?.seasons?.map { seasons ->
                (if (seasons.allEp.isNullOrEmpty()) (1..seasons.maxEp!!) else seasons.allEp.split(",")
                    .map { it.toInt() })
                    .map { episode ->
                        newEpisode(
                            LoadData(
                                id,
                                seasons.se,
                                episode,
                                subject?.detailPath
                            ).toJson()
                        ) {
                            this.season = seasons.se
                            this.episode = episode
                        }
                    }
            }?.flatten() ?: emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episode) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(id, detailPath = subject?.detailPath).toJson()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media = parseJson<LoadData>(data)
        val referer = "$secondAPIUrl/spa/videoPlayPage/movies/${media.detailPath}?id=${media.id}&type=/movie/detail&lang=en"

        val streams = app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/play?subjectId=${media.id}&se=${media.season ?: 0}&ep=${media.episode ?: 0}",
            referer = referer
        ).parsedSafe<Media>()?.data?.streams

        streams?.reversed()?.distinctBy { it.url }?.map { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source.url ?: return@map,
                    INFER_TYPE
                ) {
                    this.referer = "$secondAPIUrl/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val id = streams?.first()?.id
        val format = streams?.first()?.format

        app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=${media.id}",
            referer = referer
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
        val detailPath: String? = null
    )

    data class Media(
        @JsonProperty("data") val data: Data? = null
    ) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf()
        ) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
                @JsonProperty("url") val url: String? = null,
                @JsonProperty("resolutions") val resolutions: String? = null
            )

            data class Captions(
                @JsonProperty("lan") val lan: String? = null,
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null
            )
        }
    }

    data class MediaDetail(
        @JsonProperty("data") val data: Data? = null
    ) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null
        ) {
            data class Stars(
                @JsonProperty("name") val name: String? = null,
                @JsonProperty("character") val character: String? = null,
                @JsonProperty("avatarUrl") val avatarUrl: String? = null
            )

            data class Resource(
                @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf()
            ) {
                data class Seasons(
                    @JsonProperty("se") val se: Int? = null,
                    @JsonProperty("maxEp") val maxEp: Int? = null,
                    @JsonProperty("allEp") val allEp: String? = null
                )
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
        @JsonProperty("detailPath") val detailPath: String? = null
    ) {

        fun toSearchResponse(provider: MovieboxProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title ?: "",
                subjectId ?: "",
                if (subjectType == 1) TvType.Movie else TvType.TvSeries,
                false
            ) {
                this.posterUrl = cover?.url
            }
        }

        data class Cover(
            @JsonProperty("url") val url: String? = null
        )

        data class Trailer(
            @JsonProperty("videoAddress") val videoAddress: VideoAddress? = null
        ) {
            data class VideoAddress(
                @JsonProperty("url") val url: String? = null
            )
        }
    }
}
