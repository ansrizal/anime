package com.klikxxi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class KlikxxiProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KlikxxiProvider())
        registerExtractorAPI(Klixxistrp2p())
        registerExtractorAPI(Klixxiupns())
        registerExtractorAPI(Hglink())  
    }
}