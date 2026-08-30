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
        return app.get(url, headers = defaultHeaders, timeout = 30)
    }

    // [PERBAIKAN KATEGORI] - Sesuaikan path dengan struktur web (anime-list, genres)
    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime-list/" to "Daftar Anime", 
        "genres/action/" to "Action Anime",
        "genres/isekai/" to "Isekai Anime"
    )

    // [PERBAIKAN URL] - Menghilangkan double slash dan menangani kategori dengan benar
    private fun buildPageUrl(path: String, page: Int): String {
        val base = mainUrl.removeSuffix("/")
        val cleanPath = path.trim('/')
        
        return when {
            page <= 1 -> if (cleanPath.isEmpty()) base else "$base/$cleanPath"
            cleanPath.isEmpty() -> "$base/page/$page/"
            else -> "$base/$cleanPath/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = buildPageUrl(path, page)

        val res = request(url)
        val document = res.document

        // Kumpulkan semua elemen yang potensial sebagai kartu anime
        val potentialItems = mutableListOf<Element>()

        // Daftar selector umum untuk kartu anime
        val selectors = listOf(
            "div.bsx", "div.listupd article", "div.utao", "div.uta", "div.luf",
            "article.bs", "div.post-show article", "div.bxb article",
            "div.swiper-slide", "div.animposx", "div.bs", "div.animepost",
            "div.anime-list", "div.anime-item", "div.episodelist article",
            "div.item", "div.anime-card", "div.card", "div.thumb-item"
        )

        for (sel in selectors) {
            val elements = document.select(sel)
            if (elements.isNotEmpty()) {
                potentialItems.addAll(elements)
            }
        }

        // Jika masih kosong, coba ambil semua link yang mengarah ke /anime/ (fallback)
        val finalItems = if (potentialItems.isEmpty()) {
            // Ambil semua <a> yang href-nya mengandung "/anime/" kecuali yang mengandung "/genre/" dll
            val animeLinks = document.select("a[href*='/anime/']")
                .toList()  // <- Konversi ke List agar filter Kotlin digunakan
                .filter { a ->
                    val href = a.attr("href")
                    !href.contains("/genre/") && !href.contains("/category/") &&
                    !href.contains("/page/") && !href.contains("/tag/") &&
                    !href.contains("/bookmark/") && !href.contains("/jadwal-rilis/")
                }
            // Untuk setiap link, cari elemen pembungkus terdekat (parent) untuk dijadikan item
            animeLinks.mapNotNull { a ->
                var parent = a.parent()
                // Naik sampai 5 level untuk menemukan elemen yang memiliki gambar atau class tertentu
                var container: Element? = a
                for (i in 0..5) {
                    if (parent != null) {
                        // Jika parent memiliki img atau class yang menandakan kartu, gunakan parent
                        if (parent.selectFirst("img") != null || parent.hasClass("bsx") || parent.hasClass("item") || parent.hasClass("post")) {
                            container = parent
                            break
                        }
                        parent = parent.parent()
                    }
                }
                container
            }.distinctBy { it?.outerHtml() }.filterNotNull()
        } else {
            potentialItems
        }

        val homeItems = finalItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems),
            hasNext = homeItems.isNotEmpty()
        )
    }

    // [PERBAIKAN GAMBAR] - Menambahkan data-original dan filter placeholder
    private fun Element.toSearchResult(): SearchResponse? {
        // Cari link utama
        val linkElement = selectFirst("a[href]") ?: return null
        var href = fixUrl(linkElement.attr("href"))

        // Filter URL tidak valid
        val invalid = listOf("/genre/", "/category/", "/page/", "/bookmark/", "/jadwal-rilis/", "/tag/")
        if (invalid.any { href.contains(it) }) return null
        if (href.removeSuffix("/") == mainUrl.removeSuffix("/")) return null

        // Ambil judul dari berbagai sumber
        val title = selectFirst(".tt, h2, h3, h4, .title, .entry-title, .post-title, .ttname, .anime-title, .judul, .name, .item-title")
            ?.text()?.trim()
            ?: linkElement.attr("title").trim().ifEmpty { null }
            ?: linkElement.text().trim().ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.isEmpty()) return null

        // Ambil gambar dari berbagai atribut (data-original ditambahkan sebagai prioritas utama)
        val img = selectFirst("img")
        var rawImg = img?.attr("data-original")
            ?: img?.attr("data-src")
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("src")
            ?: img?.attr("srcset")?.substringBefore(" ")
            ?: selectFirst("div.thumb img")?.attr("data-original")
            ?: selectFirst("div.thumb img")?.attr("data-src")
            ?: selectFirst("div.thumb img")?.attr("src")

        // Filter URL placeholder yang membuat gambar jadi abu-abu
        if (rawImg != null && (rawImg.contains("placeholder") || rawImg.contains("default") || rawImg.contains("spacer") || rawImg.contains("blank"))) {
            rawImg = null
        }

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

        // Gunakan selector yang sama seperti di getMainPage
        val selectors = listOf(
            "div.bsx", "div.listupd article", "div.utao", "div.uta", "div.luf",
            "article.bs", "div.animposx", "div.bs", "div.animepost"
        )
        val items = mutableListOf<Element>()
        for (sel in selectors) {
            items.addAll(document.select(sel))
        }

        // Fallback jika kosong
        val finalItems = if (items.isEmpty()) {
            document.select("a[href*='/anime/']")
                .toList() // Konversi ke List
                .filter { !it.attr("href").contains("/genre/") && !it.attr("href").contains("/page/") }
                .mapNotNull { a ->
                    var parent = a.parent()
                    for (i in 0..5) {
                        if (parent != null && parent.selectFirst("img") != null) {
                            return@mapNotNull parent
                        }
                        parent = parent?.parent()
                    }
                    null
                }.distinctBy { it.outerHtml() }
        } else items

        return finalItems.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        // Cari link ke daftar episode
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

        val title = targetDoc.selectFirst("h1.entry-title, h1, .infotable h1, .post-title, .anime-title")?.text()
            ?.replace("Nonton Anime ", "")
            ?.replace("Subtitle Indonesia", "")
            ?.trim() ?: "Sokuja Anime"

        var rawPoster = targetDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("data-src")
            ?: targetDoc.selectFirst("div.fotoanime img, div.thumb img, .poster img")?.attr("src")
        rawPoster = if (rawPoster?.startsWith("//") == true) "https:$rawPoster" else rawPoster
        val poster = fixUrlNull(rawPoster)

        val description = targetDoc.selectFirst("div.sinopc, div.entry-content, div.synopsis, [itemprop=description], .deskripsi")?.text()?.trim()

        // [PERBAIKAN EPISODE] - Mengambil semua link episode berdasarkan pola URL
        val episodes = mutableListOf<Episode>()

        // Cara paling universal untuk web anime: Ambil semua tag <a> yang href-nya mengandung '/episode/'
        val episodeLinks = targetDoc.select("a[href*='/episode/']")

        if (episodeLinks.isNotEmpty()) {
            episodeLinks.forEachIndexed { index, a ->
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.text().trim().ifEmpty { "Episode ${index + 1}" }
                
                // Ekstrak nomor episode dari nama atau URL
                val epNum = Regex("""Episode\s?(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        } else {
            // Fallback jika tidak ada link episode (biasanya karena JS/AJAX)
            val watchLink = targetDoc.selectFirst("a[href*='/episode/'], a[href*='/stream/'], a[href*='/watch/']")
            if (watchLink != null) {
                val epUrl = fixUrl(watchLink.attr("href"))
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            } else {
                // Fallback terakhir jika halamannya langsung episode
                episodes.add(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            // Hapus duplikat dan urutkan terbalik (biasanya episode terbaru di atas)
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

        // Cari iframe
        document.select("iframe, iframe[data-src]").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"

            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("disqus")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // Cari server mirror
        val serverOptions = document.select("select.mirror option, ul.mserver li a, .select-server option, [data-index], div.mirror-stream a, .server a, .mirror a")
        for (option in serverOptions) {
            val embedUrl = option.attr("value").ifBlank { option.attr("data-em") }.ifBlank { option.attr("href") }
            if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
