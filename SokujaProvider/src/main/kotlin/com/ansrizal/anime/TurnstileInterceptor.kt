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

class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        // Pre-set some common cookies to look more legitimate
        cookieManager.setCookie(domainUrl, "_as_ipin_lc=id-ID; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_tz=Asia/Jakarta; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_ct=ID; path=/; SameSite=Strict")
        cookieManager.flush()

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""
        if (existingCookies.contains(targetCookie) || existingCookies.contains("cf_clearance")) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .build(),
            )
            if ((response.code != 403) && (response.code != 503)) return response

            response.close()
            // If we got here, the cookie might be expired
            cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0; path=/; Secure")
            cookieManager.setCookie(domainUrl, "cf_clearance=; Max-Age=0; path=/; Secure")
            cookieManager.flush()
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
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager.flush()
                }
            }

            wv.loadUrl(url)
        }

        // Wait for the cookie to appear
        repeat(20) { // 10 seconds total
            Thread.sleep(500)
            val cookies = cookieManager.getCookie(domainUrl) ?: ""
            if (cookies.contains("cf_clearance") || 
                cookies.contains("as_turnstile") || 
                cookies.contains("__cf_bm") ||
                cookies.contains(targetCookie)
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
        val finalUA = userAgentRef.get()

        return chain.proceed(
            originalRequest.newBuilder()
                .apply { if (finalUA.isNotBlank()) header("User-Agent", finalUA) }
                .header("Cookie", finalCookies)
                .build(),
        )
    }
}
