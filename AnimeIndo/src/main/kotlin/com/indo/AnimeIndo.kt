package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeIndo : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/movie/page/" to "Movie",
        "$mainUrl/anime-list/" to "Daftar Anime"
    )

    // ---------- HELPERS ----------
    private fun getPosterUrl(element: Element?): String? {
        if (element == null) return null
        val img = element.selectFirst("img") ?: return null
        // Coba atribut yang umum digunakan untuk lazy loading
        var src = img.attr("data-original")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("src") }
        if (src.isBlank()) return null
        // Abaikan gambar placeholder/loading
        if (src.contains("loading", ignoreCase = true) || 
            src.contains("placeholder", ignoreCase = true) ||
            src.contains("blank", ignoreCase = true)) {
            return null
        }
        // Ubah ke URL absolut
        return if (src.startsWith("http")) src else fixUrl(src)
    }

    // ---------- MAIN PAGE ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("/movie/") -> {
                if (page == 1) "$mainUrl/movie/" else "$mainUrl/movie/page/$page/"
            }
            request.data.contains("/anime-list/") -> {
                if (page == 1) "$mainUrl/anime-list/" else "$mainUrl/anime-list/page/$page/"
            }
            else -> {
                if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
            }
        }
        val document = app.get(url).document

        val home = when {
            request.data.contains("/movie/") -> parseMovies(document)
            request.data.contains("/anime-list/") -> parseAnimeList(document)
            else -> parseEpisodes(document)
        }
        return newHomePageResponse(request.name, home)
    }

    // Parser untuk episode terbaru
    private fun parseEpisodes(document: Document): List<SearchResponse> {
        return document.select("div.menu a[href]").asIterable().mapNotNull { a ->
            val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = inner.selectFirst("p")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = getPosterUrl(inner)
            val animeUrl = episodeToAnimeUrl(href)
            newAnimeSearchResponse(title, fixUrl(animeUrl), TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // Parser untuk movie
    private fun parseMovies(document: Document): List<SearchResponse> {
        return document.select("table.otable").asIterable().mapNotNull { table ->
            val link = table.selectFirst("td.vithumb a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { null } ?: return@mapNotNull null
            val poster = getPosterUrl(link)
            val desc = table.selectFirst("td.videsc") ?: return@mapNotNull null
            val title = desc.selectFirst("a[href]")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // Parser untuk daftar anime (anime-list) - tidak ada gambar di halaman ini
    private fun parseAnimeList(document: Document): List<SearchResponse> {
        // Di halaman ini hanya ada teks, tidak ada gambar.
        // Kita hanya tampilkan judul tanpa poster.
        return document.select("div.anime-list a[href], table.otable a[href], div.list-anime a[href]")
            .asIterable().mapNotNull { a ->
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    // posterUrl = null (tidak ada gambar)
                }
            }.distinctBy { it.url }
    }

    // Helper untuk mengubah URL episode menjadi URL anime
    private fun episodeToAnimeUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val animeSlug = Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        return "$mainUrl/anime/$animeSlug/"
    }

    // ---------- SEARCH ---------- (diperbaiki agar gambar muncul)
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrls = listOf(
            "$mainUrl/search.php?q=$query",
            "$mainUrl/?s=$query",
            "$mainUrl/search?q=$query"
        )
        var document: Document? = null
        for (url in searchUrls) {
            try {
                document = app.get(url).document
                // Periksa apakah ada hasil
                if (document.select("div.result, div.list-anime, div.anime-item, a[href*=/anime/]").isNotEmpty()) {
                    break
                }
            } catch (_: Exception) { }
        }
        document ?: return emptyList()

        // Cari elemen yang berisi link ke anime dan memiliki gambar
        // Selektor mencakup struktur hasil pencarian yang umum
        val items = document.select("div.result a[href], div.list-anime a[href], div.menu a[href], a[href*=/anime/]")
        return items.asIterable().mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            // Cari container yang berisi gambar dan teks
            val container = a.selectFirst("div.list-anime, div.thumb, div.result") ?: a
            // Judul bisa dari berbagai elemen
            val title = container.selectFirst("p, h2, h3, .title, .anime-title, td.videsc a")?.text()?.trim()?.ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null
            val poster = getPosterUrl(container)
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // ---------- LOAD ----------
    override suspend fun load(url: String): LoadResponse {
        val isEpisode = !url.contains("/anime/")
        val episodeDoc = if (isEpisode) app.get(url).document else null

        val animeUrl = episodeDoc?.selectFirst("div.navi a[href*=/anime/]")?.attr("href")
            ?.let { fixUrl(it) }
            ?: if (url.contains("/anime/")) url
            else episodeToAnimeUrl(url)

        val document = app.get(animeUrl).document

        val title = document.selectFirst("h1.title, h2.title, h1, h2")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = getPosterUrl(document.selectFirst("div.detail, td.vithumb"))

        val description = document.selectFirst("div.detail p, p.des")?.text()?.trim()
        val genres = document.select("div.detail li a").asIterable().map { it.text() }.filter { it.isNotBlank() }

        val episodes = document.select("div.ep a[href]").asIterable().mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim()
            val ep = epText.toIntOrNull()
                ?: Regex("(\\d+)").find(href.trimEnd('/').substringAfterLast("/"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) { this.name = "Episode $epText"; this.episode = ep }
        }.sortedBy { it.episode }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), null, true)

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    // ---------- LOAD LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val serverUrls = mutableListOf<String>()
        document.selectFirst("iframe#tontonin")?.attr("src")?.ifBlank { null }?.let {
            serverUrls.add(it)
        }
        document.select("a.server[data-video]").asIterable().forEach { a ->
            val url = a.attr("data-video").ifBlank { null } ?: return@forEach
            if (!serverUrls.contains(url)) serverUrls.add(url)
        }

        serverUrls.forEach { url ->
            val fullUrl = if (url.startsWith("/")) "$mainUrl$url" else url
            if (fullUrl.contains("btube3.php")) {
                try {
                    val playerDoc = app.get(fullUrl).document
                    val videoSrc = playerDoc.selectFirst("source[src]")?.attr("src")
                        ?: playerDoc.selectFirst("video")?.attr("src")
                    if (!videoSrc.isNullOrBlank()) {
                        val itag = Regex("[?&]itag=(\\d+)").find(videoSrc)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val quality = when (itag) {
                            18 -> Qualities.P360.value
                            22 -> Qualities.P720.value
                            37 -> Qualities.P1080.value
                            59 -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                        callback(
                            newExtractorLink(
                                "AnimeIndo",
                                "B-TUBE",
                                videoSrc
                            ) {
                                this.quality = quality
                                this.referer = "https://www.blogger.com/"
                            }
                        )
                    }
                } catch (_: Exception) {}
            } else if (fullUrl.contains("xtwap.top")) {
                try {
                    val html = app.get(fullUrl).text
                    val fileMatch = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    val filePath = fileMatch?.groupValues?.getOrNull(1)
                    if (!filePath.isNullOrBlank()) {
                        val masterUrl = if (filePath.startsWith("/")) "https://xtwap.top$filePath" else filePath
                        val links = M3u8Helper.generateM3u8("AnimeIndo", masterUrl, fullUrl)
                        if (links.isNotEmpty()) {
                            links.forEach { callback(it) }
                        } else {
                            callback(newExtractorLink("AnimeIndo", "CEPAT", masterUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = fullUrl
                            })
                        }
                    }
                } catch (_: Exception) {}
            } else {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        document.select("div.navi a[href]").asIterable().forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            if (href.startsWith("http") && !href.contains(mainUrl)) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
