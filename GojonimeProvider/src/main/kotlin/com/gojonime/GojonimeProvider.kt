package com.gojonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class GojonimeProvider : MainAPI() {
    override var mainUrl = "https://gojonime.net"
    override var name = "Gojonime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "on-going-anime/page/%d/" to "On-Going Anime",
        "completed-anime/page/%d/" to "Completed Anime",
        "movie/page/%d/" to "Movie Anime",
        "anime/page/%d/?order=update" to "Latest Update",
        "anime/page/%d/?order=popular" to "Most Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        val url = if (page <= 1) {
            "$mainUrl/${path.replace("page/1/", "")}"
        } else {
            "$mainUrl/$path"
        }

        val doc = app.get(url).document
        val home = doc.select("div.listupd article, div.bsx, article.bs, article.stylefor")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href.isBlank() || href.contains("/page/")) return null

        val rawTitle = this.selectFirst("h2, .tt h2, .entry-title")?.text()
            ?: a.attr("title")
        if (rawTitle.isBlank()) return null

        val title = rawTitle
            .replace(Regex("(?i)Episode\\s*\\d+"), "")
            .replace(Regex("(?i)Subtitle\\s+Indonesia"), "")
            .replace(Regex("(?i)Sub\\s+Indo"), "")
            .replace(Regex("(?i)\\[END]"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val img = this.selectFirst("img")
        val rawImg = img?.attr("data-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
            ?: img?.attr("src")

        val posterUrl = fixImageUrl(rawImg)

        val typeStr = this.selectFirst(".typez, .eggtype, .bt .typez, span i")?.text().orEmpty()
        val tvType = when {
            href.contains("/movie/") || typeStr.contains("Movie", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            typeStr.contains("OVA", ignoreCase = true) || typeStr.contains("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val epNum = this.selectFirst(".epx, .eggepisode, .bt .epx")?.text()
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url == null || url.startsWith("data:")) return null
        val cleanUrl = url.substringBefore("?")
        return fixUrlNull(cleanUrl)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.listupd article, div.bsx, article.bs, article.stylefor")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        var animeUrl = fixUrl(url)
        var doc = app.get(animeUrl).document

        if (!animeUrl.contains("/anime/")) {
            val parentLink = doc.selectFirst(".ts-breadcrumb a[href*=\"/anime/\"], .year a[href*=\"/anime/\"], .naveps a[href*=\"/anime/\"]")?.attr("href")
            if (parentLink != null) {
                animeUrl = fixUrl(parentLink)
                doc = app.get(animeUrl).document
            }
        }

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".entry-title")?.text()?.trim()
            ?: "Unknown"

        val poster = fixImageUrl(
            doc.selectFirst("div.thumb img, div.thumbook img")?.attr("src")
                ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        )

        val synopsis = doc.select("div.entry-content p, div.desc p, div.mindesc").text().trim()
        val genres = doc.select(".genxed a").map { it.text().trim() }
        val rating = doc.selectFirst("div.rating strong")?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()

        val statusStr = doc.select("div.spe span:contains(Status)").text()
        val showStatus = when {
            statusStr.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            statusStr.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val typeStr = doc.select("div.spe span:contains(Tipe), div.spe span:contains(Type)").text()
        val tvType = when {
            typeStr.contains("Movie", ignoreCase = true) || animeUrl.contains("/movie/") -> TvType.AnimeMovie
            typeStr.contains("OVA", ignoreCase = true) || typeStr.contains("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = doc.select(".eplister ul li, div.bxcl.epcheck ul li, div.bxcl ul li").mapNotNull { ep ->
            val a = ep.selectFirst("a[href]") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val epNumStr = ep.selectFirst(".epl-num")?.text()?.trim()
            val epNum = epNumStr?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            val epName = ep.selectFirst(".epl-title")?.text()?.trim() ?: "Episode $epNumStr"
            val epDate = ep.selectFirst(".epl-date")?.text()?.trim()

            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
                this.posterUrl = poster
                if (!epDate.isNullOrBlank()) {
                    this.description = "Rilis: $epDate"
                }
            }
        }.reversed()

        val finalEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(animeUrl) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return if (tvType == TvType.AnimeMovie && finalEpisodes.size <= 1) {
            newMovieLoadResponse(title, animeUrl, TvType.AnimeMovie, finalEpisodes.first().data) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                this.score = Score.from10(rating)
            }
        } else {
            newAnimeLoadResponse(title, animeUrl, tvType) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                this.showStatus = showStatus
                this.score = Score.from10(rating)
                addEpisodes(DubStatus.Subbed, finalEpisodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(fixUrl(data)).document

        val options = doc.select("#selectserver option, select.mirror option, .mobius option, .select-server option")
        options.forEach { option ->
            val value = option.attr("value").trim()
            if (value.isNotBlank()) {
                var rawIframeOrUrl = ""
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    rawIframeOrUrl = value
                } else if (value.contains("<iframe")) {
                    rawIframeOrUrl = Jsoup.parse(value).selectFirst("iframe")?.attr("src") ?: ""
                } else {
                    try {
                        val decoded = base64Decode(value)
                        rawIframeOrUrl = if (decoded.contains("<iframe")) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                        } else {
                            decoded
                        }
                    } catch (_: Exception) {
                        rawIframeOrUrl = value
                    }
                }

                if (rawIframeOrUrl.isNotBlank()) {
                    val streamUrl = fixStreamUrl(rawIframeOrUrl)
                    processStreamUrl(streamUrl, subtitleCallback, callback)
                }
            }
        }

        val defaultIframeSrc = doc.selectFirst("#pembed iframe, #embed_holder iframe, .player-embed iframe")?.attr("src")
        if (!defaultIframeSrc.isNullOrBlank()) {
            val streamUrl = fixStreamUrl(defaultIframeSrc)
            processStreamUrl(streamUrl, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun processStreamUrl(
        streamUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (streamUrl.contains("berkasdrive.com")) {
            if (streamUrl.contains("id=")) {
                val base64Id = streamUrl.substringAfter("id=").substringBefore("&")
                try {
                    val decodedUrl = base64Decode(base64Id)
                    if (decodedUrl.startsWith("http")) {
                        val fixedDecoded = fixStreamUrl(decodedUrl)
                        loadExtractor(fixedDecoded, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
            if (streamUrl.contains("backup=")) {
                val base64Backup = streamUrl.substringAfter("backup=").substringBefore("&")
                try {
                    val decodedUrl = base64Decode(base64Backup)
                    if (decodedUrl.startsWith("http")) {
                        val fixedDecoded = fixStreamUrl(decodedUrl)
                        loadExtractor(fixedDecoded, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
            try {
                val berkasDoc = app.get(streamUrl, referer = mainUrl).document
                val berkasIframe = berkasDoc.selectFirst("iframe")?.attr("src")
                if (!berkasIframe.isNullOrBlank()) {
                    val fixedBerkas = fixStreamUrl(berkasIframe)
                    loadExtractor(fixedBerkas, subtitleCallback, callback)
                }
                val videoSrc = berkasDoc.selectFirst("video source, source")?.attr("src")
                if (!videoSrc.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            "Berkasdrive",
                            "Berkasdrive",
                            fixUrl(videoSrc)
                        ) {
                            this.referer = streamUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (_: Exception) {}
        }

        if (streamUrl.contains("short.icu") || streamUrl.contains("short.ink") ||
            streamUrl.contains("yihdraplay") || streamUrl.contains("gojonime.my.id")
        ) {
            try {
                ShortIcuExtractor().getUrl(streamUrl, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        loadExtractor(streamUrl, subtitleCallback, callback)
    }

    private fun fixStreamUrl(rawUrl: String): String {
        var url = fixUrl(rawUrl)
        val domainMappings = mapOf(
            "short.ink" to "short.icu",
            "desustream.me/otakuhade/baru/" to "desustream.info/dstream/otakustream/index.php",
            "desustream.me/moedesu/stream/hd/" to "desustream.info/dstream/moedesu/hd/index.php",
            "desustream.com/moedesu/hd/" to "desustream.info/dstream/moedesu/index.php",
            "desustream.me/beta/stream/hd/" to "desustream.info/dstream/otakuwatch2/hd/index.php",
            "desustream.me/ondesu/hd/index.php" to "desustream.info/dstream/ondesu/hd/index.php",
            "desustream.me/arcg/done/" to "desustream.info/dstream/arcg/",
            "desustream.me/otakustream/?" to "desustream.info/dstream/otakustream/index.php?",
            "desustream.me/desudrive/player.php" to "desustream.info/dstream/desudrive/player.php",
            "desustream.me/desudesuhd/" to "desustream.info/dstream/desudesuhd/index.php",
            "desustream.me/desudesuhd3/" to "desustream.info/dstream/desudesuhd3/index.php",
            "desustream.me/arcg" to "desustream.info/dstream/arcg"
        )
        domainMappings.forEach { (old, new) ->
            url = url.replace(old, new)
        }
        if (url.contains("short.ink")) {
            url = url.replace("short.ink", "gojonime.my.id")
        }
        if (url.contains("short.icu")) {
            url = url.replace("short.icu", "yihdraplay.my.id")
        }
        if (Regex("""https?://(player\.|play\.)?abyssplayer\.com""").containsMatchIn(url)) {
            url = url.replace(Regex("""https?://(player\.|play\.)?abyssplayer\.com"""), "https://harenchidesu.my.id")
        }
        return url
    }
}
