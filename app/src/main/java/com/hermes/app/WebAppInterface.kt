package com.hermes.app

import android.content.Context
import android.webkit.JavascriptInterface

class WebAppInterface(private val context: Context) {

    private val activity: MainActivity? get() = context as? MainActivity

    @JavascriptInterface
    fun showNotification(title: String, body: String, tag: String, data: String) {
        NotificationHelper.show(context, title, body, tag)
    }

    @JavascriptInterface
    fun vibrate(duration: Long) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.0.0"
        } catch (_: Exception) { "2.0.0" }
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun startAudioRecording(): Boolean {
        return activity?.startNativeAudioRecording() ?: false
    }

    @JavascriptInterface
    fun stopAudioRecording(): String {
        return activity?.stopNativeAudioRecording() ?: ""
    }

    @JavascriptInterface
    fun cancelAudioRecording() {
        activity?.cancelNativeAudioRecording()
    }

    @JavascriptInterface
    fun changeServerUrl() {
        activity?.runOnUiThread { activity?.showConfig() }
    }
}
