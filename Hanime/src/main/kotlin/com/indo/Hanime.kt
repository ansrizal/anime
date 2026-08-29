package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString

class Hanime : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiBase = "https://hanime.tv/api/v8"

    private var searchCatalog: List<Map<String, Any?>>? = null
    private var searchCatalogTime = 0L

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "X-Directive" to "api",
    )

    private val playerHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Referer" to "https://player.hanime.tv/",
        "Origin" to "https://player.hanime.tv/",
        "Accept" to "*/*",
    )

    override val mainPage = mainPageOf(
        "https://hanime.tv/api/v8/static/landing" to "Recent Uploads"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val response = try {
            val json = app.get(request.data, headers = headers).text
            val root = tryParseJson<Map<String, Any?>>(json)
            if (root != null) {
                val sections = (root["sections"] as? List<*>) ?: emptyList<Any?>()
                val videosMap = (root["hentai_videos"] as? List<*>)
                    ?.asSequence()
                    ?.filterIsInstance<Map<String, Any?>>()
                    ?.associateBy { it["id"] } ?: emptyMap()
                
                sections.asSequence().filterIsInstance<Map<String, Any?>>().mapNotNull { section ->
                    val title = section["title"]?.toString() ?: return@mapNotNull null
                    val ids = (section["hentai_video_ids"] as? List<*>) ?: return@mapNotNull null
                    HomePageList(title, ids.mapNotNull { id -> toSearchResponse(videosMap[id]) })
                }.toList()
            } else null
        } catch (_: Exception) {
            null
        }

        if (!response.isNullOrEmpty()) return newHomePageResponse(response)

        // Scrape HTML fallback
        val doc = try { app.get(mainUrl, headers = headers).document } catch(_: Exception) { null }
        val items = doc?.select("div.hvc.item.card a[href^=/videos/hentai/]")?.mapNotNull { a ->
            val slug = a.attr("href").removePrefix("/videos/hentai/").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { null } ?: a.selectFirst(".title")?.text() ?: return@mapNotNull null
            newMovieSearchResponse(title, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }?.distinctBy { it.url }

        if (!items.isNullOrEmpty()) return newHomePageResponse(listOf(HomePageList("Recent Uploads", items)))

        val catalog = getSearchCatalog()
        if (catalog.isNullOrEmpty()) return newHomePageResponse(emptyList())

        val catalogItems = catalog.asReversed().take(25).mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(listOf(HomePageList("Recent Uploads", catalogItems)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return listOf()

        val catalog = getSearchCatalog()
        if (!catalog.isNullOrEmpty()) {
            val q = query.lowercase()
            return catalog.asSequence()
                .filter { item ->
                    item["name"]?.toString()?.lowercase()?.contains(q) == true ||
                        item["search_titles"]?.toString()?.lowercase()?.contains(q) == true ||
                        item["slug"]?.toString()?.lowercase()?.contains(q) == true
                }
                .mapNotNull { toSearchResponse(it) }
                .take(50)
                .toList()
        }

        val doc = app.get("$mainUrl/browse?search=$query", headers = headers).document
        return doc.select("div.hvc.item.card a[href^=/videos/hentai/]").asIterable().mapNotNull { a ->
            val slug = a.attr("href").removePrefix("/videos/hentai/").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { return@mapNotNull null }
            newMovieSearchResponse(title, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }.distinctBy { it.url }
    }

    private suspend fun getSearchCatalog(): List<Map<String, Any?>>? {
        val now = System.currentTimeMillis()
        if (searchCatalog != null && now - searchCatalogTime < 3_600_000L) return searchCatalog
        searchCatalog = try {
            val text = app.get("$apiBase/search_hvs", headers = headers).text
            tryParseJson<List<Map<String, Any?>>>(text)
        } catch (_: Exception) {
            null
        }
        searchCatalogTime = now
        return searchCatalog
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")

        val detail = try {
            val text = app.get("$apiBase/video?id=$slug", headers = headers).text
            val root = tryParseJson<Map<String, Any?>>(text)
            root?.get("hentai_video") as? Map<*, *>
        } catch (_: Exception) { null }

        detail?.let { return buildMovieResponse(it, url) }

        val fromCatalog = getSearchCatalog()?.firstOrNull { it["slug"]?.toString() == slug }
        fromCatalog?.let { return buildMovieResponse(it, url) }

        throw ErrorLoadingException("Video not found")
    }

    private suspend fun buildMovieResponse(video: Map<*, *>, url: String): LoadResponse {
        val title = video["name"]?.toString() ?: throw ErrorLoadingException("Title not found")
        val plot = (video["description"]?.toString() ?: "").replace(Regex("<[^>]*>"), "").ifBlank { null }
        val poster = video["poster_url"]?.toString()
            ?: video["cover_url"]?.toString()?.replace("covers", "posters")?.replace("cv1", "pv1")
        val tags = when (val t = video["hentai_tags"] ?: video["tags"]) {
            is List<*> -> {
                t.filterIsInstance<Map<*, *>>().mapNotNull { it["text"]?.toString() }.ifEmpty {
                    t.filterIsInstance<String>()
                }
            }
            else -> emptyList()
        }
        val year = video["released_at"]?.toString()?.take(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfterLast("/")

        val hvId = try {
            val text = app.get("$apiBase/video?id=$slug", headers = headers).text
            val root = tryParseJson<Map<String, Any?>>(text)
            (root?.get("hentai_video") as? Map<*, *>)?.get("id")?.toString()
        } catch (_: Exception) { null }

        val resolvedId = hvId
            ?: getSearchCatalog()?.firstOrNull { it["slug"]?.toString() == slug }?.get("id")?.toString()
            ?: return false

        try {
            app.post("$apiBase/hentai_videos/$slug/play", headers = headers, json = JsonAsString("{}"))
        } catch (_: Exception) { }

        val manifestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json",
            "x-signature-version" to "web2",
            "x-signature" to "",
            "x-time" to "0",
            "x-session-token" to "",
            "x-csrf-token" to ""
        )

        val manifestJson = try {
            app.get("$apiBase/guest/videos/$resolvedId/manifest", headers = manifestHeaders).text
        } catch (_: Exception) { null }

        val hlsUrl = if (!manifestJson.isNullOrBlank()) {
            val manifestRoot = tryParseJson<Map<String, Any?>>(manifestJson)
            if (manifestRoot != null) {
                manifestRoot["url"]?.toString()
                    ?: (manifestRoot["videos_manifest"] as? Map<*, *>)
                        ?.let { m ->
                            (m["servers"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull()
                                ?.let { s ->
                                    (s["streams"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull()
                                        ?.let { st -> st["url"]?.toString() }
                                }
                        }
            } else null
        } else null

        if (hlsUrl.isNullOrBlank()) return false

        val links = M3u8Helper.generateM3u8(
            source = name,
            streamUrl = hlsUrl,
            referer = "https://player.hanime.tv/",
            headers = playerHeaders
        )
        if (links.isEmpty()) {
            callback(
                newExtractorLink(name, "$name - HLS", hlsUrl) {
                    this.referer = "https://player.hanime.tv/"
                }
            )
        } else {
            links.forEach { callback(it) }
        }

        return true
    }

    private fun toSearchResponse(video: Map<String, Any?>?): SearchResponse? {
        val v = video ?: return null
        val name = v["name"]?.toString()?.ifBlank { null }
            ?: v["title"]?.toString()?.ifBlank { null }
            ?: return null
        val slug = v["slug"]?.toString()?.ifBlank { null }
            ?: v["id"]?.toString()?.ifBlank { null }
            ?: return null
        val poster = v["poster_url"]?.toString()?.ifBlank { null }
            ?: v["cover_url"]?.toString()?.ifBlank { null }
            ?: "https://hanime-cdn.com/images/posters/$slug-pv1.webp"

        return newMovieSearchResponse(name, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }
}
