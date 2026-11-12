package com.mynimeku

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.Jsoup

open class MyPlayerKuExtractor : ExtractorApi() {
    override var name = "MyPlayerKu"
    override var mainUrl = "https://players.myplayerku.my.id"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://www.mynimeku.com/"),
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
        )

        try {
            // Get redirect Location header without downloading the video body
            val res = app.get(url, headers = headers, allowRedirects = false)
            val location = res.headers["Location"] ?: res.headers["location"]
            val targetUrl = if (!location.isNullOrBlank()) {
                if (location.startsWith("http")) location else "$mainUrl$location"
            } else {
                url
            }

            if (targetUrl != url && !targetUrl.contains("myplayerku.my.id") && !targetUrl.contains("mydriveku.my.id")) {
                val loaded = loadExtractor(targetUrl, url, subtitleCallback, callback)
                if (loaded) return
            }

            val qualityStr = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(url)?.value
                ?: Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(referer.orEmpty())?.value
                ?: ""

            val quality = when {
                qualityStr.contains("1080") -> Qualities.P1080.value
                qualityStr.contains("720") -> Qualities.P720.value
                qualityStr.contains("480") -> Qualities.P480.value
                qualityStr.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            val displayName = if (qualityStr.isNotBlank()) "$name $qualityStr" else name

            callback.invoke(
                newExtractorLink(
                    source = "Mynimeku",
                    name = displayName,
                    url = targetUrl
                ) {
                    this.referer = url
                    this.quality = quality
                }
            )
        } catch (_: Exception) {
            callback.invoke(
                newExtractorLink(
                    source = "Mynimeku",
                    name = name,
                    url = url
                ) {
                    this.referer = referer ?: "https://www.mynimeku.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

class MyDriveKuExtractor : MyPlayerKuExtractor() {
    override var name = "MyDriveKu"
    override var mainUrl = "https://file.mydriveku.my.id"
}

class StreamMyPlayerKuExtractor : MyPlayerKuExtractor() {
    override var name = "MyPlayerKu Stream"
    override var mainUrl = "https://stream.myplayerku.my.id"
}
