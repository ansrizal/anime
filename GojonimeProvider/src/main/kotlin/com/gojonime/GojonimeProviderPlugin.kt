package com.gojonime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.Gofile
import com.lagradost.cloudstream3.extractors.Krakenfiles
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.Mediafire

@CloudstreamPlugin
class GojonimeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GojonimeProvider())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Mediafire())
    }
}
