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
            val responseText = app.get(url, headers = headers).text

            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d.*?\.split\('\|'\)\)""")
            val packedCode = packedRegex.find(responseText)?.value
            val contentToSearch = if (packedCode != null) {
                JsUnpacker(packedCode).unpack() ?: responseText
            } else {
                responseText
            }

            val doc = Jsoup.parse(contentToSearch)

            // Extract embed / iframe
            doc.select("iframe[src]").forEach { iframe ->
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.startsWith("http")) {
                    loadExtractor(iframeUrl, url, subtitleCallback, callback)
                }
            }

            // Extract m3u8
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            m3u8Regex.findAll(contentToSearch).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    referer = url,
                    headers = headers
                ).forEach { link ->
                    callback.invoke(link)
                }
            }

            // Extract mp4
            val mp4Regex = Regex("""["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""")
            mp4Regex.findAll(contentToSearch).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}

class MyDriveKuExtractor : ExtractorApi() {
    override var name = "MyDriveKu"
    override var mainUrl = "https://file.mydriveku.my.id"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerKu = MyPlayerKuExtractor()
        playerKu.name = name
        playerKu.getUrl(url, referer, subtitleCallback, callback)
    }
}
