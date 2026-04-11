package com.hermes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import android.util.Log
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
import java.io.ByteArrayOutputStream
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
            val data = result.data
            val photoUri = data?.data ?: cameraPhotoUri

            if (fileUploadCallback != null) {
                // Normal flow — Activity survived, deliver via callback
                val results = if (photoUri != null) arrayOf(photoUri) else null
                fileUploadCallback?.onReceiveValue(results)
                fileUploadCallback = null
                clearPendingCameraState()
            } else if (photoUri != null) {
                // Activity was recreated — fileUploadCallback is lost
                // Save URI and inject photo via JS after page loads
                Log.d("HermesApp", "Activity recreated after camera, will inject photo via JS")
                getPrefs().edit().putString("pending_photo_uri", photoUri.toString()).apply()
                // Try injecting immediately if WebView already loaded
                webView.postDelayed({ injectPendingPhoto() }, 1500)
            }
        } else {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            clearPendingCameraState()
        }
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
        // Also try SharedPreferences (survives process death)
        if (cameraPhotoUri == null) {
            getPrefs().getString("pending_camera_uri", null)?.let {
                cameraPhotoUri = Uri.parse(it)
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

        // Disable SwipeRefreshLayout completely
        // This is a SPA app — pull-to-refresh causes accidental reloads
        // and WebView.scrollY is always 0 (scroll is internal to web content)
        swipeRefresh.isEnabled = false

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
                // Check if there's a pending photo from camera (Activity was recreated)
                webView.postDelayed({ injectPendingPhoto() }, 800)
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
            onPermissionRequestCallback = { request -> runOnUiThread { request.grant(request.resources) } },
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

                console.log('[HermesApp] Native bridge v2.2.3 installed');

                // Inject "Alterar servidor" card in the settings hub page
                function injectServerOption() {
                    if (document.querySelector('#hermes-change-server')) return true;
                    // Try multiple selectors for the settings grid
                    var grid = document.querySelector('.grid.grid-cols-2.gap-3')
                             || document.querySelector('.grid.grid-cols-2.gap-4')
                             || document.querySelector('div[class*="grid-cols-2"][class*="gap-"]');
                    if (!grid) return false;
                    // Only inject on settings-like pages (grid must have multiple card-like children)
                    if (grid.children.length < 2) return false;
                    var card = document.createElement('div');
                    card.id = 'hermes-change-server';
                    card.className = 'flex flex-col items-center justify-center p-5 sm:p-6 glass-card rounded-2xl hover:bg-hermes-50/50 dark:hover:bg-hermes-900/10 transition-all duration-200 min-h-[110px] sm:min-h-[120px] group cursor-pointer border-2 border-transparent hover:border-hermes-200 dark:hover:border-hermes-800/40';
                    card.innerHTML = '<div class="w-10 h-10 sm:w-12 sm:h-12 rounded-xl flex items-center justify-center mb-2 sm:mb-3 bg-gradient-to-br from-blue-400 to-blue-600 shadow-lg"><svg class="w-5 h-5 sm:w-6 sm:h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"/></svg></div><span class="text-xs sm:text-sm font-medium text-gray-700 dark:text-gray-300 text-center leading-tight">Alterar<br/>Servidor</span>';
                    card.onclick = function() { if(window.HermesAndroid) HermesAndroid.changeServerUrl(); };
                    grid.appendChild(card);
                    console.log('[HermesApp] Server card injected into settings');
                    return true;
                }
                // Use setInterval for reliable injection (SPA route changes don't trigger onPageFinished)
                var _hsInterval = setInterval(function() { injectServerOption(); }, 1000);
                // Also try immediately and with a small delay
                injectServerOption();
                setTimeout(injectServerOption, 500);
                setTimeout(injectServerOption, 2000);
            })();
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

        // Save to SharedPreferences (survives process death)
        savePendingCameraState()

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        fileChooserLauncher.launch(cameraIntent)
    }

    private fun launchCameraOrGalleryChooser() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cacheDir = externalCacheDir ?: cacheDir
        val photoFile = File(cacheDir, "PHOTO_${timeStamp}.jpg")
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            photoFile
        )

        // Save to SharedPreferences (survives process death)
        savePendingCameraState()

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        val chooserIntent = Intent.createChooser(galleryIntent, "Selecionar imagem")
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))

        fileChooserLauncher.launch(chooserIntent)
    }

    private fun savePendingCameraState() {
        cameraPhotoUri?.let {
            getPrefs().edit()
                .putString("pending_camera_uri", it.toString())
                .apply()
        }
    }

    private fun clearPendingCameraState() {
        getPrefs().edit()
            .remove("pending_camera_uri")
            .remove("pending_photo_uri")
            .apply()
    }

    private fun injectPendingPhoto() {
        val uriStr = getPrefs().getString("pending_photo_uri", null) ?: return

        try {
            val uri = Uri.parse(uriStr)
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap == null) {
                clearPendingCameraState()
                return
            }

            // Compress to JPEG 85% quality (keeps size manageable for JS injection)
            val baos = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()

            val bytes = baos.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            baos.close()

            Log.d("HermesApp", "Injecting pending photo via JS (${bytes.size / 1024} KB)")

            // Inject photo into the chat file input via JavaScript
            val js = """
                (function() {
                    try {
                        var base64 = '$base64';
                        var byteChars = atob(base64);
                        var byteNumbers = new Uint8Array(byteChars.length);
                        for (var i = 0; i < byteChars.length; i++) {
                            byteNumbers[i] = byteChars.charCodeAt(i);
                        }
                        var blob = new Blob([byteNumbers], { type: 'image/jpeg' });
                        var file = new File([blob], 'camera_photo.jpg', { type: 'image/jpeg', lastModified: Date.now() });

                        // Try to find the camera file input
                        var input = document.querySelector('input[type="file"][capture]')
                                 || document.querySelector('input[type="file"][accept*="image"]');
                        if (input) {
                            var dt = new DataTransfer();
                            dt.items.add(file);
                            input.files = dt.files;
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                            console.log('[HermesApp] Photo injected via file input');
                            return;
                        }

                        // Fallback: dispatch custom event for Vue to pick up
                        window.dispatchEvent(new CustomEvent('hermes-native-photo', { detail: { file: file } }));
                        console.log('[HermesApp] Photo dispatched via custom event');
                    } catch(e) {
                        console.error('[HermesApp] Photo injection failed:', e);
                    }
                })();
            """.trimIndent()

            webView.evaluateJavascript(js, null)
            clearPendingCameraState()

        } catch (e: Exception) {
            Log.e("HermesApp", "Failed to inject pending photo", e)
            clearPendingCameraState()
        }
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
