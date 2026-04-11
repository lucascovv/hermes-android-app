package com.hermes.app

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class HermesWebChromeClient(
    private val onFileChooser: (ValueCallback<Array<Uri>>, FileChooserParams) -> Unit,
    private val onPermissionRequest: (PermissionRequest) -> Unit,
    private val onProgressChanged: (Int) -> Unit
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        if (filePathCallback != null && fileChooserParams != null) {
            onFileChooser(filePathCallback, fileChooserParams)
            return true
        }
        return false
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.let { onPermissionRequest(it) }
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgressChanged(newProgress)
    }

    // Allow geolocation if needed
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, true, false)
    }
}
