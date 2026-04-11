package com.hermes.app

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class HermesWebChromeClient(
    private val onFileChooser: (ValueCallback<Array<Uri>>, FileChooserParams) -> Unit,
    private val onPermissionRequestCallback: (PermissionRequest) -> Unit,
    private val onProgressChanged: (Int) -> Unit
) : WebChromeClient() {

    // Guard against recursive onPermissionRequest calls (WebView bug)
    private var isGrantingPermission = false

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
        if (request == null) return
        if (isGrantingPermission) return  // Prevent recursive calls
        isGrantingPermission = true
        try {
            onPermissionRequestCallback(request)
        } finally {
            isGrantingPermission = false
        }
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
