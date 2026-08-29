package com.ansrizal.indoxxi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IndoxxiPlugin : Plugin() {
    override fun load(context: Context) {
        // Register INDOXXI Provider into CloudStream 3 provider registry
        registerMainAPI(IndoxxiProvider())
    }
}
