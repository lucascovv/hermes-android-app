package com.hermes.app

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class HermesWebViewClient(
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onError: (String) -> Unit
) : WebViewClient() {

    private var hasError = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        hasError = false
        onPageStarted()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (!hasError) {
            onPageFinished()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false

        // Keep platform navigation inside WebView
        val currentHost = view?.url?.let { android.net.Uri.parse(it).host } ?: ""
        val targetHost = android.net.Uri.parse(url).host ?: ""

        if (targetHost == currentHost || targetHost.isEmpty()) {
            return false // Stay in WebView
        }

        // External links → open in browser
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            view?.context?.startActivity(intent)
        } catch (_: Exception) {}
        return true
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        // Only handle main frame errors
        if (request?.isForMainFrame == true) {
            hasError = true
            val errorMsg = when (error?.errorCode) {
                ERROR_HOST_LOOKUP -> "Servidor não encontrado.\nVerifique a URL e tente novamente."
                ERROR_CONNECT -> "Não foi possível conectar ao servidor.\nVerifique se o servidor está online."
                ERROR_TIMEOUT -> "Conexão expirou.\nTente novamente."
                ERROR_IO -> "Erro de rede.\nVerifique sua conexão."
                else -> "Erro ao carregar a página.\nVerifique a URL e sua conexão."
            }
            onError(errorMsg)
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // For development/self-signed certs: proceed (remove in production if needed)
        handler?.proceed()
    }
}
