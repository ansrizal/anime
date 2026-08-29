package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class sokujaProvider : MainAPI() {
    override var mainUrl = "https://x6.sokuja.uk/"
    override var name = "sokuja Anime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "rilisan-anime-terbaru/" to "Latest Update",
        "genre/action/" to "Action Anime",
        "genre/isekai/" to "Isekai Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            val data = request.data.removeSuffix("/")
            if (data.contains("?")) {
                "$mainUrl/${data.substringBefore("?")}/page/$page/?${data.substringAfter("?")}"
            } else {
                "$mainUrl/$data/page/$page/"
            }
        }.replace("(?<!:)/{2,}".toRegex(), "/")

        val document = app.get(url).document
        val homeItems = document.select("div.listupd article, article.bs, div.bs, div.bsx, div.ml-item, div.item, article, div.uta").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt, h2, h3, .title, a[title]")?.text()?.trim() 
            ?: this.selectFirst("a[title]")?.attr("title") 
            ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("abs:data-src")
            ?: img?.attr("abs:data-lazy-src")
            ?: img?.attr("abs:src")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.listupd article, article.bs, div.bs, div.bsx, div.ml-item, div.item, article, div.uta").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.replace("Nonton Anime ", "")?.trim() ?: "sokuja Anime"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst("div.fotoanime img, div.thumb img")?.attr("src"))
        val description = document.selectFirst("div.sinopc, div.entry-content, div.synopsis")?.text()?.trim()

        val episodes = document.select("li[data-index], div.eplister li, div.listeps li, .eplister ul li").mapNotNull { elem ->
            val a = elem.selectFirst("a") ?: return@mapNotNull null
            val epUrl = fixUrl(a.attr("href"))
            val epName = a.text().trim()
            val epNum = elem.selectFirst("span.epstitle, .epl-num")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: Regex("Episode\\s?(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(
                DubStatus.Subbed,
                episodes.ifEmpty {
                    listOf(newEpisode(url) { this.name = "Full Stream"; this.episode = 1 })
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Check iframe embeds
        document.select("iframe").asIterable().forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains("streamtape")) {
                loadExtractor(src, subtitleCallback, callback)
            } else if (src.contains("filemoon")) {
                loadExtractor(src, subtitleCallback, callback)
            } else if (src.contains("dood") || src.contains("ds2play")) {
                loadExtractor(src, subtitleCallback, callback)
            } else if (src.endsWith(".m3u8") || src.contains(".m3u8?")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "sokuja HLS Stream",
                        url = src,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = Qualities.P1080.value
                    }
                )
            }
        }

        return true
    }
}