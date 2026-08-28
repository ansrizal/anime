package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

open class Plugin {
    open var filename: String? = null
    open var openSettings: ((Context) -> Unit)? = null

    open fun load(context: Context) {}
    open fun reload(context: Context) { load(context) }
    open fun unload(context: Context) {}

    open fun registerMainAPI(element: MainAPI) {}
    open fun registerExtractorAPI(element: ExtractorApi) {}
}
