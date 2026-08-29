package com.ansrizal.anime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.Acefile
import com.lagradost.cloudstream3.extractors.Dhtpre
import com.lagradost.cloudstream3.extractors.Gofile
import com.lagradost.cloudstream3.extractors.Krakenfiles
import com.lagradost.cloudstream3.extractors.Mediafire
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.Peytonepre
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.Ryderjet
import com.lagradost.cloudstream3.extractors.Smoothpre
import com.lagradost.cloudstream3.extractors.VidHideHub
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidHidePro1
import com.lagradost.cloudstream3.extractors.VidHidePro2
import com.lagradost.cloudstream3.extractors.VidHidePro3
import com.lagradost.cloudstream3.extractors.VidHidePro4
import com.lagradost.cloudstream3.extractors.VidHidePro5
import com.lagradost.cloudstream3.extractors.VidHidePro6

@CloudstreamPlugin
class sokujaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register sokuja Provider into CloudStream 3 provider registry
        registerMainAPI(sokujaProvider())
    }
}