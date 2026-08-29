package com.donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import com.lagradost.nicehttp.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DonghubProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/" to "Latest Releases",
        "status/ongoing/" to "Series Ongoing",
        "status/completed/" to "Series Completed",
        "type/movie/" to "Movie"
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
        }.replace("//page", "/page")
        val document = app.get(url).document
        val items = document.select("div.listupd article, article.bs, div.bs, div.bsx, div.ml-item, div.item, article, div.uta").asIterable().mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("a[title]")?.attr("title")?.trim() 
            ?: selectFirst(".tt, h2, h3")?.text()?.trim() 
            ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("img")?.getsrcAttribute())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("$mainUrl/page/$i/?s=$query").document
            val result = document.select("div.listupd article, article.bs, div.bs, div.bsx, div.ml-item, div.item, article, div.uta").asIterable().mapNotNull { it.toSearchResult() }
            if (result.isEmpty()) break
            list.addAll(result)
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", true)

        var poster = document.select("div.ime > img").first()?.getsrcAttribute()
            ?: document.select("meta[property=og:image]").attr("content")

        val epBlocks =
            document.select(".eplister li").ifEmpty {
                document.select("div.list-episode .episode-item")
            }.ifEmpty {
                document.select("#episodes a")
            }

        return if (!isMovie) {
            val episodes = epBlocks.map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text() ?: ep.text()
                newEpisode(link) {
                    this.name = epTitle.trim()
                    this.posterUrl = fixUrlNull(poster)
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val movieLink = document.selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieLink, TvType.Movie, movieLink) {
                this.posterUrl = fixUrlNull(poster)
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
        val document = app.get(data).document
        document.select(".mobius option").asIterable().forEach { item ->
            val base64 = item.attr("value")
            if (base64.isNotBlank()) {
                val decoded = base64Decode(base64)
                val doc = Jsoup.parse(decoded)
                val iframe = doc.select("iframe").attr("src")
                loadExtractor(fixUrl(iframe), subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.getsrcAttribute(): String {
        val src = this.attr("src")
        val dataSrc = this.attr("data-src")
        return when {
            dataSrc.startsWith("http") -> dataSrc
            src.startsWith("http") -> src
            else -> ""
        }
    }
}