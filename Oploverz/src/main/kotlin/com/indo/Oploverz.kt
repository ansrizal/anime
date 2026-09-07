package com.indo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.util.Base64

class Oploverz : MainAPI() {
    override var mainUrl = "https://oploverz.ch"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Completed", true) == true || t?.contains("Tamat", true) == true -> ShowStatus.Completed
                t?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Update Terbaru",
        "$mainUrl/series/?status=&type=&order=title" to "Daftar Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSeries = request.data.contains("/series/")
        val url = if (isSeries) {
            // Series list: base URL with query parameters, add page param
            val base = request.data.substringBefore('?')
            val params = request.data.substringAfter('?')
            if (page <= 1) request.data else "$base?$params&page=$page"
        } else {
            // Homepage updates
            if (page <= 1) "$mainUrl/" else "${request.data}$page/"
        }

        val document = app.get(url).document

        val home = if (isSeries) {
            // Series list page: items inside .listupd with .bsx
            document.select("div.listupd div.bsx, div.listupd article.bs, div.bsx").asIterable().mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .tt, .title")?.text()?.trim()
                    ?: el.ownText().trim()
                    ?: a.attr("title").ifBlank { null }
                    ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
                }
                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            }.distinctBy { it.url }
        } else {
            // Homepage updates: items inside .listupd with .bsx
            document.select("div.bsx").asIterable().mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val title = el.selectFirst("div.tt")?.ownText()?.trim()
                    ?: el.selectFirst("h2")?.text()?.trim()
                    ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
                }
                val epText = el.selectFirst("span.epx")?.text()?.trim() ?: ""
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val animeUrl = if (epNum != null) episodeUrlToAnimeUrl(href) else href
                newAnimeSearchResponse(title, fixUrl(animeUrl), TvType.Anime) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun episodeUrlToAnimeUrl(episodeUrl: String): String {
        val slug = episodeUrl.trimEnd('/').substringAfterLast("/")
        val animeSlug = slug.replace(Regex("-episode-\\d+.*", RegexOption.IGNORE_CASE), "")
        return "$mainUrl/series/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Search uses ?s=query
        val document = app.get("$mainUrl/?s=$query").document

        // Results are inside .listupd with .bsx items
        return document.select("div.listupd div.bsx, div.listupd article.bs, div.bsx").asIterable().mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.tt h4, h4, .tt, h2, h3")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
            }
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.thumb img, img[src*=upload]")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
        }

        val description = document.selectFirst("div.entry-content > p, div.synp p")?.text()?.trim()
        val genres = document.select("a[href*=genres]").asIterable().map { it.text() }.filter { it.isNotBlank() }
        val statusText = document.selectFirst("div.spe span:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim()
        val status = getStatus(statusText)
        val year = document.selectFirst("div.spe span:contains(Released)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val episodes = document.select("div.eplister ul li, ul#episodelist li").asIterable().mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epNum = li.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
            val epTitle = li.selectFirst("div.epl-title")?.text()?.trim() ?: a.text().trim()
            newEpisode(fixUrl(href)) { this.name = epTitle; this.episode = epNum }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster?.let { fixUrl(it) }
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        document.select("div#pembed iframe, div.player-embed iframe, div.video-content iframe")
            .asIterable()
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { null } ?: return@forEach
                if (src.startsWith("http")) handleUrl(src, data, subtitleCallback, callback)
            }

        document.select("select.mirror option").asIterable().forEach { option ->
            val encoded = option.attr("value").ifBlank { null } ?: return@forEach
            val decoded = try { String(Base64.getDecoder().decode(encoded)) } catch (e: Exception) { null } ?: return@forEach
            val src = Regex("src\\s*=\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.getOrNull(1) ?: return@forEach
            if (src.startsWith("http")) handleUrl(src, data, subtitleCallback, callback)
        }

        document.select("a[href*=gofile.io]").asIterable().forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            loadExtractor(href, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun handleUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (url.contains("blogger.com")) {
            handleBloggerUrl(url, referer, subtitleCallback, callback)
        } else {
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun handleBloggerUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        loadExtractor(url, referer, subtitleCallback, callback)

        try {
            val doc = app.get(url).document
            doc.select("script").asIterable().forEach { script ->
                val text = script.data()
                val streamsStart = text.indexOf("\"streams\":[")
                if (streamsStart >= 0) {
                    val from = streamsStart + "\"streams\":[".length
                    val end = text.indexOf(']', from)
                    if (end > from) {
                        val streamsJson = "[" + text.substring(from, end) + "]"
                        tryParseJson<List<ResponseSource>>(streamsJson)?.forEach { source ->
                            callback(
                                newExtractorLink(name, name, source.play_url) {
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = when (source.format_id) {
                                        18 -> 360
                                        22 -> 720
                                        else -> Qualities.Unknown.value
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private data class ResponseSource(
        @param:JsonProperty("play_url") val play_url: String,
        @param:JsonProperty("format_id") val format_id: Int
    )
}
