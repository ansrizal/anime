package com.anichin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AnichinProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${mainUrl}/${request.data}&page=$page").document
        // Perbaikan: Gunakan "div.listupd article" tanpa ">" agar poster terbaca
        val home = document.select("div.listupd article").asIterable().mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        // Perbaikan Selector: Gunakan div.bsx a img
        val anchor = this.selectFirst("div.bsx a") ?: this.selectFirst("a")
        val title = anchor?.attr("title")?.trim().orEmpty()
        val href = fixUrl(anchor?.attr("href").orEmpty())
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx a img")?.attr("src"))
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            // Perbaikan: URL pencarian halaman 1 tidak pakai /page/1/
            val url = if (i == 1) "${mainUrl}/?s=$query" else "${mainUrl}/page/$i/?s=$query"
            val document = app.get(url).document
            val results = document.select("div.listupd article").asIterable().mapNotNull { it.toSearchResult() }
            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(fixUrl(url)).document
        
        // 1. Ambil Data Series dari Sidebar/Info Box
        val seriesInfo = document.selectFirst("div.single-info.bixbox")
        val title = seriesInfo?.selectFirst("div.infolimit h2")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            
        val poster = seriesInfo?.selectFirst("div.thumb img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            
        val description = seriesInfo?.selectFirst("div.desc")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()

        // Cari URL utama series (tanpa embel-embel episode)
        val seriesUrl = document.selectFirst("#mainepisode .headlist .det h2 a")?.attr("href")?.let { fixUrl(it) } ?: url

        // 2. Ambil Daftar Episode dari Sidebar
        val episodeElements = document.select("#mainepisode .episodelist ul li")
        
        val episodes = episodeElements.mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            
            // Ambil judul asli
            val rawTitle = li.selectFirst(".playinfo h3")?.text()?.trim() ?: a.attr("title").trim()
            
            // Ambil tanggal rilis
            val dateText = li.selectFirst(".playinfo span")?.text()?.trim().orEmpty() // Contoh: "Eps 188 - November 3, 2026"
            
            // Bersihkan judul agar rapi: Ambil angka episodenya saja
            val epNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.get(1)
                ?: Regex("Eps\\s*(\\d+)", RegexOption.IGNORE_CASE).find(dateText)?.groupValues?.get(1)
            
            val cleanName = if (epNumber != null) "Episode $epNumber" else rawTitle.replace("Subtitle Indonesia", "").trim()

            newEpisode(epHref) {
                this.name = cleanName
                this.posterUrl = fixUrlNull(poster)
                // Tampilkan tanggal rilis di deskripsi episode
                this.description = dateText.replace(Regex("Eps\\s*\\d+\\s*-\\s*", RegexOption.IGNORE_CASE), "Rilis: ")
            }
        }.reversed() // Dibalik agar Episode 1 ada di paling atas

        // 3. Kembalikan sebagai TvSeries agar semua episode muncul
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(fixUrl(data)).document
        
        // Ambil semua server dari dropdown <select class="mirror">
        val options = document.select(".mobius option")
        for (server in options) {
            val base64 = server.attr("value")
            if (base64.isNotBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val doc = Jsoup.parse(decoded)
                    val href = doc.select("iframe").attr("src")
                    
                    if (href.isNotBlank()) {
                        // Kirim ke extractor CloudStream
                        loadExtractor(fixUrl(href), subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Abaikan jika gagal decode
                }
            }
        }
        return true
    }
}
