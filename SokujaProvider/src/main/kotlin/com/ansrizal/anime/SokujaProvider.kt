package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse

class SokujaProvider : MainAPI() {
    override var mainUrl = "https://x6.sokuja.uk"
    override var name = "Sokuja Anime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private suspend fun request(url: String): NiceResponse {
        return app.get(
            url,
            headers = defaultHeaders,
            timeout = 30
        )
    }

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime/" to "Daftar Anime",
        "genre/action/" to "Action Anime",
        "genre/isekai/" to "Isekai Anime"
    )

    // Helper untuk membangun URL halaman dengan paginasi yang benar
    private fun buildPageUrl(path: String, page: Int): String {
        val base = mainUrl.removeSuffix("/")
        return when {
            page <= 1 -> if (path.isEmpty()) base else "$base/$path"
            path.isEmpty() -> "$base/page/$page/"
            path.contains("?") -> {
                val (basePath, query) = path.split("?", limit = 2)
                "$base/${basePath.removeSuffix("/")}/page/$page/?$query"
            }
            else -> "$base/${path.removeSuffix("/")}/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = buildPageUrl(path, page)

        val res = request(url)
        val document = res.document

        // Selector utama + fallback
        val items = document.select(
            "div.bsx, div.listupd article, div.utao, div.uta, div.luf, " +
            "article.bs, div.post-show article, div.bxb article, div.swiper-slide"
        )

        // Jika kosong, coba selector lain (beberapa tema pakai div.animposx)
        val finalItems = if (items.isEmpty()) {
            document.select("div.animposx, div.bs, div.bsx")
        } else items

        val homeItems = finalItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems),
            hasNext = homeItems.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        var href = fixUrl(linkElement.attr("href"))

        // Filter halaman yang tidak valid
        val invalid = listOf("/genre/", "/category/", "/page/", "/bookmark/", "/jadwal-rilis/", "/tag/")
        if (invalid.any { href.contains(it) }) return null
        if (href.removeSuffix("/") == mainUrl.removeSuffix("/")) return null

        // Ambil judul dari berbagai kemungkinan
        val title = selectFirst(".tt, h2, h3, h4, .title, .entry-title, .post-title, .ttname, .anime-title")
            ?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.isEmpty()) return null

        // Ambil gambar
        val img = selectFirst("img")
        var rawImg = img?.attr("data-src")
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("src")
            ?: img?.attr("srcset")?.substringBefore(" ")

        rawImg = when {
            rawImg.isNullOrBlank() -> null
            rawImg.startsWith("//") -> "https:$rawImg"
            else -> rawImg
        }
        val posterUrl = fixUrlNull(rawImg)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = request(searchUrl).document

        return document.select("div.bsx, div.listupd article, div.utao, div.uta, div.luf, article.bs, div.animposx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        // Coba cari link ke halaman daftar episode (jika di halaman detail tidak ada)
        val seriesLink = document.selectFirst(
            "a:contains(Semua Episode), div.breadcrumb a[href*='/anime/'], " +
            "div.ninfo a[href*='/anime/'], span.all-ep a, .all-episode a"
        )?.attr("href")

        val targetDoc = if (seriesLink != null && !url.contains("/anime/")) {
            val parentUrl = fixUrl(seriesLink)
            if (parentUrl != url) request(parentUrl).document else document
        } else {
            document
        }

        val title = targetDoc.selectFirst("h1.entry-title, h1, .infotable h1")?.text()
            ?.replace("Nonton Anime ", "")
            ?.replace("Subtitle Indonesia", "")
            ?.trim() ?: "Sokuja Anime"

        var rawPoster = targetDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("data-src")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("src")
        rawPoster = if (rawPoster?.startsWith("//") == true) "https:$rawPoster" else rawPoster
        val poster = fixUrlNull(rawPoster)

        val description = targetDoc.selectFirst("div.sinopc, div.entry-content, div.synopsis, [itemprop=description]")?.text()?.trim()

        // Ambil daftar episode
        val episodeElements = targetDoc.select(
            "div.eplister li, div.listeps li, .eplister ul li, " +
            "div.list-episode li, div.lsteps ul li, ul.clnew li, " +
            "div.epsother article, div.epslist li"
        )

        val episodes = mutableListOf<Episode>()

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, elem ->
                val a = elem.selectFirst("a") ?: return@forEachIndexed
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.text().trim().ifEmpty {
                    elem.selectFirst(".title, .epl-title")?.text()?.trim() ?: "Episode ${index + 1}"
                }
                val epNum = elem.selectFirst("span.epstitle, .epl-num, .eps, .epl-zero")?.text()
                    ?.filter { it.isDigit() }?.toIntOrNull()
                    ?: Regex("""Episode\s?(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        }

        // Jika tidak ada episode, tambahkan satu episode dari URL saat ini
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.reversed())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document

        document.select("iframe, iframe[data-src]").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"

            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("disqus")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        val serverOptions = document.select("select.mirror option, ul.mserver li a, .select-server option, [data-index], div.mirror-stream a")
        for (option in serverOptions) {
            val embedUrl = option.attr("value").ifBlank { option.attr("data-em") }.ifBlank { option.attr("href") }
            if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
