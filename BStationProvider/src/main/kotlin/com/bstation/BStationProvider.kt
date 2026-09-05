package com.bstation

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.Context

@CloudstreamPlugin
class BStationProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BStation())
    }
}
