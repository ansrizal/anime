package com.nekopoi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class NekopoiProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(NekopoiProvider())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Streampoi())
    }
}