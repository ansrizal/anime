package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class Loklok : MainAPI() {
    override var mainUrl = "https://api.loklok.fun"
    override var name = "Loklok"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    companion object {
        private const val geoblockError = "Loklok is Geoblock, use vpn or give up"

        // === API BARU (Web/PC) ===
        private const val api = "https://api.loklok.fun"
        private const val apiUrl = "$api/cms/pc"
        private const val homeApi = "$api/home/pc"
        private const val searchApi = "$api/cms/pc"
        private const val rankApi = "$api/home/pc"

        private const val mainImageUrl = "https://images.weserv.nl"

        private val PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCR0hi0Ah2EkHEL0bMsyQUPn8A1
0ZW42z9LtfSiUSMaf3lw/sfqRcMTmh4m8+sBnK2a5PWKLTG2CW/HWYydN5n1BU63
c9yYMAoUD+52usxxsMaELLOb+xEv6LRW5oquDck7ZWj0xkSfHc4UXUa1l1FwVXQS
+qFIRDJGXgPCUEGVmwIDAQAB
-----END PUBLIC KEY-----"""

        private val HEX = "0123456789abcdef"

        fun randomHex(len: Int): String {
            val bytes = ByteArray((len + 1) / 2)
            SecureRandom().nextBytes(bytes)
            val sb = StringBuilder(len)
            for (b in bytes) {
                sb.append(HEX[(b.toInt() shr 4) and 0xF])
                if (sb.length < len) sb.append(HEX[b.toInt() and 0xF])
            }
            return sb.toString().substring(0, len)
        }

        fun aesEcbEncrypt(plaintext: String, key16: ByteArray): String {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"))
            return Base64.encodeToString(
                cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        }

        fun md5Hex(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest) sb.append(String.format("%02x", b))
            return sb.toString().lowercase(Locale.ROOT)
        }

        fun hmacSha256Hex(data: String, key: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val digest = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest) sb.append(String.format("%02x", b))
            return sb.toString().lowercase(Locale.ROOT)
        }

        fun rsaEncrypt(data: String, publicKeyPem: String): String {
            val stripped = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.decode(stripped, Base64.DEFAULT)
            val spec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val kf = java.security.KeyFactory.getInstance("RSA")
            val pubKey = kf.generatePublic(spec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            return Base64.encodeToString(
                cipher.doFinal(data.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        }

        fun extractBodyValues(body: String): String {
            return try {
                val json = JSONObject(body)
                val keys = json.keys().asSequence().toList().sorted()
                keys.joinToString("") { k ->
                    val v = json.opt(k)
                    when (v) {
                        null, JSONObject.NULL -> "null"
                        is String -> v
                        else -> v.toString()
                    }
                }
            } catch (e: Exception) {
                ""
            }
        }

        fun buildHeaders(
            query: Map<String, String>? = null,
            body: String? = null,
            token: String? = null,
        ): Map<String, String> {
            val aesKeyInternal = randomHex(16)
            val currentTime = System.currentTimeMillis().toString()
            val deviceId = randomHex(32).uppercase(Locale.ROOT)

            val pubRaw = Base64.decode(
                PUBLIC_KEY.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\\s".toRegex(), ""),
                Base64.DEFAULT
            )
            val usertypeKey = pubRaw.copyOfRange(0, 16)
            val aesKeyInternalBytes = aesKeyInternal.toByteArray(Charsets.UTF_8)

            val aesKey = rsaEncrypt(aesKeyInternal, PUBLIC_KEY)

            // === SIGN ===
            val paramsString = if (!query.isNullOrEmpty()) {
                query.keys.sorted().map { query[it] ?: "" }.joinToString("")
            } else if (!body.isNullOrEmpty()) {
                extractBodyValues(body)
            } else {
                ""
            }
            val signPayload = paramsString + currentTime
            val signEncrypted = aesEcbEncrypt(signPayload, aesKeyInternalBytes)
            val sign = md5Hex(signEncrypted)

            // === USERTYPE ===
            val usertypeEncrypted = aesEcbEncrypt(deviceId, usertypeKey)
            val usertype = hmacSha256Hex(usertypeEncrypted, aesKeyInternalBytes)

            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
            val tzSign = if (offset >= 0) "+" else "-"
            val absOff = kotlin.math.abs(offset)
            val timezone = "GMT$tzSign${"%02d".format(absOff / 60)}:${"%02d".format(absOff % 60)}"

            val headers = mutableMapOf(
                "Content-Type" to "application/json",
                "aesKey" to aesKey,
                "aesKey_Internal" to aesKeyInternal,
                "clientType" to "web-loklok",
                "currentTime" to currentTime,
                "deviceId" to deviceId,
                "keke" to "false",
                "lang" to "en",
                "sign" to sign,
                "timezone" to timezone,
                "usertype" to usertype,
                "versionCode" to "32",
            )
            if (!token.isNullOrEmpty()) {
                headers["token"] = token
            }
            return headers
        }
    }

    private fun encode(input: String): String =
        java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20")

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = ArrayList<HomePageList>()
        val headers = buildHeaders()
        val homeData = app.get(
            "$homeApi/getHome?page=0&navigationId=1000004",
            headers = headers
        ).parsedSafe<Home>()?.data ?: throw ErrorLoadingException(geoblockError)

        val items = homeData.recommendItems.orEmpty()
            .ifEmpty { throw ErrorLoadingException(geoblockError) }

        for (section in items) {
            val type = section.homeSectionType?.trim()?.uppercase(Locale.ROOT) ?: continue
            if (type == "BANNER") continue
            if (type != "SINGLE_ALBUM") continue

            val header = section.homeSectionName?.trim().orEmpty()
            if (header.isEmpty()) continue
            val mediaList = section.recommendContentVOList.orEmpty()
                .mapNotNull { it.toSearchResponse() }
            if (mediaList.isEmpty()) continue
            home.add(HomePageList(header, mediaList))
        }

        if (home.isEmpty()) throw ErrorLoadingException(geoblockError)
        return newHomePageResponse(home, false)
    }

    // ==================== MEDIA -> SEARCH RESPONSE ====================
    private fun Media.toSearchResponse(): SearchResponse? {
        val title = title ?: name ?: return null
        val id = cid ?: id ?: return null

        // category: 0=MOVIE, 1=DRAMA/TV, 2=COMIC/ANIME, 3=SHORT, 4=?
        val cat = category ?: contentTypeToCategory(contentType)
        val realId = id.toString()

        val image = imageUrl ?: coverVerticalUrl

        return newMovieSearchResponse(
            title,
            UrlData(realId, cat).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = image?.let {
                "$mainImageUrl/?url=${encode(it)}&w=175&h=246&fit=cover&output=webp"
            }
        }
    }

    private fun contentTypeToCategory(contentType: String?): Int {
        return when (contentType?.uppercase(Locale.ROOT)) {
            "MOVIE" -> 0
            "DRAMA" -> 1
            "COMIC" -> 2
            else -> 1
        }
    }

    // ==================== SEARCH ====================
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val bodyJson = """{"searchKeyWord":"${query.replace("\"", "\\\"")}","size":"50","sort":"","searchType":""}"""
        val headers = buildHeaders(body = bodyJson)

        return app.post(
            "$searchApi/search/searchWithKeyWord",
            requestBody = bodyJson.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
            headers = headers
        ).parsedSafe<QuickSearchRes>()?.data?.searchResults?.mapNotNull { media ->
            media.toSearchResponse()
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? = quickSearch(query)

    // ==================== LOAD (DETAIL) ====================
    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<UrlData>(url)
        val id = data.id?.toString() ?: return null
        val category = data.category ?: 0

        val query = mapOf("id" to id, "category" to category.toString())
        val headers = buildHeaders(query = query)

        val res = app.get(
            "$apiUrl/movieDrama/get?id=$id&category=$category",
            headers = headers
        ).parsedSafe<Load>()?.data ?: throw ErrorLoadingException(geoblockError)

        val actors = res.starList?.mapNotNull {
            Actor(
                it.localName ?: return@mapNotNull null, it.image
            )
        }

        val episodes = res.episodeVo?.map { eps ->
            val definition = eps.definitionList?.map {
                Definition(it.code, it.description)
            }
            val subtitling = eps.subtitlingList?.map {
                Subtitling(it.languageAbbr, it.language, it.subtitlingUrl)
            }
            newEpisode(
                UrlEpisode(
                    id,
                    category,
                    eps.id,
                    definition,
                    subtitling
                ).toJson()
            ) {
                this.episode = eps.seriesNo
            }
        } ?: throw ErrorLoadingException("No Episode Found")

        val recommendations = res.likeList?.mapNotNull { rec ->
            rec.toSearchResponse()
        }

        val type = when {
            res.areaList?.firstOrNull()?.id == 44 && res.tagNameList?.contains("Anime") == true -> TvType.Anime
            category == 0 -> TvType.Movie
            else -> TvType.TvSeries
        }

        val animeType = if (type == TvType.Anime && category == 0) "movie" else "tv"
        val (malId, anilistId) = if (type == TvType.Anime) {
            getTracker(res.name, animeType, res.year)
        } else Tracker()

        return newTvSeriesLoadResponse(
            res.name ?: return null,
            url,
            if (category == 0) TvType.Movie else type,
            episodes
        ) {
            this.posterUrl = res.coverVerticalUrl
            this.backgroundPosterUrl = res.coverHorizontalUrl
            this.year = res.year
            this.plot = res.introduction
            this.tags = res.tagNameList
            this.score = Score.from10(res.score)
            addActors(actors)
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
            this.recommendations = recommendations
        }
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "in_ID" -> "Indonesian"
            "pt" -> "Portuguese"
            else -> str.split("_").first().let {
                try {
                    SubtitleHelper.fromTwoLettersToLanguage(it).toString()
                } catch (e: Exception) {
                    it
                }
            }
        }
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<UrlEpisode>(data)

        res.definitionList?.amap { video ->
            val query = mapOf(
                "category" to (res.category?.toString() ?: "1"),
                "contentId" to (res.id ?: ""),
                "episodeId" to (res.epId?.toString() ?: ""),
                "definition" to (video.code ?: "")
            )
            val headers = buildHeaders(query = query)

            val json = app.get(
                "$apiUrl/media/previewInfo?category=${res.category}&contentId=${res.id}&episodeId=${res.epId}&definition=${video.code}",
                headers = headers,
            ).parsedSafe<PreviewResponse>()?.data

            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    json?.mediaUrl ?: return@amap,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = getQuality(json?.currentDefinition ?: "")
                }
            )
        }

        res.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getLanguage(sub.languageAbbr ?: return@map),
                    sub.subtitlingUrl ?: return@map
                )
            )
        }

        return true
    }

    private fun getQuality(quality: String): Int {
        return when (quality) {
            "GROOT_FD" -> Qualities.P360.value
            "GROOT_LD" -> Qualities.P480.value
            "GROOT_SD" -> Qualities.P720.value
            "GROOT_HD" -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://consumet-instance.vercel.app/meta/anilist/$title")
            .parsedSafe<AniSearch>()?.results?.find { media ->
                (media.title?.english.equals(title, true) || media.title?.romaji.equals(title, true))
                        || (media.type.equals(type, true) && media.releaseDate == year)
            }
        return Tracker(res?.malId, res?.aniId, res?.image, res?.cover)
    }

    // ==================== DATA CLASSES ====================
    data class Tracker(
        val malId: Int? = null,
        val aniId: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class UrlData(
        val id: Any? = null,
        val category: Int? = null,
    )

    data class Subtitling(
        val languageAbbr: String? = null,
        val language: String? = null,
        val subtitlingUrl: String? = null,
    )

    data class Definition(
        val code: String? = null,
        val description: String? = null,
    )

    data class UrlEpisode(
        val id: String? = null,
        val category: Int? = null,
        val epId: Int? = null,
        val definitionList: List<Definition>? = arrayListOf(),
        val subtitlingList: List<Subtitling>? = arrayListOf(),
    )

    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
    )

    data class Results(
        @JsonProperty("id") val aniId: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("releaseDate") val releaseDate: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class AniSearch(
        @JsonProperty("results") val results: java.util.ArrayList<Results>? = arrayListOf(),
    )

    data class QuickSearchData(
        @JsonProperty("searchResults") val searchResults: ArrayList<Media>? = arrayListOf(),
    )

    data class QuickSearchRes(
        @JsonProperty("data") val data: QuickSearchData? = null,
    )

    data class PreviewResponse(
        @JsonProperty("data") val data: PreviewVideos? = null,
    )

    data class PreviewVideos(
        @JsonProperty("mediaUrl") val mediaUrl: String? = null,
        @JsonProperty("currentDefinition") val currentDefinition: String? = null,
    )

    data class SubtitlingList(
        @JsonProperty("languageAbbr") val languageAbbr: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("subtitlingUrl") val subtitlingUrl: String? = null,
    )

    data class DefinitionList(
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("description") val description: String? = null,
    )

    data class EpisodeVo(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("seriesNo") val seriesNo: Int? = null,
        @JsonProperty("definitionList") val definitionList: ArrayList<DefinitionList>? = arrayListOf(),
        @JsonProperty("subtitlingList") val subtitlingList: ArrayList<SubtitlingList>? = arrayListOf(),
    )

    data class Region(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class StarList(
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("localName") val localName: String? = null,
    )

    data class MediaDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("coverVerticalUrl") val coverVerticalUrl: String? = null,
        @JsonProperty("coverHorizontalUrl") val coverHorizontalUrl: String? = null,
        @JsonProperty("score") val score: String? = null,
        @JsonProperty("starList") val starList: ArrayList<StarList>? = arrayListOf(),
        @JsonProperty("areaList") val areaList: ArrayList<Region>? = arrayListOf(),
        @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
        @JsonProperty("likeList") val likeList: ArrayList<Media>? = arrayListOf(),
        @JsonProperty("tagNameList") val tagNameList: ArrayList<String>? = arrayListOf(),
    )

    data class Load(
        @JsonProperty("data") val data: MediaDetail? = null,
    )

    data class Media(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("cid") val cid: Any? = null,
        @JsonProperty("category") val category: Int? = null,
        @JsonProperty("domainType") val domainType: Int? = null,
        @JsonProperty("imageUrl") val imageUrl: String? = null,
        @JsonProperty("coverVerticalUrl") val coverVerticalUrl: String? = null,
        @JsonProperty("coverHorizontalUrl") val coverHorizontalUrl: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("score") val score: Any? = null,
        @JsonProperty("jumpAddress") val jumpAddress: String? = null,
    )

    data class RecommendItems(
        @JsonProperty("homeSectionName") val homeSectionName: String? = null,
        @JsonProperty("homeSectionType") val homeSectionType: String? = null,
        @JsonProperty("homeSectionId") val homeSectionId: Int? = null,
        @JsonProperty("recommendContentVOList") val recommendContentVOList: ArrayList<Media>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("recommendItems") val recommendItems: ArrayList<RecommendItems>? = arrayListOf(),
    )

    data class Home(
        @JsonProperty("data") val data: Data? = null,
    )
}

fun getDeviceId(length: Int = 16): String {
    val allowedChars = ('a'..'f') + ('0'..'9')
    return (1..length).map { allowedChars.random() }.joinToString("")
}
