package com.idlix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IdlixProviderPlugin: BasePlugin() {
    fun load(context: Context) {
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Jeniusplay())
    }
}