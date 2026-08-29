package com.ansrizal.filmapik

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class FilmApikPlugin : Plugin() {
    override fun load(context: Context) {
        // Register FilmApik Provider into CloudStream 3 provider registry
        registerMainAPI(FilmApikProvider())
    }
}
