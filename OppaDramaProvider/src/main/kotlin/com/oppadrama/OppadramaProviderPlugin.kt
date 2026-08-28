package com.oppadrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class OppadramaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OppadramaProvider())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(BuzzServer())
    }
}