package com.donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class DonghubProvider : MainAPI() {
    override var mainUrl = "https://donghub.vip/"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "anime/" to "All Anime",
        "status/ongoing/" to "Series Ongoing",
        "status/completed/" to "Series Completed",
        "type/movie/" to "Movie",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/comedy/" to "Comedy",
        "genres/cultivation/" to "Cultivation",
        "genres/drama/" to "Drama",
        "genres/fantasy/" to "Fantasy",
        "genres/game/" to "Game",
        "genres/historical/" to "Historical",
        "genres/horor/" to "Horor",
        "genres/isekai/" to "Isekai",
        "genres/martial-arts/" to "Martial Arts",
        "genres/mystery/" to "Mystery",
        "genres/op-mc/" to "OP-MC",
        "genres/psychological/" to "Psychological",
        "genres/reincarnation/" to "Reincarnation",
        "genres/romance/" to "Romance",
        "genres/sci-fi/" to "Sci-fi",
        "genres/super-power/" to "Super Power",
        "genres/supranatural/" to "Supranatural",
        "genres/war/" to "War",
        "genres/wuxia/" to "Wuxia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            page <= 1 -> if (path.isEmpty()) mainUrl else "$mainUrl$path/"
            path.contains('?') -> "$mainUrl$path&page=$page"
            else -> "$mainUrl$path/page/$page/"
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        )).document
        
        var items = document.select("article.bs, article.styleegg").mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) {
            items = document.select("div.listupd article").mapNotNull { it.toSearchResult() }
        }
        if (items.isEmpty()) {
            items = document.select("article.bsx").mapNotNull { it.toSearchResult() }
        }
        
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty(),
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        
        var title = link.attr("title").trim()
        if (title.isBlank()) {
            title = selectFirst(".eggtitle")?.text()?.trim() ?:
                    selectFirst(".tt")?.text()?.trim() ?:
                    selectFirst("h2")?.text()?.trim() ?: return null
        }
        
        // Ambil gambar dari .limit img (poster utama)
        val img = selectFirst(".limit img") ?: selectFirst("img")
        // URL gambar sudah absolut, ambil langsung
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
        
        val typeEl = selectFirst(".typez")
        val isMovie = typeEl?.text()?.contains("Movie", ignoreCase = true) == true

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val url = "$mainUrl/page/$i/?s=$query"
            val document = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )).document
            val result = document.select("article.bs, article.styleegg").mapNotNull { it.toSearchResult() }
            if (result.isEmpty()) break
            list.addAll(result)
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        )).document
        
        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", ignoreCase = true) || 
                      document.select(".typez.Movie").isNotEmpty()

        // Ambil poster dari berbagai sumber, langsung ambil src
        val poster = document.select("div.ime > img, div.bigcontent img").first()?.attr("src")
            ?: document.select("meta[property=og:image]").attr("content")
            ?: document.select("img.attachment-post-thumbnail").first()?.attr("src")
            ?: ""

        val epBlocks = document.select(".eplister li").ifEmpty {
            document.select("div.list-episode .episode-item")
        }.ifEmpty {
            document.select("#episodes a")
        }

        return if (!isMovie && epBlocks.isNotEmpty()) {
            val episodes = epBlocks.map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title, .epx")?.text()?.trim() ?: ep.text()
                newEpisode(link) {
                    this.name = epTitle
                    this.posterUrl = poster
                    this.skipOp = true // skip opening
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val movieLink = document.selectFirst(".eplister li > a, .download a")
                ?.attr("href")
                ?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieLink, TvType.Movie, movieLink) {
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
        val document = app.get(data, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        )).document

        document.select(".mobius option").forEach { item ->
            val base64 = item.attr("value")
            if (base64.isNotBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val doc = Jsoup.parse(decoded)
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(fixUrl(iframe), subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
        }

        document.select("div.player-embed iframe, div.embed iframe, iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google")) {
                loadExtractor(fixUrl(src), subtitleCallback, callback)
            }
        }

        document.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='.mkv']").forEach { a ->
            val url = a.attr("href")
            if (url.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Download",
                        url = fixUrl(url),
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        }

        return true
    }

    private fun Element.getsrcAttribute(): String {
        val src = this.attr("src")
        val dataSrc = this.attr("data-src")
        val dataLazy = this.attr("data-lazy-src")
        return when {
            src.isNotBlank() && src.startsWith("http") -> src
            dataSrc.isNotBlank() && dataSrc.startsWith("http") -> dataSrc
            dataLazy.isNotBlank() && dataLazy.startsWith("http") -> dataLazy
            else -> ""
        }
    }
}
