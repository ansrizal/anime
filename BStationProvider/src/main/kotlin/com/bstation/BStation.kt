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
        "anime" to "Anime",
        "timeline" to "Jadwal Tayang",
        "trending" to "Trending",
        "short-drama" to "Dracin",
        "?bstar_from=bstar-web.homepage.recommend.all" to "Direkomendasikan untukmu",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val document = app.get(url).document
        val home = mutableListOf<HomePageList>()

        if (request.data == "anime") {
            // Banner section
            val banner = document.select("div.banner-warp div.banner-item").mapNotNull {
                val a = it.selectFirst("a.video-play") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                val title = it.selectFirst("div.banner-content picture img")?.attr("alt")?.ifEmpty { null }
                    ?: it.selectFirst("div.banner-desc")?.text()?.take(20) ?: "Banner"
                val posterUrl = it.selectFirst("div.banner-image")?.attr("style")?.let { style ->
                    Regex("""url\((.*?)\)""").find(style)?.groupValues?.get(1)?.substringBefore("@")
                }
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }
            }
            if (banner.isNotEmpty()) {
                home.add(HomePageList("Unggulan", banner, isHorizontalImages = true))
            }

            // Trending & Recommended in Anime page
            document.select("div.trending, div.recommended").forEach { section ->
                val title = section.selectFirst("h3.title")?.text() ?: "Anime"
                val items = section.select("div.card-item").mapNotNull {
                    it.toSearchResult()
                }
                if (items.isNotEmpty()) {
                    home.add(HomePageList(title, items))
                }
            }

            // Calendar in Anime page
            val calendar = document.select("div.calendar div.card-item").mapNotNull {
                it.toSearchResult()
            }
            if (calendar.isNotEmpty()) {
                home.add(HomePageList("Jadwal Tayang", calendar))
            }

        } else {
            // Populer section (UGC)
            val popular = document.select("li.section__list__item").mapNotNull {
                it.toSearchResult()
            }
            if (popular.isNotEmpty()) {
                home.add(HomePageList("Populer", popular))
            }

            // Anime section (OGV)
            val anime = document.select("li.scroll-wrap__list__item, div.card-item").mapNotNull {
                it.toSearchResult()
            }
            if (anime.isNotEmpty()) {
                home.add(HomePageList("Anime", anime))
            }
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
        val title = this.selectFirst(".card-title")?.text()
            ?: this.selectFirst("img")?.attr("alt") 
            ?: this.selectFirst(".bstar-video-card__title")?.text() 
            ?: this.selectFirst(".bstar-video-card__title-text")?.text()
            ?: return null
            
        var posterUrl = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
        
        if (posterUrl == null || posterUrl.contains("data:image")) {
             posterUrl = this.selectFirst("source")?.attr("srcset")
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
        return document.select(".bstar-video-card, li.section__list__item, li.scroll-wrap__list__item, .card-item").mapNotNull {
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
            ?: document.selectFirst(".video-info-desc")?.text()

        return if (url.contains("/play/")) {
            // Attempt to extract episodes from the play page
            val episodes = document.select(".ep-list .ep-item, .episode-list li").mapNotNull { ep ->
                val epHref = ep.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                val epTitle = ep.selectFirst(".ep-title, .title")?.text() ?: ep.text()
                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                
                newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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
