package com.gojonime

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import kotlinx.coroutines.runBlocking

open class ShortIcuExtractor : StreamWishExtractor() {
    override var name = "Server 1"
    override var mainUrl = "https://short.icu"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "$mainUrl/"),
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
        )

        val serverName = name

        try {
            val responseText = app.get(url, headers = headers).text

            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d.*?\.split\('\|'\)\)""")
            val packedCode = packedRegex.find(responseText)?.value
            val contentToSearch = if (packedCode != null) {
                JsUnpacker(packedCode).unpack() ?: responseText
            } else {
                responseText
            }

            var found = false

            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            m3u8Regex.findAll(contentToSearch).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                M3u8Helper.generateM3u8(
                    serverName,
                    m3u8Url,
                    referer = url,
                    headers = headers
                ).forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            "$serverName - ${link.name}",
                            link.name,
                            link.url,
                            link.type
                        ) {
                            this.referer = link.referer
                            this.headers = link.headers
                            this.quality = link.quality
                            this.extractorData = link.extractorData
                        }
                    )
                }
                found = true
            }

            val videoRegex = Regex("""["'](https?://[^"']+\.(?:mp4|fd)[^"']*)["']""")
            videoRegex.findAll(contentToSearch).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        serverName,
                        serverName,
                        videoUrl
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

            if (!found) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                fileRegex.findAll(contentToSearch).forEach { match ->
                    val fileUrl = match.groupValues[1].replace("\\/", "/")
                    if (fileUrl.startsWith("http")) {
                        if (fileUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                serverName,
                                fileUrl,
                                referer = url,
                                headers = headers
                            ).forEach { link ->
                                callback.invoke(
                                    newExtractorLink(
                                        "$serverName - ${link.name}",
                                        link.name,
                                        link.url,
                                        link.type
                                    ) {
                                        this.referer = link.referer
                                        this.headers = link.headers
                                        this.quality = link.quality
                                        this.extractorData = link.extractorData
                                    }
                                )
                            }
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    serverName,
                                    serverName,
                                    fileUrl
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                        found = true
                    }
                }
            }

            if (!found) {
                super.getUrl(url, referer, subtitleCallback) { link ->
                    runBlocking {
                        callback.invoke(
                            newExtractorLink(
                                "$serverName - ${link.name}",
                                link.name,
                                link.url,
                                link.type
                            ) {
                                this.referer = link.referer
                                this.headers = link.headers
                                this.quality = link.quality
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {
            try {
                super.getUrl(url, referer, subtitleCallback) { link ->
                    runBlocking {
                        callback.invoke(
                            newExtractorLink(
                                "$serverName - ${link.name}",
                                link.name,
                                link.url,
                                link.type
                            ) {
                                this.referer = link.referer
                                this.headers = link.headers
                                this.quality = link.quality
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }
}

class ShortInkExtractor : ShortIcuExtractor() {
    init { name = "Server 1 (ShortInk)" }
    override var mainUrl = "https://short.ink"
}

class GojonimeMyIdExtractor : ShortIcuExtractor() {
    init { name = "Server 1 (Gojonime)" }
    override var mainUrl = "https://gojonime.my.id"
}

class YihdraplayExtractor : ShortIcuExtractor() {
    init { name = "Server 1 (Yihdraplay)" }
    override var mainUrl = "https://yihdraplay.my.id"
}
