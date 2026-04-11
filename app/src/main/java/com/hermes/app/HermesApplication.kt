package com.hermes.app

import android.app.Application
import android.webkit.WebView

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Enable WebView debugging in debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
