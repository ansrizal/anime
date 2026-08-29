package com.ansrizal.anime

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

class TurnstileInterceptor(private val targetCookie: String = "cf_clearance") : Interceptor {

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""
        if (existingCookies.contains(targetCookie) || existingCookies.contains("cf_clearance") || existingCookies.contains("__cf_bm")) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .build(),
            )
            if ((response.code != 403) && (response.code != 503)) return response
            response.close()
        }

        @Suppress("PrivateApi")
        val context = (try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (_: Throwable) {
            null
        }) ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        val userAgentRef = AtomicReference(originalRequest.header("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        val webViewRef = AtomicReference<WebView?>(null)

        handler.post {
            val wv = WebView(context)
            webViewRef.set(wv)
            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = userAgentRef.get()
            }

            wv.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager.flush()
                }
            }
            wv.loadUrl(url)
        }

        repeat(20) { 
            Thread.sleep(500)
            val cookies = cookieManager.getCookie(domainUrl) ?: ""
            if (cookies.contains("cf_clearance") || 
                cookies.contains("as_turnstile") || 
                cookies.contains("__cf_bm") ||
                cookies.contains("_as_turnstile")
            ) {
                return@repeat
            }
        }

        handler.post {
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                destroy()
            }
        }

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        return chain.proceed(
            originalRequest.newBuilder()
                .header("Cookie", finalCookies)
                .header("User-Agent", userAgentRef.get())
                .build(),
        )
    }
}
