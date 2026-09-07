package com.ansrizal.filmapik

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile

class Filemoonfa : Filesim() {
    override var name = "Filemoon"
    override var mainUrl = "https://byseqekaho.com"
}

class Streamp2p : VidStack() {
    override var name = "StreamP2P"
    override var mainUrl = "https://fiilmapik.strp2p.site"
}

class Abyssplayer : ExtractorApi() {
    override var name = "Hydrax"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hydraxUrl = url.replace("abyssplayer.com", "hydrax.net")
        com.lagradost.cloudstream3.utils.loadExtractor(hydraxUrl, subtitleCallback, callback)
    }
}

open class EfekStream : ExtractorApi() {
    override var name = "EfekStream"
    override var mainUrl = "https://fa.efek.stream"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val html = response.text
        
        // Try to find direct m3u8
        val m3u8Regex = Regex("""["'](http.*?\.m3u8.*?)["']""")
        val m3u8 = m3u8Regex.findAll(html).map { it.groupValues[1] }.firstOrNull { it.contains("efek.stream") || it.contains("m3u8") }
        
        if (m3u8 != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8.replace("\\/", "/"),
                url
            ).forEach(callback)
        } else {
            // Check for packed scripts
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                val packed = "eval" + html.substringAfter("eval")
                val unpacked = JsUnpacker(packed).unpack()
                if (unpacked != null) {
                    val packedM3u8 = m3u8Regex.find(unpacked)?.groupValues?.get(1)
                    if (packedM3u8 != null) {
                        M3u8Helper.generateM3u8(name, packedM3u8.replace("\\/", "/"), url).forEach(callback)
                        return
                    }
                }
            }
            
            // Fallback to iframes
            response.document.select("iframe").forEach { iframe ->
                var src = iframe.attr("src")
                if (src.startsWith("//")) src = "https:$src"
                if (src.isNotBlank() && src != url) {
                    com.lagradost.cloudstream3.utils.loadExtractor(src, subtitleCallback, callback)
                }
            }
        }
    }
}

class EfekStreamV2 : EfekStream() {
    override var name = "EfekStream V2"
    override var mainUrl = "https://v2.efek.stream"
}
