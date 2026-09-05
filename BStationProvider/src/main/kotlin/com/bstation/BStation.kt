package com.bstation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class BStation : MainAPI() {
    override var mainUrl = "https://www.bilibili.tv/id"
    override var name = "BStation"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "" to "Populer",
        "timeline" to "Anime Schedule",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val document = app.get(url).document
        val home = mutableListOf<HomePageList>()

        // Populer section (UGC)
        val popular = document.select("li.section__list__item").mapNotNull {
            it.toSearchResult()
        }
        if (popular.isNotEmpty()) {
            home.add(HomePageList("Populer", popular))
        }

        // Anime section (OGV)
        val anime = document.select("li.scroll-wrap__list__item").mapNotNull {
            it.toSearchResult()
        }
        if (anime.isNotEmpty()) {
            home.add(HomePageList("Anime", anime))
        }

        return newHomePageResponse(home, false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href").let { 
            if (it.startsWith("//")) "https:$it" 
            else if (it.startsWith("/")) "https://www.bilibili.tv$it"
            else it 
        }
        val title = this.selectFirst("img")?.attr("alt") 
            ?: this.selectFirst(".bstar-video-card__title")?.text() 
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()
            ?: return null
            
        var posterUrl = this.selectFirst("img")?.attr("src")
        if (posterUrl == null || posterUrl.contains("data:image")) {
             posterUrl = this.selectFirst("source")?.attr("srcset")
        }
        if (posterUrl == null) {
            posterUrl = this.selectFirst("img")?.attr("data-src")
        }
        
        val finalPoster = posterUrl?.substringBefore("@")

        return if (href.contains("/play/")) {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = finalPoster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = finalPoster
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/search-result?q=$query"
        val document = app.get(url).document
        return document.select(".bstar-video-card, li.section__list__item, li.scroll-wrap__list__item").mapNotNull {
            it.toSearchResult()
        }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") 
            ?: document.selectFirst("h1")?.text()
            ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return if (url.contains("/play/")) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, listOf()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        return false
    }
}
