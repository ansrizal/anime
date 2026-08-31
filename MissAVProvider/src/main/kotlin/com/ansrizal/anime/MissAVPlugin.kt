package com.ansrizal.anime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MissAVPlugin : Plugin() {
    override fun load(context: Context) {
        // Register MissAV Provider into CloudStream 3 provider registry
        registerMainAPI(MissAVProvider())
    }
}