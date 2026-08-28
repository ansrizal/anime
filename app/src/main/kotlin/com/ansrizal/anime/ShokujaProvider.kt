package com.ansrizal.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import com.lagradost.nicehttp.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class ShokujaProvider : MainAPI() {
    override var mainUrl = "https://x6.shokuja.uk"
    override var name = "Shokuja Anime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Update",
        "$mainUrl/genre/action/page/" to "Action Anime",
        "$mainUrl/genre/isekai/page/" to "Isekai Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val document = app.get(url).document
        val homeItems = document.select("article, div.bsx, div.animepost").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 a, .title a, a[title]")?.text() ?: this.selectFirst("a[title]")?.attr("title") ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("article, div.bsx, div.animepost, div.box-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.replace("Nonton Anime ", "")?.trim() ?: "Shokuja Anime"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst("div.fotoanime img, div.thumb img")?.attr("src"))
        val description = document.selectFirst("div.sinopc, div.entry-content, div.synopsis")?.text()?.trim()

        val episodes = document.select("li[data-index], div.eplister li").mapNotNull { elem ->
            val epUrl = fixUrl(elem.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val epName = elem.selectFirst("a")?.text() ?: "Episode"
            val epNum = elem.selectFirst("span.epstitle")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes.ifEmpty {
                listOf(newEpisode(url) { this.name = "Full Stream"; this.episode = 1 })
            })
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
        document.select("iframe").forEach { iframe ->
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
                    ExtractorLink(
                        source = name,
                        name = "Shokuja HLS Stream",
                        url = src,
                        referer = mainUrl,
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        }

        return true
    }
}