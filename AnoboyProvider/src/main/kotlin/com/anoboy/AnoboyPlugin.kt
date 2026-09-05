package com.anoboy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class AnoboyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anoboy())
        registerExtractorAPI(BloggerExtractor())
    }
}