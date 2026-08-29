package com.filmapik

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FilmApikPlugin : Plugin() {
    override fun load(context: Context) {
        // Register FilmApik Provider into CloudStream 3 provider registry
        registerMainAPI(FilmApikProvider())
    }
}
