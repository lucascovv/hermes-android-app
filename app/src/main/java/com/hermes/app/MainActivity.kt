package com.hermes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "hermes_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var webView: WebView
    private lateinit var configContainer: View
    private lateinit var webContainer: View
    private lateinit var urlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var savedUrl: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Camera capture support
    private var cameraPhotoUri: Uri? = null

    // Native audio recording support
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecordingAudio = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Check if the result comes from file picker or camera
            val data = result.data
            val results = if (data != null && data.data != null) {
                // File picker result
                arrayOf(data.data!!)
            } else if (cameraPhotoUri != null) {
                // Camera capture result — use the URI we created
                arrayOf(cameraPhotoUri!!)
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(results)
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
        cameraPhotoUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        NotificationHelper.createChannel(this)
        setupWebView()

        // Restore camera URI if activity was recreated (e.g. after camera app)
        if (savedInstanceState != null) {
            cameraPhotoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable("cameraPhotoUri", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable("cameraPhotoUri")
            }
        }

        savedUrl = getPrefs().getString(KEY_SERVER_URL, null)
        if (savedUrl != null) {
            if (savedInstanceState != null) {
                // Activity recreated — restore WebView state instead of reloading
                configContainer.visibility = View.GONE
                webContainer.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                webView.restoreState(savedInstanceState)
            } else {
                showWebView(savedUrl!!)
            }
        } else {
            showConfig()
        }

        connectButton.setOnClickListener { onConnect() }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { onConnect(); true } else false
        }

        retryButton.setOnClickListener {
            errorView.visibility = View.GONE
            savedUrl?.let { loadUrl(it) }
        }

        // Disable swipe-to-refresh when WebView is not at the top (prevents accidental reloads)
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        swipeRefresh.setOnRefreshListener {
            webView.reload()
            swipeRefresh.postDelayed({ swipeRefresh.isRefreshing = false }, 2000)
        }
        swipeRefresh.setColorSchemeResources(R.color.hermes_primary)

        // Back button handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webContainer.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
                    webContainer.visibility == View.VISIBLE -> moveTaskToBack(true)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        requestRequiredPermissions()
    }

    private fun initViews() {
        configContainer = findViewById(R.id.configContainer)
        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        connectButton = findViewById(R.id.connectButton)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        swipeRefresh = findViewById(R.id.swipeRefresh)
    }

    private fun onConnect() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            urlInput.error = "Digite a URL do servidor"
            return
        }

        val normalizedUrl = normalizeUrl(url)
        getPrefs().edit().putString(KEY_SERVER_URL, normalizedUrl).apply()
        savedUrl = normalizedUrl

        hideKeyboard()
        showWebView(normalizedUrl)
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }

    fun showConfig() {
        configContainer.visibility = View.VISIBLE
        webContainer.visibility = View.GONE
        errorView.visibility = View.GONE
        savedUrl?.let { urlInput.setText(it) }
    }

    private fun showWebView(url: String) {
        configContainer.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        loadUrl(url)
    }

    private fun loadUrl(url: String) {
        if (!isNetworkAvailable()) {
            showError("Sem conexão com a internet.\nVerifique sua rede e tente novamente.")
            return
        }
        progressBar.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = false
            displayZoomControls = false
            // Append custom user-agent to identify native app
            userAgentString = "$userAgentString HermesApp/2.0"
        }

        // Cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        // JavaScript bridge for native notifications
        webView.addJavascriptInterface(WebAppInterface(this), "HermesAndroid")

        // WebView client
        webView.webViewClient = HermesWebViewClient(
            onPageStarted = {
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
            },
            onPageFinished = {
                progressBar.visibility = View.GONE
                injectNotificationBridge()
            },
            onError = { errorMsg -> showError(errorMsg) }
        )

        // Chrome client for file uploads, permissions, progress
        webView.webChromeClient = HermesWebChromeClient(
            onFileChooser = { callback, params ->
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = callback
                try {
                    val acceptTypes = params.acceptTypes
                    val isImageRequest = acceptTypes.any {
                        it.isNotEmpty() && (it.startsWith("image/") || it == "image/*")
                    }

                    if (isImageRequest && params.isCaptureEnabled) {
                        // capture attribute set → open camera directly
                        launchDirectCamera()
                    } else if (isImageRequest) {
                        // No capture → offer camera + gallery chooser
                        launchCameraOrGalleryChooser()
                    } else {
                        // For other types (files, etc) → use the default intent
                        val intent = params.createIntent()
                        fileChooserLauncher.launch(intent)
                    }
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                }
            },
            onPermissionRequest = { request -> runOnUiThread { request.grant(request.resources) } },
            onProgressChanged = { progress ->
                progressBar.progress = progress
                if (progress >= 100) progressBar.visibility = View.GONE
            }
        )

        // Download listener
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = android.app.DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Baixando arquivo...")
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(fileName)
                request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                )
                val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Baixando: $fileName", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Erro ao baixar arquivo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun injectNotificationBridge() {
        val js = """
            (function() {
                if (window.__hermesNativeBridgeInstalled) return;
                window.__hermesNativeBridgeInstalled = true;

                // Override Notification API → native Android notifications
                window.Notification = function(title, options) {
                    options = options || {};
                    try {
                        HermesAndroid.showNotification(
                            title || 'Hermes',
                            options.body || '',
                            options.tag || ('hermes-' + Date.now()),
                            options.data ? JSON.stringify(options.data) : ''
                        );
                    } catch(e) {}
                    this.close = function() {};
                    this.onclick = null;
                };
                window.Notification.permission = 'granted';
                window.Notification.requestPermission = function(cb) {
                    if (cb) cb('granted');
                    return Promise.resolve('granted');
                };

                // Override ServiceWorkerRegistration.showNotification → native
                if (typeof ServiceWorkerRegistration !== 'undefined') {
                    ServiceWorkerRegistration.prototype.showNotification = function(title, options) {
                        options = options || {};
                        try {
                            HermesAndroid.showNotification(
                                title || 'Hermes',
                                options.body || '',
                                options.tag || ('hermes-' + Date.now()),
                                options.data ? JSON.stringify(options.data) : ''
                            );
                        } catch(e) {}
                        return Promise.resolve();
                    };
                }

                // Flag for web app to detect native context
                window.__HERMES_NATIVE_APP = true;

                // Native audio recording bridge for Android
                // Intercepts MediaRecorder to use native Android recording
                window.__hermesNativeAudio = {
                    isRecording: false,
                    startRecording: function() {
                        if (this.isRecording) return;
                        this.isRecording = true;
                        HermesAndroid.startAudioRecording();
                    },
                    stopRecording: function() {
                        if (!this.isRecording) return Promise.resolve(null);
                        this.isRecording = false;
                        return new Promise(function(resolve) {
                            var data = HermesAndroid.stopAudioRecording();
                            if (data && data.length > 0) {
                                var byteChars = atob(data);
                                var byteNumbers = new Array(byteChars.length);
                                for (var i = 0; i < byteChars.length; i++) {
                                    byteNumbers[i] = byteChars.charCodeAt(i);
                                }
                                var byteArray = new Uint8Array(byteNumbers);
                                var blob = new Blob([byteArray], { type: 'audio/mp4' });
                                resolve(blob);
                            } else {
                                resolve(null);
                            }
                        });
                    },
                    cancelRecording: function() {
                        this.isRecording = false;
                        HermesAndroid.cancelAudioRecording();
                    }
                };

                console.log('[HermesApp] Native bridge v2.2 installed');

                // Inject "Alterar servidor" option in the settings page
                function injectServerOption() {
                    if (document.querySelector('#hermes-change-server')) return;
                    // Look for the settings sidebar or settings view
                    var settingsContainer = document.querySelector('.settings--content .settings--content--inner') 
                        || document.querySelector('.profile--section')
                        || document.querySelector('.settings-section');
                    if (!settingsContainer) return;
                    var btn = document.createElement('div');
                    btn.id = 'hermes-change-server';
                    btn.innerHTML = '<div style="padding:12px 16px;margin:8px 0;background:#f3f4f6;border-radius:8px;cursor:pointer;display:flex;align-items:center;gap:8px;border:1px solid #e5e7eb;">' +
                        '<span style="font-size:18px;">🔗</span>' +
                        '<div><div style="font-weight:600;color:#374151;font-size:14px;">Alterar servidor</div>' +
                        '<div style="color:#9ca3af;font-size:12px;">Trocar URL de conexão do app</div></div></div>';
                    btn.onclick = function() { HermesAndroid.changeServerUrl(); };
                    settingsContainer.appendChild(btn);
                }
                // Observe DOM changes to inject when settings page is loaded
                var observer = new MutationObserver(function() { setTimeout(injectServerOption, 500); });
                observer.observe(document.body, { childList: true, subtree: true });
                setTimeout(injectServerOption, 1000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun launchDirectCamera() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cacheDir = externalCacheDir ?: cacheDir
        val photoFile = File(cacheDir, "PHOTO_${timeStamp}.jpg")
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            photoFile
        )

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        fileChooserLauncher.launch(cameraIntent)
    }

    private fun launchCameraOrGalleryChooser() {
        // Create temp file for camera photo
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cacheDir = externalCacheDir ?: cacheDir
        val photoFile = File(cacheDir, "PHOTO_${timeStamp}.jpg")
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            photoFile
        )

        // Camera intent
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        // Gallery intent
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        // Create chooser with camera as primary option
        val chooserIntent = Intent.createChooser(galleryIntent, "Selecionar imagem")
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))

        fileChooserLauncher.launch(chooserIntent)
    }

    // --- Native audio recording ---

    fun startNativeAudioRecording(): Boolean {
        if (isRecordingAudio) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            audioFile = File(externalCacheDir, "AUDIO_${timeStamp}.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }

            isRecordingAudio = true
            return true
        } catch (e: Exception) {
            cleanupAudioRecording()
            return false
        }
    }

    fun stopNativeAudioRecording(): String? {
        if (!isRecordingAudio || mediaRecorder == null) return null

        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingAudio = false

            val file = audioFile ?: return null
            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                file.delete()
                audioFile = null
                encoded
            } else {
                null
            }
        } catch (e: Exception) {
            cleanupAudioRecording()
            null
        }
    }

    fun cancelNativeAudioRecording() {
        cleanupAudioRecording()
    }

    private fun cleanupAudioRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecordingAudio = false
        audioFile?.delete()
        audioFile = null
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        errorText.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    @Suppress("DEPRECATION")
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    // Keep WebSocket alive when app goes to background briefly
    @SuppressLint("WakelockTimeout")
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // Acquire partial wake lock for 2 minutes to keep WS alive
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:websocket")
            wakeLock?.acquire(2 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
        // Nudge WebSocket reconnection when app comes back
        webView.evaluateJavascript(
            "(function(){ if(window.__hermesCable && window.__hermesCable.forceReconnect) window.__hermesCable.forceReconnect(); })()",
            null
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        cameraPhotoUri?.let { outState.putParcelable("cameraPhotoUri", it) }
    }

    override fun onDestroy() {
        cleanupAudioRecording()
        wakeLock?.let { if (it.isHeld) it.release() }
        webView.destroy()
        super.onDestroy()
    }

    private fun getPrefs(): SharedPreferences {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
}
