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
        "page/%d" to "Keluaran Terbaru",
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    // Proxy gambar alternatif (weserv.nl)
    private fun getProxiedImage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fixedUrl = fixUrl(url).replace("https://", "").replace("http://", "")
        return "https://images.weserv.nl/?url=$fixedUrl&w=300&h=450&fit=cover&a=center"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${mainUrl}/${request.data}&page=$page").document
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
        val anchor = this.selectFirst("div.bsx > a") ?: this.selectFirst("a")
        val title = anchor?.attr("title")?.trim() ?: this.selectFirst("div.tt h2")?.text()?.trim().orEmpty()
        val href = fixUrl(anchor?.attr("href").orEmpty())
        
        // Baca data-src (Lazy Load) terlebih dahulu, jika kosong baru baca src
        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: imgTag?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = getProxiedImage(rawPoster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
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
        
        // 1. Ambil Data Series
        val seriesInfo = document.selectFirst("div.single-info.bixbox")
        val title = seriesInfo?.selectFirst("div.infolimit h2")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            
        val imgTag = seriesInfo?.selectFirst("div.thumb img") ?: document.selectFirst("div.mvelement img")
        val rawPoster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: imgTag?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            
        val description = seriesInfo?.selectFirst("div.desc")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()

        val seriesUrl = document.selectFirst("#mainepisode .headlist .det h2 a")?.attr("href")?.let { fixUrl(it) } ?: url

        // 2. Ambil Daftar Episode (Bisa dari Sidebar ATAU dari bagian bawah halaman)
        var episodeElements = document.select("#mainepisode .episodelist ul li")
        
        // Jika halaman ini adalah halaman Series murni, episodeElements dari sidebar akan kosong.
        // Kita cari daftar episode yang ada di bagian bawah halaman (div.bixbox .listupd .bsx).
        if (episodeElements.isEmpty()) {
            episodeElements = document.select("div.bixbox div.listupd div.bsx")
        }
        
        val episodes = episodeElements.mapNotNull { el ->
            // Ambil tag <a> utama (baik dari sidebar maupun dari listupd)
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            
            // Ambil judul (dari h3 sidebar atau dari title di <a> listupd)
            val rawTitle = el.selectFirst(".playinfo h3")?.text()?.trim() 
                ?: a.attr("title").trim()
                ?: el.selectFirst("div.inf h2")?.text()?.trim().orEmpty()
                
            val dateText = el.selectFirst(".playinfo span")?.text()?.trim().orEmpty()
            
            // Bersihkan judul
            val epNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.get(1)
                ?: Regex("Eps\\s*(\\d+)", RegexOption.IGNORE_CASE).find(dateText)?.groupValues?.get(1)
            
            val cleanName = if (epNumber != null) "Episode $epNumber" else rawTitle.replace("Subtitle Indonesia", "").trim()
            
            // Ambil poster khusus episode jika ada, jika tidak pakai poster series
            val epImgTag = el.selectFirst("img")
            val epRawPoster = epImgTag?.attr("data-src")?.takeIf { it.isNotBlank() } ?: epImgTag?.attr("src") ?: rawPoster

            newEpisode(epHref) {
                this.name = cleanName
                this.posterUrl = getProxiedImage(epRawPoster)
                this.description = dateText.replace(Regex("Eps\\s*\\d+\\s*-\\s*", RegexOption.IGNORE_CASE), "Rilis: ")
            }
        }.distinctBy { it.data }.reversed() // Hapus duplikat & urutkan dari terlama

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
            this.posterUrl = getProxiedImage(rawPoster)
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
        
        // Ambil iframe default yang biasanya sudah ada di halaman
        val defaultIframe = document.selectFirst("div.player-embed iframe, div.megavid iframe")?.attr("src")
        if (!defaultIframe.isNullOrBlank()) {
            loadExtractor(defaultIframe, subtitleCallback, callback)
        }

        // Ambil dari dropdown server
        val options = document.select(".mobius option")
        for (server in options) {
            val base64 = server.attr("value")
            if (base64.isNotBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val doc = Jsoup.parse(decoded)
                    val href = doc.select("iframe").attr("src").ifBlank { doc.select("IFRAME").attr("SRC") }
                    
                    if (href.isNotBlank() && href != defaultIframe) {
                        loadExtractor(href, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Abaikan
                }
            }
        }
        return true
    }
}
