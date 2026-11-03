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

    // Fungsi pembantu untuk melewati proteksi Cloudflare gambar
    private fun getProxiedImage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fixedUrl = fixUrl(url)
        // Kita gunakan wsrv.nl sebagai proxy gambar
        return "https://wsrv.nl/?url=${fixedUrl.replace("https://", "")}&n=-1"
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
        
        // Ambil src gambar mentah
        val rawPoster = this.selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = getProxiedImage(rawPoster) // Gunakan proxy
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
        
        val seriesInfo = document.selectFirst("div.single-info.bixbox")
        val title = seriesInfo?.selectFirst("div.infolimit h2")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            
        val rawPoster = seriesInfo?.selectFirst("div.thumb img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            
        val description = seriesInfo?.selectFirst("div.desc")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()

        val seriesUrl = document.selectFirst("#mainepisode .headlist .det h2 a")?.attr("href")?.let { fixUrl(it) } ?: url

        val episodeElements = document.select("#mainepisode .episodelist ul li")
        
        val episodes = episodeElements.mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val rawTitle = li.selectFirst(".playinfo h3")?.text()?.trim() ?: a.attr("title").trim()
            val dateText = li.selectFirst(".playinfo span")?.text()?.trim().orEmpty()
            
            val epNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.get(1)
                ?: Regex("Eps\\s*(\\d+)", RegexOption.IGNORE_CASE).find(dateText)?.groupValues?.get(1)
            
            val cleanName = if (epNumber != null) "Episode $epNumber" else rawTitle.replace("Subtitle Indonesia", "").trim()

            newEpisode(epHref) {
                this.name = cleanName
                this.posterUrl = getProxiedImage(rawPoster) // Gunakan proxy
                this.description = dateText.replace(Regex("Eps\\s*\\d+\\s*-\\s*", RegexOption.IGNORE_CASE), "Rilis: ")
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
            this.posterUrl = getProxiedImage(rawPoster) // Gunakan proxy
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
        val options = document.select(".mobius option")
        
        for (server in options) {
            val base64 = server.attr("value")
            if (base64.isNotBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val doc = Jsoup.parse(decoded)
                    var href = doc.select("iframe").attr("src")
                    
                    // Cek jika iframe tidak langsung ada src (misal pakai atribut lain)
                    if (href.isBlank()) {
                        href = doc.select("IFRAME").attr("SRC") // Kadang kapital
                    }
                    
                    if (href.isNotBlank()) {
                        loadExtractor(href, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Abaikan error pada server tertentu
                }
            }
        }
        return true
    }
}
