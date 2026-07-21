package com.inetconnector.aichat

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.format.Formatter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.graphics.Color
import android.widget.EditText
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ArrayAdapter
import android.widget.TextView
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.arm.aichat.UnsupportedArchitectureException
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.BindException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val MAX_MOBILE_MODEL_BYTES: Long = 5L * 1024L * 1024L * 1024L
        private val CHAT_SERVER_PORT_CANDIDATES = intArrayOf(18080, 18081, 18082, 8080)
        private val ANALYSIS_SERVER_PORT_CANDIDATES = intArrayOf(19777, 19778, 19779, 19780)
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 2001
        private const val REQUEST_CAMERA_PERMISSION = 2002
        private const val PREFS_NAME = "model_selection_prefs"
        private const val PREF_KEY_PREFERRED_MODEL_PATH = "preferred_model_path"
        private const val PREF_KEY_FAILED_MODEL_PATHS = "failed_model_paths"
        private const val TAG = "MainActivity"
    }
    private lateinit var webView: WebView
    private lateinit var engine: InferenceEngine
    private lateinit var apiServer: LocalApiServer
    private lateinit var analysisServer: LocalAnalysisServer
    @Volatile
    private var chatApiPort: Int = CHAT_SERVER_PORT_CANDIDATES.first()
    @Volatile
    private var analysisApiPort: Int = ANALYSIS_SERVER_PORT_CANDIDATES.first()
    private val modelPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    @Volatile
    private var localServersStarted = false

    private val engineMutex = Mutex()
    private var modelPath: String? = null
    private var filePickerCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingDictationStart = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePickerCallback
            val captured = pendingCameraCaptureUri
            filePickerCallback = null
            var selected = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if ((selected == null || selected.isEmpty()) && result.resultCode == RESULT_OK && captured != null) {
                selected = arrayOf(captured)
            }
            pendingCameraCaptureUri = null
            callback?.onReceiveValue(selected)
        }

    private val cameraCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePickerCallback
            val captured = pendingCameraCaptureUri
            filePickerCallback = null
            pendingCameraCaptureUri = null

            if (result.resultCode == RESULT_OK && captured != null) {
                callback?.onReceiveValue(arrayOf(captured))
            } else {
                callback?.onReceiveValue(null)
            }
        }

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                showModelRequiredDialog()
                return@registerForActivityResult
            }

            lifecycleScope.launch(Dispatchers.IO) {
                importAndLoadModel(uri)
            }
        }

    private val speechInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spoken = matches?.firstOrNull()?.trim().orEmpty()
                if (spoken.isNotBlank()) {
                    runOnUiThread {
                        val safeText = JSONObject.quote(spoken)
                        webView.evaluateJavascript(
                            """
                            (function() {
                              window.dispatchEvent(new CustomEvent('native-dictation-result', { detail: $safeText }));
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        val contentRoot = findViewById<FrameLayout>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
        setupWebView()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                if (!ensureModelLoadedOrPrompt()) {
                    return@launch
                }

                runCatching {
                    startLocalServers()
                }.onFailure {
                    Log.e(TAG, "Local servers could not be started cleanly during startup; keeping model loaded.", it)
                }

                launch(Dispatchers.Main) {
                    loadChatUi()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Startup failed", t)
                launch(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.dialog_import_failed_title))
                        .setMessage(t.message ?: getString(R.string.dialog_unknown_error))
                        .setPositiveButton(getString(R.string.dialog_retry)) { _, _ ->
                            recreate()
                        }
                        .setNegativeButton(getString(R.string.dialog_close)) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }
    }

    private fun createApiServer(port: Int): LocalApiServer {
        return LocalApiServer(
            port = port,
            webRootResolver = { applicationContext.assets },
            getModelName = { activeModelName() },
            onChatCompletion = { body -> handleCompletion(body) },
            artifactsRootResolver = { File(filesDir, "generated-artifacts").apply { mkdirs() } }
        )
    }

    private fun createAnalysisServer(port: Int): LocalAnalysisServer {
        return LocalAnalysisServer(applicationContext, port)
    }

    @Synchronized
    private fun startLocalServers() {
        if (localServersStarted) {
            return
        }

        val chatPort = startServerWithFallback(
            name = "chat-api",
            candidatePorts = CHAT_SERVER_PORT_CANDIDATES,
            createServer = { port ->
                chatApiPort = port
                createApiServer(port)
            }
        )
        val analysisPort = startServerWithFallback(
            name = "analysis-api",
            candidatePorts = ANALYSIS_SERVER_PORT_CANDIDATES,
            createServer = { port ->
                analysisApiPort = port
                createAnalysisServer(port)
            }
        )

        Log.i(TAG, "Local servers started on chat=$chatPort analysis=$analysisPort")

        localServersStarted = true
    }

    private fun startServerWithFallback(
        name: String,
        candidatePorts: IntArray,
        createServer: (Int) -> NanoHTTPD
    ): Int {
        var lastError: Throwable? = null
        for (port in candidatePorts) {
            val server = createServer(port)
            if (name == "chat-api") {
                apiServer = server as LocalApiServer
            } else {
                analysisServer = server as LocalAnalysisServer
            }

            try {
                server.start()
                waitForServerHealth(port)
                return port
            } catch (t: Throwable) {
                lastError = t
                val bindInUse =
                    t is BindException ||
                        t.cause is BindException ||
                        (t.message?.contains("EADDRINUSE", ignoreCase = true) == true)
                if (bindInUse) {
                    if (runCatching { waitForServerHealth(port, timeoutMs = 1000L); true }.getOrDefault(false)) {
                        Log.w(TAG, "Server '$name' already running on $port; reusing existing listener.", t)
                        return port
                    }

                    Log.w(TAG, "Server '$name' port $port is unavailable; trying next candidate.", t)
                    runCatching { server.stop() }
                    Thread.sleep(150)
                    continue
                }
                runCatching { server.stop() }
                throw t
            }
        }
        throw IllegalStateException("No usable port found for server '$name'", lastError)
    }

    private fun waitForServerHealth(port: Int, timeoutMs: Long = 3500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                val conn = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 300
                    readTimeout = 400
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }
                try {
                    conn.connect()
                    val code = conn.responseCode
                    code in 200..299
                } finally {
                    conn.disconnect()
                }
            }.onFailure {
                lastError = it.message
            }.getOrDefault(false)

            if (ok) return
            Thread.sleep(120)
        }
        throw IllegalStateException("Local server on port $port is not reachable${if (lastError != null) ": $lastError" else ""}")
    }

    override fun onDestroy() {
        runCatching { apiServer.stop() }
        runCatching { analysisServer.stop() }
        runCatching { engine.destroy() }
        localServersStarted = false
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#0F172A"))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "aichat" && uri.host == "dictation") {
                    startNativeDictationFlow()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Force a readable light theme baseline in WebView to avoid device force-dark artifacts.
                view?.evaluateJavascript(
                    """
                    (function() {
                      try {
                        localStorage.setItem('theme', 'dark');
                        document.documentElement.classList.add('dark');
                        document.documentElement.style.background = '#0F172A';
                        document.body.style.background = '#0F172A';
                        document.body.style.color = '#F8FAFC';
                      } catch (e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePickerCallback?.onReceiveValue(null)
                filePickerCallback = filePathCallback

                if (fileChooserParams?.isCaptureEnabled == true) {
                    if (!hasCameraPermission()) {
                        pendingFileChooserParams = fileChooserParams
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(android.Manifest.permission.CAMERA),
                            REQUEST_CAMERA_PERMISSION
                        )
                        return true
                    }
                    return launchCameraCapture()
                }

                if (needsCameraPermission(fileChooserParams) && !hasCameraPermission()) {
                    pendingFileChooserParams = fileChooserParams
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(android.Manifest.permission.CAMERA),
                        REQUEST_CAMERA_PERMISSION
                    )
                    return true
                }

                return launchWebFileChooser(fileChooserParams)
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                forceDark = WebSettings.FORCE_DARK_OFF
            }
        }

        webView.clearCache(true)
        webView.clearHistory()
        webView.addJavascriptInterface(AndroidSpeechBridge(), "AndroidSpeechBridge")
        webView.addJavascriptInterface(AndroidModelBridge(), "AndroidModelBridge")
        webView.setDownloadListener { url, _userAgent, contentDisposition, mimeType, _contentLength ->
            enqueueWebDownload(url, contentDisposition, mimeType)
        }
    }

    private fun enqueueWebDownload(url: String, contentDisposition: String?, mimeType: String?) {
        runCatching {
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(guessedName)
                setDescription("Downloading file")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onFailure {
            Log.e(TAG, "Failed to enqueue download", it)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun needsCameraPermission(params: WebChromeClient.FileChooserParams?): Boolean {
        if (params == null || !params.isCaptureEnabled) {
            return false
        }

        val accepts = params.acceptTypes
            ?.map { it?.lowercase(Locale.ROOT).orEmpty() }
            .orEmpty()

        if (accepts.isEmpty()) {
            return true
        }

        return accepts.any { type ->
            type.isBlank() ||
                type == "*/*" ||
                type.startsWith("image/") ||
                type.startsWith("video/")
        }
    }

    private fun launchWebFileChooser(params: WebChromeClient.FileChooserParams?): Boolean {
        return try {
            val contentIntent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            val shouldOfferCamera = hasCameraPermission() && acceptsImageOrVideo(params)
            val launchIntent = if (shouldOfferCamera) {
                val cameraIntent = createCameraCaptureIntent()
                if (cameraIntent != null) {
                    Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentIntent)
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                } else {
                    contentIntent
                }
            } else {
                pendingCameraCaptureUri = null
                contentIntent
            }

            fileChooserLauncher.launch(launchIntent)
            true
        } catch (t: Throwable) {
            pendingCameraCaptureUri = null
            filePickerCallback?.onReceiveValue(null)
            filePickerCallback = null
            false
        }
    }

    private fun acceptsImageOrVideo(params: WebChromeClient.FileChooserParams?): Boolean {
        val accepts = params?.acceptTypes
            ?.map { it?.lowercase(Locale.ROOT).orEmpty() }
            .orEmpty()
        if (accepts.isEmpty()) {
            return true
        }
        return accepts.any { type ->
            type.isBlank() ||
                type == "*/*" ||
                type.startsWith("image/") ||
                type.startsWith("video/")
        }
    }

    private fun createCameraCaptureIntent(): Intent? {
        val captureDir = File(cacheDir, "captures").apply { mkdirs() }
        val captureFile = File.createTempFile("capture_", ".jpg", captureDir)
        val authority = "${applicationContext.packageName}.fileprovider"
        val captureUri = FileProvider.getUriForFile(this, authority, captureFile)
        pendingCameraCaptureUri = captureUri
        return Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, captureUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun launchCameraCapture(): Boolean {
        return try {
            val intent = createCameraCaptureIntent() ?: return false
            cameraCaptureLauncher.launch(intent)
            true
        } catch (t: Throwable) {
            pendingCameraCaptureUri = null
            filePickerCallback?.onReceiveValue(null)
            filePickerCallback = null
            false
        }
    }

    private inner class AndroidSpeechBridge {
        @JavascriptInterface
        fun startDictation() {
            runOnUiThread {
                startNativeDictationFlow()
            }
        }
    }

    private inner class AndroidModelBridge {
        @JavascriptInterface
        fun openModelSwitcher() {
            runOnUiThread {
                showModelSwitcherDialog()
            }
        }

        @JavascriptInterface
        fun openModelDownloader() {
            runOnUiThread {
                showModelDownloadDialog()
            }
        }
    }

    private fun startNativeDictationFlow() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }

        val permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            pendingDictationStart = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }

        launchSpeechRecognizerIntent()
    }

    private fun launchSpeechRecognizerIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.dictation_prompt))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { speechInputLauncher.launch(intent) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingDictationStart) {
                pendingDictationStart = false
                launchSpeechRecognizerIntent()
            } else {
                pendingDictationStart = false
            }
            return
        }

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val params = pendingFileChooserParams
            pendingFileChooserParams = null

            if (granted) {
                if (params?.isCaptureEnabled == true) {
                    launchCameraCapture()
                } else {
                    launchWebFileChooser(params)
                }
            } else {
                filePickerCallback?.onReceiveValue(null)
                filePickerCallback = null
            }
        }
    }

    private fun loadChatUi() {
        val cacheBuster = System.currentTimeMillis()
        val language = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
        webView.loadUrl("http://127.0.0.1:$chatApiPort/?v=$cacheBuster&lang=$language")
    }

    private suspend fun ensureModelLoaded(): Boolean {
        val preferred = resolvePreferredModelFile()
        val modelFile = preferred ?: throw IllegalStateException(
            getString(R.string.error_no_gguf_in_models_dir, File(filesDir, "models").absolutePath)
        )

        return activateLocalModel(modelFile, refreshUi = false)
    }

    private fun modelsDir(): File = File(filesDir, "models").apply { mkdirs() }

    private fun listLocalModelFiles(): List<File> {
        val failedModelPaths = loadFailedModelPaths()
        return modelsDir()
            .listFiles()
            ?.filter { it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".gguf") }
            ?.filter { it.length() in 1..MAX_MOBILE_MODEL_BYTES }
            .orEmpty()
            .sortedWith(
                compareBy<File> { failedModelPaths.contains(it.absolutePath) }
                    .thenByDescending { it.absolutePath == modelPath }
                    .thenByDescending { it.absolutePath == resolvePreferredModelPath() }
                    .thenByDescending { it.lastModified() }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    private fun listLoadableModelFiles(): List<File> {
        val failedModelPaths = loadFailedModelPaths()
        return listLocalModelFiles().filter {
            it.absolutePath !in failedModelPaths
        }
    }

    private fun listBrokenModelFiles(): List<File> {
        val failedModelPaths = loadFailedModelPaths()
        return listLocalModelFiles().filter {
            it.absolutePath in failedModelPaths
        }
    }

    private fun resolvePreferredModelPath(): String? {
        val failedModelPaths = loadFailedModelPaths()
        val preferred = modelPrefs.getString(PREF_KEY_PREFERRED_MODEL_PATH, null)?.trim().orEmpty()
        return preferred.takeIf {
            it.isNotBlank() &&
                File(it).exists() &&
                File(it).isFile &&
                it !in failedModelPaths
        }
    }

    private fun resolvePreferredModelFile(): File? {
        val preferredPath = resolvePreferredModelPath()
        if (preferredPath != null) {
            val preferred = File(preferredPath)
            if (preferred.exists() && preferred.isFile && preferred.length() in 1..MAX_MOBILE_MODEL_BYTES) {
                return preferred
            }
        }

        modelPath?.let { currentPath ->
            val current = File(currentPath)
            if (current.exists() &&
                current.isFile &&
                current.length() in 1..MAX_MOBILE_MODEL_BYTES &&
                currentPath !in loadFailedModelPaths()) {
                return current
            }
        }

        return listLocalModelFiles().firstOrNull {
            it.absolutePath !in loadFailedModelPaths()
        }
    }

    private fun persistPreferredModel(file: File) {
        modelPrefs.edit().putString(PREF_KEY_PREFERRED_MODEL_PATH, file.absolutePath).apply()
    }

    private fun loadFailedModelPaths(): Set<String> {
        return modelPrefs.getStringSet(PREF_KEY_FAILED_MODEL_PATHS, emptySet())?.toSet().orEmpty()
    }

    private fun markModelAsFailed(file: File) {
        val failed = loadFailedModelPaths().toMutableSet()
        if (failed.add(file.absolutePath)) {
            modelPrefs.edit().putStringSet(PREF_KEY_FAILED_MODEL_PATHS, failed).apply()
        }
    }

    private fun clearModelFailure(file: File) {
        val failed = loadFailedModelPaths().toMutableSet()
        if (failed.remove(file.absolutePath)) {
            modelPrefs.edit().putStringSet(PREF_KEY_FAILED_MODEL_PATHS, failed).apply()
        }
    }

    private fun bindModelRow(
        rowView: android.view.View,
        file: File,
        isCurrent: Boolean,
        isPreferred: Boolean,
        isBroken: Boolean,
        allowSelection: Boolean,
        onSelect: (() -> Unit)?,
        onDelete: (() -> Unit)?
    ) {
        val title = rowView.findViewById<TextView>(R.id.modelRowTitle)
        val meta = rowView.findViewById<TextView>(R.id.modelRowMeta)
        val deleteButton = rowView.findViewById<ImageButton>(R.id.modelRowDeleteButton)

        title.text = file.name
        meta.text = buildString {
            append(Formatter.formatFileSize(this@MainActivity, file.length()))
            if (isCurrent) append(" • ").append(getString(R.string.dialog_model_switcher_current_label))
            if (isPreferred && !isCurrent) append(" • ").append(getString(R.string.dialog_model_switcher_preferred_label))
            if (isBroken) append(" • ").append(getString(R.string.dialog_model_switcher_broken_label))
        }

        title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        rowView.isSelected = isCurrent
        rowView.isActivated = isCurrent
        rowView.alpha = when {
            isBroken -> 0.65f
            isCurrent -> 1f
            else -> 0.96f
        }
        rowView.setBackgroundResource(
            when {
                isBroken -> R.drawable.dialog_model_row_bg
                isCurrent -> R.drawable.dialog_model_row_current_bg
                else -> R.drawable.dialog_model_row_bg
            }
        )

        val titleColor = if (isBroken) {
            ContextCompat.getColor(this@MainActivity, R.color.dialog_error)
        } else {
            ContextCompat.getColor(this@MainActivity, R.color.dialog_on_surface)
        }
        val metaColor = if (isBroken) {
            ContextCompat.getColor(this@MainActivity, R.color.dialog_error)
        } else {
            ContextCompat.getColor(this@MainActivity, R.color.dialog_on_surface_variant)
        }
        title.setTextColor(titleColor)
        meta.setTextColor(metaColor)

        rowView.isEnabled = allowSelection || onDelete != null
        rowView.isClickable = allowSelection
        rowView.isLongClickable = false
        rowView.setOnClickListener(
            if (allowSelection) {
                {
                    onSelect?.invoke()
                }
            } else {
                null
            }
        )

        deleteButton.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.dialog_error))
        deleteButton.isEnabled = onDelete != null
        deleteButton.setOnClickListener {
            onDelete?.invoke()
        }
    }

    private suspend fun handleModelActivationFailure(
        modelFile: File,
        previousModelPath: String?,
        error: Throwable,
        progress: ImportProgressDialog?,
        deleteOnFailure: Boolean = false
    ): Boolean {
        markModelAsFailed(modelFile)
        withContext(Dispatchers.Main) {
            progress?.close()
            if (error is UnsupportedArchitectureException) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_model_unsupported_title))
                    .setMessage(getString(R.string.dialog_model_unsupported_message))
                    .setPositiveButton(getString(R.string.dialog_ok), null)
                    .show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_import_failed_title))
                    .setMessage(error.message ?: getString(R.string.dialog_unknown_error))
                    .setPositiveButton(getString(R.string.dialog_ok), null)
                    .show()
            }
        }

        if (deleteOnFailure) {
            runCatching { modelFile.delete() }
            clearModelFailure(modelFile)
        }

        if (previousModelPath != null && previousModelPath != modelFile.absolutePath) {
            runCatching {
                val previousModelFile = File(previousModelPath)
                if (previousModelFile.exists() && previousModelFile.isFile) {
                    engineMutex.withLock {
                        runCatching { engine.cleanUp() }
                        engine.loadModel(previousModelPath)
                    }
                    modelPath = previousModelPath
                    persistPreferredModel(previousModelFile)
                    clearModelFailure(previousModelFile)
                    startLocalServers()
                }
            }.onFailure {
                Log.e(TAG, "Failed to restore previous model after load error", it)
                modelPath = null
            }
        }

        return false
    }

    private suspend fun activateLocalModel(
        modelFile: File,
        refreshUi: Boolean = true,
        progressMessage: String? = null,
        deleteOnFailure: Boolean = false
    ): Boolean {
        if (!modelFile.exists() || !modelFile.isFile) {
            throw IllegalStateException(getString(R.string.error_read_model_file))
        }
        if (modelFile.length() !in 1..MAX_MOBILE_MODEL_BYTES) {
            throw IllegalStateException(getString(R.string.error_model_too_large))
        }

        val previousModelPath = modelPath
        val shouldCleanup = engine.state.value.isModelLoaded
        if (shouldCleanup) {
            runCatching { engine.cleanUp() }
        }

        val progress = progressMessage?.let {
            withContext(Dispatchers.Main) {
                ImportProgressDialog(this@MainActivity).also { dialog ->
                    dialog.setMessage(it)
                    dialog.show()
                }
            }
        }

        return try {
            engineMutex.withLock {
                try {
                    engine.loadModel(modelFile.absolutePath)
                } catch (stateError: IllegalStateException) {
                    val msg = stateError.message ?: ""
                    if (msg.contains("ModelReady", ignoreCase = true)) {
                        Log.w(TAG, "Model already in ready state, keeping loaded model: ${modelFile.name}")
                    } else {
                        throw stateError
                    }
                }
            }

            when (val loadState = engine.state.value) {
                is InferenceEngine.State.Error -> {
                    return handleModelActivationFailure(
                        modelFile = modelFile,
                        previousModelPath = previousModelPath,
                        error = loadState.exception,
                        progress = progress,
                        deleteOnFailure = deleteOnFailure
                    )
                }
                else -> Unit
            }

            modelPath = modelFile.absolutePath
            persistPreferredModel(modelFile)
            clearModelFailure(modelFile)

            runCatching {
                startLocalServers()
            }.onFailure {
                Log.e(TAG, "Local servers could not be started cleanly; keeping model loaded.", it)
            }

            if (refreshUi) {
                withContext(Dispatchers.Main) {
                    progress?.close()
                    loadChatUi()
                }
            } else {
                withContext(Dispatchers.Main) {
                    progress?.close()
                }
            }
            true
        } catch (t: Throwable) {
            handleModelActivationFailure(modelFile, previousModelPath, t, progress, deleteOnFailure)
        }
    }

    private suspend fun ensureModelLoadedOrPrompt(): Boolean {
        val loadableModels = listLoadableModelFiles()
        val brokenModels = listBrokenModelFiles()

        if (loadableModels.isNotEmpty()) {
            return ensureModelLoaded()
        }

        withContext(Dispatchers.Main) {
            if (brokenModels.isNotEmpty()) {
                showModelUnavailableDialog(brokenModels.size)
            } else {
                showModelRequiredDialog()
            }
        }
        return false
    }

    private fun showModelRequiredDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_model_required_title))
                .setMessage(getString(R.string.dialog_model_required_message))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.dialog_model_required_download)) { _, _ ->
                    showModelDownloadDialog()
                }
                .setNeutralButton(getString(R.string.dialog_model_switcher_import)) { _, _ ->
                    pickModelLauncher.launch(arrayOf("*/*"))
                }
                .setNegativeButton(getString(R.string.dialog_close)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showModelUnavailableDialog(problemModelCount: Int) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_model_unavailable_title))
                .setMessage(getString(R.string.dialog_model_unavailable_message, problemModelCount))
                .setPositiveButton(getString(R.string.dialog_model_required_download)) { _, _ ->
                    showModelDownloadDialog()
                }
                .setNeutralButton(getString(R.string.dialog_model_unavailable_manage)) { _, _ ->
                    showModelSwitcherDialog()
                }
                .setNegativeButton(getString(R.string.dialog_model_switcher_import)) { _, _ ->
                    pickModelLauncher.launch(arrayOf("*/*"))
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun showModelDownloadDialog() {
        val deviceRamBytes = getDeviceTotalMemoryBytes()
        val presets = buildModelDownloadPresets(deviceRamBytes)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_download, null, false)
        val subtitleView = dialogView.findViewById<TextView>(R.id.modelDownloadSubtitle)
        val deviceInfoView = dialogView.findViewById<TextView>(R.id.modelDownloadDeviceInfo)
        val hintView = dialogView.findViewById<TextView>(R.id.modelDownloadHint)
        val listView = dialogView.findViewById<ListView>(R.id.modelDownloadList)
        var dialog: AlertDialog? = null

        subtitleView.text = getString(R.string.dialog_model_download_subtitle)
        deviceInfoView.text = buildString {
            append(getString(R.string.dialog_model_device_detected, getDeviceDisplayName()))
            append('\n')
            append(getString(R.string.dialog_model_ram_detected, deviceRamBytes / (1024L * 1024L * 1024L)))
        }
        hintView.text = getString(R.string.dialog_model_download_hint)

        val adapter = object : ArrayAdapter<ModelDownloadPreset>(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            presets
        ) {
            override fun areAllItemsEnabled(): Boolean = false

            override fun isEnabled(position: Int): Boolean {
                val item = getItem(position) ?: return false
                return item.enabled
            }

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position) ?: return view
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
                text1.text = item.label
                text2.text = item.subtitle
                val enabled = item.enabled
                view.alpha = if (enabled) 1f else 0.38f
                text1.isEnabled = enabled
                text2.isEnabled = enabled
                return view
            }
        }

        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.TRANSPARENT)
        listView.setDividerHeight(10)
        listView.isVerticalScrollBarEnabled = false
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val preset = presets.getOrNull(position) ?: return@OnItemClickListener
            if (!preset.enabled) return@OnItemClickListener
            if (preset.customUrl) {
                dialog?.dismiss()
                showCustomUrlDialog()
            } else {
                dialog?.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    downloadAndLoadModel(preset.source)
                }
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_model_downloader_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.dialog_model_switcher_import)) { _, _ ->
                pickModelLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .create()
        dialog?.show()
    }

    private fun showModelSwitcherDialog() {
        val allModelItems = listLocalModelFiles()
        val failedModelPaths = loadFailedModelPaths()
        val modelItems = allModelItems.filter { it.absolutePath !in failedModelPaths }.toMutableList()
        val problematicModelItems = allModelItems.filter { it.absolutePath in failedModelPaths }.toMutableList()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_manager, null, false)
        val titleView = dialogView.findViewById<TextView>(R.id.modelManagerTitle)
        val subtitleView = dialogView.findViewById<TextView>(R.id.modelManagerSubtitle)
        val emptyView = dialogView.findViewById<TextView>(R.id.modelManagerEmptyState)
        val footerHintView = dialogView.findViewById<TextView>(R.id.modelManagerFooterHint)
        val problemTitleView = dialogView.findViewById<TextView>(R.id.modelManagerProblemTitle)
        val problemSubtitleView = dialogView.findViewById<TextView>(R.id.modelManagerProblemSubtitle)
        val problemContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.problemModelContainer)
        val listView = dialogView.findViewById<ListView>(R.id.modelList)
        val importButton = dialogView.findViewById<Button>(R.id.modelManagerImportButton)
        val downloadButton = dialogView.findViewById<Button>(R.id.modelManagerDownloadButton)
        val closeButton = dialogView.findViewById<Button>(R.id.modelManagerCloseButton)
        var dialog: AlertDialog? = null

        titleView.text = getString(R.string.dialog_model_switcher_title)
        subtitleView.text = when {
            modelItems.isNotEmpty() -> getString(R.string.dialog_model_switcher_message)
            problematicModelItems.isNotEmpty() -> getString(R.string.dialog_model_switcher_problem_only_message)
            else -> getString(R.string.dialog_model_empty_message)
        }
        footerHintView.text = if (modelItems.isNotEmpty()) {
            getString(R.string.dialog_model_switcher_hint)
        } else {
            getString(R.string.dialog_model_switcher_problem_only_message)
        }
        footerHintView.visibility = if (modelItems.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        emptyView.text = getString(R.string.dialog_model_empty_message)
        problemTitleView.text = getString(R.string.dialog_model_problem_section_title)
        problemSubtitleView.text = getString(R.string.dialog_model_problem_section_message)

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = modelItems.size

            override fun getItem(position: Int): File? = modelItems.getOrNull(position)

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.dialog_model_row, parent, false)
                val file = getItem(position) ?: return view
                val isCurrent = file.absolutePath == modelPath
                val isPreferred = file.absolutePath == resolvePreferredModelPath()
                bindModelRow(
                    rowView = view,
                    file = file,
                    isCurrent = isCurrent,
                    isPreferred = isPreferred,
                    isBroken = false,
                    allowSelection = true,
                    onSelect = {
                        dialog?.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            activateLocalModel(
                                file,
                                refreshUi = true,
                                progressMessage = getString(R.string.dialog_model_switching_message)
                            )
                        }
                    },
                    onDelete = {
                        confirmDeleteModel(file) {
                            dialog?.dismiss()
                        }
                    }
                )

                return view
            }
        }

        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.TRANSPARENT)
        listView.setDividerHeight(10)
        listView.isVerticalScrollBarEnabled = false
        listView.visibility = if (modelItems.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        emptyView.visibility = if (modelItems.isEmpty() && problematicModelItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        problemTitleView.visibility = if (problematicModelItems.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        problemSubtitleView.visibility = if (problematicModelItems.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        problemContainer.visibility = if (problematicModelItems.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        problemContainer.removeAllViews()
        problematicModelItems.forEach { file ->
            val row = LayoutInflater.from(this).inflate(R.layout.dialog_model_row, problemContainer, false)
            val isCurrent = file.absolutePath == modelPath
            val isPreferred = file.absolutePath == resolvePreferredModelPath()
            bindModelRow(
                rowView = row,
                file = file,
                isCurrent = isCurrent,
                isPreferred = isPreferred,
                isBroken = true,
                allowSelection = false,
                onSelect = null,
                onDelete = {
                    confirmDeleteModel(file) {
                        dialog?.dismiss()
                    }
                }
            )
            problemContainer.addView(row)
        }

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val selected = modelItems.getOrNull(position) ?: return@OnItemClickListener
            dialog?.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                activateLocalModel(
                    selected,
                    refreshUi = true,
                    progressMessage = getString(R.string.dialog_model_switching_message)
                )
            }
        }
        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            modelItems.getOrNull(position)?.let { confirmDeleteModel(it) {
                dialog?.dismiss()
            } }
            true
        }

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        importButton.setOnClickListener {
            dialog?.dismiss()
            pickModelLauncher.launch(arrayOf("*/*"))
        }
        downloadButton.setOnClickListener {
            dialog?.dismiss()
            showModelDownloadDialog()
        }
        closeButton.setOnClickListener {
            dialog?.dismiss()
        }

        dialog.setOnShowListener {
            dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog?.show()
    }

    private fun confirmDeleteModel(modelFile: File, onConfirmedDelete: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_model_delete_title))
            .setMessage(getString(R.string.dialog_model_delete_message, modelFile.name))
            .setPositiveButton(getString(R.string.dialog_model_delete_button)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val wasCurrent = modelFile.absolutePath == modelPath
                        val remainingModels = deleteModelFile(modelFile)
                        withContext(Dispatchers.Main) {
                            onConfirmedDelete?.invoke()
                            if (wasCurrent && remainingModels.isNotEmpty()) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    activateLocalModel(
                                        remainingModels.first(),
                                        refreshUi = true,
                                        progressMessage = getString(R.string.dialog_model_switching_message)
                                    )
                                }
                            } else if (remainingModels.isNotEmpty()) {
                                showModelSwitcherDialog()
                            } else {
                                showModelRequiredDialog()
                            }
                        }
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(getString(R.string.dialog_delete_failed_title))
                                .setMessage(t.message ?: getString(R.string.dialog_model_delete_failed))
                                .setPositiveButton(getString(R.string.dialog_ok), null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private suspend fun deleteModelFile(modelFile: File): List<File> {
        val targetPath = modelFile.absolutePath
        val wasPreferred = resolvePreferredModelPath() == targetPath
        val wasCurrent = modelPath == targetPath

        if (wasCurrent) {
            runCatching { engine.cleanUp() }
        }

        if (!modelFile.delete()) {
            throw IllegalStateException(getString(R.string.dialog_model_delete_failed))
        }

        if (wasPreferred) {
            modelPrefs.edit().remove(PREF_KEY_PREFERRED_MODEL_PATH).apply()
        }
        if (wasCurrent) {
            modelPath = null
        }

        return listLocalModelFiles()
    }

    private fun showCustomUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://.../model.gguf"
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_gguf_url_title))
            .setView(input)
            .setPositiveButton(getString(R.string.dialog_download)) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        downloadAndLoadModel(url)
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_invalid_url_title))
                        .setMessage(getString(R.string.dialog_invalid_url_message))
                        .setPositiveButton(getString(R.string.dialog_ok), null)
                        .show()
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private suspend fun downloadAndLoadModel(url: String) {
        val progress = withContext(Dispatchers.Main) { ImportProgressDialog(this@MainActivity).also { it.show() } }
        var targetFile: File? = null
        try {
            val resolved = resolveModelDownload(url)
            val modelsDir = File(filesDir, "models").apply { mkdirs() }
            val outFile = File(modelsDir, resolved.fileName)
            val tempFile = File(modelsDir, "${resolved.fileName}.download")
            targetFile = tempFile
            runCatching { tempFile.delete() }
            downloadWithProgress(resolved.url, tempFile, progress)
            val downloadedSize = tempFile.length()
            if (tempFile.length() > MAX_MOBILE_MODEL_BYTES) {
                tempFile.delete()
                throw IllegalStateException(getString(R.string.error_model_too_large))
            }
            if (resolved.expectedSizeBytes > 0 && downloadedSize != resolved.expectedSizeBytes) {
                tempFile.delete()
                throw IllegalStateException(
                    getString(
                        R.string.error_download_incomplete,
                        downloadedSize,
                        resolved.expectedSizeBytes
                    )
                )
            }
            if (outFile.exists()) {
                outFile.delete()
            }
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            targetFile = outFile
            activateLocalModel(outFile, refreshUi = true, deleteOnFailure = true)
            withContext(Dispatchers.Main) {
                progress.close()
            }
        } catch (t: Throwable) {
            runCatching { targetFile?.delete() }
            withContext(Dispatchers.Main) {
                progress.close()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_download_failed_title))
                    .setMessage(t.message ?: getString(R.string.dialog_unknown_error))
                    .setPositiveButton(getString(R.string.dialog_retry)) { _, _ -> showModelDownloadDialog() }
                    .setNegativeButton(getString(R.string.dialog_close), null)
                    .show()
            }
        }
    }

    private fun normalizeModelFileName(url: String): String {
        val raw = Uri.parse(url).lastPathSegment ?: "model-${System.currentTimeMillis()}.gguf"
        val clean = raw.substringAfterLast('/').ifBlank { "model-${System.currentTimeMillis()}.gguf" }
        return if (clean.lowercase(Locale.ROOT).endsWith(".gguf")) clean else "$clean.gguf"
    }

    private fun getDeviceTotalMemoryBytes(): Long {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem
    }

    private fun buildModelDownloadMessage(deviceRamBytes: Long): String {
        val deviceRamGb = deviceRamBytes / (1024L * 1024L * 1024L)
        return buildString {
            append(getString(R.string.dialog_model_required_message))
            append('\n')
            append('\n')
            append(getString(R.string.dialog_model_device_detected, getDeviceDisplayName()))
            append('\n')
            append(getString(R.string.dialog_model_ram_detected, deviceRamGb))
        }
    }

    private fun getDeviceDisplayName(): String {
        val manufacturer = Build.MANUFACTURER
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val model = Build.MODEL.trim()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    private fun buildModelDownloadPresets(deviceRamBytes: Long): List<ModelDownloadPreset> {
        val isSufficientFor12B = deviceRamBytes >= 16L * 1024L * 1024L * 1024L
        val isSufficientForE4B = deviceRamBytes >= 8L * 1024L * 1024L * 1024L
        val presets = listOf(
            ModelDownloadPreset(
                label = getString(R.string.model_preset_gemma4_e2b),
                subtitle = getString(R.string.model_preset_gemma4_e2b_subtitle),
                source = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf",
                minimumMemoryBytes = 6L * 1024L * 1024L * 1024L,
                recommended = !isSufficientForE4B,
                customUrl = false,
                familyKey = "gemma-4"
            ),
            ModelDownloadPreset(
                label = getString(R.string.model_preset_gemma4_e4b),
                subtitle = getString(R.string.model_preset_gemma4_e4b_subtitle),
                source = "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf",
                minimumMemoryBytes = 8L * 1024L * 1024L * 1024L,
                recommended = isSufficientForE4B && !isSufficientFor12B,
                customUrl = false,
                familyKey = "gemma-4"
            ),
            ModelDownloadPreset(
                label = getString(R.string.model_preset_gemma4_12b),
                subtitle = getString(R.string.model_preset_gemma4_12b_subtitle),
                source = "https://huggingface.co/google/gemma-4-12B-it-qat-q4_0-gguf",
                minimumMemoryBytes = 16L * 1024L * 1024L * 1024L,
                recommended = isSufficientFor12B,
                customUrl = false,
                familyKey = "gemma-4"
            ),
            ModelDownloadPreset(
                label = getString(R.string.model_preset_gemma),
                subtitle = getString(R.string.model_preset_gemma_subtitle),
                source = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
                minimumMemoryBytes = 4L * 1024L * 1024L * 1024L,
                recommended = false,
                customUrl = false,
                familyKey = "gemma-3"
            ),
            ModelDownloadPreset(
                label = getString(R.string.model_preset_qwen),
                subtitle = getString(R.string.model_preset_qwen_subtitle),
                source = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
                minimumMemoryBytes = 8L * 1024L * 1024L * 1024L,
                recommended = false,
                customUrl = false,
                familyKey = "qwen"
            ),
            ModelDownloadPreset(
                label = getString(R.string.model_preset_llama),
                subtitle = getString(R.string.model_preset_llama_subtitle),
                source = "https://huggingface.co/bartowski/Llama-3.1-8B-Instruct-GGUF/resolve/main/Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                minimumMemoryBytes = 8L * 1024L * 1024L * 1024L,
                recommended = false,
                customUrl = false,
                familyKey = "llama-3.1"
            ),
            ModelDownloadPreset(
                label = getString(R.string.model_preset_custom),
                subtitle = getString(R.string.model_preset_custom_subtitle),
                source = "",
                minimumMemoryBytes = 0L,
                recommended = false,
                customUrl = true
            )
        )

        return presets.map { preset ->
            val enabled = preset.customUrl || deviceRamBytes >= preset.minimumMemoryBytes
            preset.copy(enabled = enabled)
        }.sortedWith(
            compareByDescending<ModelDownloadPreset> { it.recommended && it.enabled }
                .thenByDescending { it.enabled }
                .thenBy { it.customUrl }
                .thenBy { it.label }
        )
    }

    private suspend fun resolveModelDownload(source: String): ResolvedModelDownload {
        val trimmed = source.trim()
        if (trimmed.contains("/resolve/")) {
            return ResolvedModelDownload(trimmed, normalizeModelFileName(trimmed))
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri?.path.orEmpty().trim('/')
        val isHuggingFaceRepo = host.contains("huggingface.co") && path.split('/').size >= 2

        if (!isHuggingFaceRepo) {
            return ResolvedModelDownload(trimmed, normalizeModelFileName(trimmed))
        }

        val parts = path.split('/')
        val repoId = "${parts[0]}/${parts[1]}"
        val repoInfo = fetchJson("https://huggingface.co/api/models/$repoId")
        val siblings = repoInfo.optJSONArray("siblings") ?: JSONArray()
        val candidates = mutableListOf<ResolvedModelCandidate>()

        for (i in 0 until siblings.length()) {
            val sibling = siblings.optJSONObject(i) ?: continue
            val name = sibling.optString("rfilename").ifBlank { sibling.optString("name") }
            if (!name.lowercase(Locale.ROOT).endsWith(".gguf")) continue
            if (name.lowercase(Locale.ROOT).contains("mmproj")) continue
            candidates += ResolvedModelCandidate(
                fileName = name,
                size = sibling.optLong("size", 0L)
            )
        }

        val selected = candidates
            .sortedWith(
                compareByDescending<ResolvedModelCandidate> { it.size }
                    .thenByDescending { it.fileName.contains("q4_0", ignoreCase = true) }
                    .thenByDescending { it.fileName.contains("q4", ignoreCase = true) }
                    .thenBy { it.fileName }
            )
            .firstOrNull()
            ?: throw IllegalStateException("No GGUF file found in Hugging Face repo: $repoId")

        val resolvedUrl = "https://huggingface.co/$repoId/resolve/main/${selected.fileName}?download=1"
        return ResolvedModelDownload(resolvedUrl, selected.fileName, selected.size)
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.connect()
        return connection.inputStream.bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }
    }

    private suspend fun downloadWithProgress(
        url: String,
        target: File,
        progress: ImportProgressDialog
    ) {
        withContext(Dispatchers.IO) {
            var redirectCount = 0
            var currentUrl = url
            while (true) {
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.requestMethod = "GET"
                connection.connect()

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || redirectCount >= 5) {
                        throw IllegalStateException(getString(R.string.error_redirect_failed))
                    }
                    currentUrl = location
                    redirectCount++
                    continue
                }

                if (code !in 200..299) {
                    val msg = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    connection.disconnect()
                    throw IllegalStateException("HTTP $code ${if (msg.isBlank()) "" else "- $msg"}")
                }

                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var read = input.read(buffer)
                        while (read >= 0) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                runOnUiThread { progress.updateProgress(pct) }
                            }
                            read = input.read(buffer)
                        }
                        out.flush()
                    }
                }
                connection.disconnect()
                runOnUiThread { progress.updateProgress(100) }
                return@withContext
            }
        }
    }

    private suspend fun importAndLoadModel(uri: Uri) {
        val progress = withContext(Dispatchers.Main) { ImportProgressDialog(this@MainActivity).also { it.show() } }

        try {
            val displayName = queryDisplayName(uri) ?: "model-${System.currentTimeMillis()}.gguf"
            val normalizedName = if (displayName.lowercase(Locale.ROOT).endsWith(".gguf")) {
                displayName
            } else {
                "$displayName.gguf"
            }
            val modelsDirectory = File(filesDir, "models").apply { mkdirs() }
            val outFile = File(modelsDirectory, normalizedName)
            val tempFile = File(modelsDirectory, "$normalizedName.import")
            runCatching { tempFile.delete() }

            val sourceSize = contentResolver.openFileDescriptor(uri, "r")?.statSize ?: -1L
            contentResolver.openInputStream(uri)?.use { input ->
                copyWithProgress(input, tempFile, sourceSize, progress)
            } ?: throw IllegalStateException(getString(R.string.error_read_model_file))
            if (tempFile.length() > MAX_MOBILE_MODEL_BYTES) {
                tempFile.delete()
                throw IllegalStateException(getString(R.string.error_model_too_large))
            }
            if (outFile.exists()) {
                outFile.delete()
            }
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            activateLocalModel(outFile, refreshUi = true)
            withContext(Dispatchers.Main) {
                progress.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Model import failed for uri=$uri", t)
            withContext(Dispatchers.Main) {
                progress.close()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_import_failed_title))
                    .setMessage(t.message ?: getString(R.string.dialog_unknown_error))
                    .setPositiveButton(getString(R.string.dialog_pick_again)) { _, _ ->
                        pickModelLauncher.launch(arrayOf("*/*"))
                    }
                    .setNegativeButton(getString(R.string.dialog_close)) { _, _ -> }
                    .show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        target: File,
        total: Long,
        progress: ImportProgressDialog
    ) {
        withContext(Dispatchers.IO) {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = 0L
                var read = input.read(buffer)
                while (read >= 0) {
                    out.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                        runOnUiThread { progress.updateProgress(pct) }
                    }
                    read = input.read(buffer)
                }
                out.flush()
            }
        }
        runOnUiThread { progress.updateProgress(100) }
    }

    private fun activeModelName(): String {
        return modelPath?.let { File(it).nameWithoutExtension } ?: "local-model"
    }

    private enum class ArtifactFormat(val extension: String, val mimeType: String) {
        PDF("pdf", "application/pdf"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        TXT("txt", "text/plain"),
        MD("md", "text/markdown"),
        CSV("csv", "text/csv"),
        JSON("json", "application/json"),
        HTML("html", "text/html")
    }

    private data class ArtifactRequest(
        val formats: Set<ArtifactFormat>,
        val packageAsZip: Boolean,
        val baseName: String,
        val sourceText: String
    )

    private fun handleCompletion(body: JSONObject): JSONObject {
        val stream = body.optBoolean("stream", true)
        val messages = body.optJSONArray("messages") ?: JSONArray()
        val prompt = extractLastUserMessage(messages)
            .ifBlank { extractAttachmentContextFallback(messages) }
        val predictLength = resolvePredictLength(body)
        val artifactReply = maybeCreateArtifactsReply(messages, prompt)
        val text = stripModelTags(artifactReply ?: runBlocking {
            engineMutex.withLock {
                engine.sendUserPrompt(prompt, predictLength).toList().joinToString("")
            }
        })

        return if (stream) {
            // stream is handled by LocalApiServer; here we return final payload shape only
            JSONObject().put("content", text)
        } else {
            createChatCompletionJson(text)
        }
    }

    private fun maybeCreateArtifactsReply(messages: JSONArray, prompt: String): String? {
        val request = buildArtifactRequest(messages, prompt) ?: return null

        return runCatching {
            val root = File(filesDir, "generated-artifacts").apply { mkdirs() }
            cleanupGeneratedArtifacts(root)

            val artifactId = "artifact-${System.currentTimeMillis()}"
            val targetDir = File(root, artifactId).apply { mkdirs() }
            val generated = mutableListOf<File>()

            for (format in request.formats) {
                val file = File(targetDir, "${request.baseName}.${format.extension}")
                when (format) {
                    ArtifactFormat.PDF -> writePdfFile(file, request.sourceText)
                    ArtifactFormat.DOCX -> writeDocxFile(file, request.sourceText)
                    ArtifactFormat.XLSX -> writeXlsxFile(file, request.sourceText)
                    ArtifactFormat.TXT -> file.writeText(request.sourceText, Charsets.UTF_8)
                    ArtifactFormat.MD -> file.writeText(request.sourceText, Charsets.UTF_8)
                    ArtifactFormat.CSV -> {
                        val csv = request.sourceText
                            .lineSequence()
                            .map { line -> "\"${line.replace("\"", "\"\"")}\"" }
                            .joinToString("\n")
                        file.writeText(csv, Charsets.UTF_8)
                    }
                    ArtifactFormat.JSON -> {
                        val json = JSONObject()
                            .put("createdAt", System.currentTimeMillis())
                            .put("content", request.sourceText)
                        file.writeText(json.toString(2), Charsets.UTF_8)
                    }
                    ArtifactFormat.HTML -> {
                        val html = """
                            <!doctype html>
                            <html lang="de">
                              <head><meta charset="utf-8"><title>${request.baseName}</title></head>
                              <body><pre>${escapeXml(request.sourceText)}</pre></body>
                            </html>
                        """.trimIndent()
                        file.writeText(html, Charsets.UTF_8)
                    }
                }
                generated += file
            }

            val exposed = mutableListOf<File>()
            if (request.packageAsZip && generated.isNotEmpty()) {
                val zipFile = File(targetDir, "${request.baseName}.zip")
                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    for (file in generated) {
                        zip.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                exposed += zipFile
            } else {
                exposed += generated
            }

            val lines = mutableListOf<String>()
            lines += "Artefakte erstellt (${request.sourceText.length} Zeichen Quelle):"
            exposed.forEach { file ->
                val url = "http://127.0.0.1:$chatApiPort/artifacts/$artifactId/${file.name}"
                lines += "- [${file.name}]($url)"
            }
            lines += ""
            lines += "Tippe auf einen Link, um den Download zu starten."
            lines.joinToString("\n")
        }.onFailure {
            Log.e(TAG, "Artifact creation failed", it)
        }.getOrNull()
    }

    private fun buildArtifactRequest(messages: JSONArray, prompt: String): ArtifactRequest? {
        val lower = prompt.lowercase(Locale.ROOT)
        val wantsCreate = Regex("\\b(erstell\\w*|erzeug\\w*|generier\\w*|mach\\w*|create|generate|build)\\b")
            .containsMatchIn(lower)
        val mentionsFiles = listOf("pdf", "word", "docx", "excel", "xlsx", "zip", "datei", "file")
            .any { lower.contains(it) }
        if (!wantsCreate || !mentionsFiles) return null

        val formats = linkedSetOf<ArtifactFormat>()
        if (lower.contains("pdf")) formats += ArtifactFormat.PDF
        if (lower.contains("word") || lower.contains("docx")) formats += ArtifactFormat.DOCX
        if (lower.contains("excel") || lower.contains("xlsx")) formats += ArtifactFormat.XLSX
        if (lower.contains("txt") || lower.contains("textdatei")) formats += ArtifactFormat.TXT
        if (lower.contains("markdown") || lower.contains(".md")) formats += ArtifactFormat.MD
        if (lower.contains("csv")) formats += ArtifactFormat.CSV
        if (lower.contains("json")) formats += ArtifactFormat.JSON
        if (lower.contains("html")) formats += ArtifactFormat.HTML
        if (formats.isEmpty()) return null

        val wantsZip = lower.contains("zip") || lower.contains("paket")
        val sourceText = selectArtifactSourceText(messages, prompt)
        if (sourceText.isBlank()) return null

        val baseName = sanitizeArtifactBaseName(
            Regex("\\b(?:namens|name|filename|dateiname)\\s+([a-zA-Z0-9_\\-]+)")
                .find(prompt)
                ?.groupValues
                ?.getOrNull(1)
                ?: "chat_export_${System.currentTimeMillis()}"
        )

        return ArtifactRequest(
            formats = formats,
            packageAsZip = wantsZip,
            baseName = baseName,
            sourceText = sourceText.take(180_000)
        )
    }

    private fun selectArtifactSourceText(messages: JSONArray, prompt: String): String {
        val lower = prompt.lowercase(Locale.ROOT)
        val wantsAbove = lower.contains("von oben") || lower.contains("from above") || lower.contains("aktuellen text")
        val flat = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val content = item.opt("content")
            val text = when (content) {
                is JSONArray -> extractTextFromContentParts(content)
                is String -> content
                null -> ""
                else -> content.toString()
            }.trim()
            if (text.isNotBlank()) flat += role to text
        }

        if (wantsAbove) {
            val assistant = flat.lastOrNull { it.first == "assistant" }?.second
            if (!assistant.isNullOrBlank()) return assistant
        }

        val assistant = flat.lastOrNull { it.first == "assistant" }?.second
        if (!assistant.isNullOrBlank()) return assistant

        return flat.lastOrNull { it.first == "user" }?.second ?: ""
    }

    private fun sanitizeArtifactBaseName(input: String): String {
        val safe = input
            .trim()
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (safe.isBlank()) "chat_export" else safe.take(60)
    }

    private fun cleanupGeneratedArtifacts(root: File) {
        val now = System.currentTimeMillis()
        val maxAgeMs = 3L * 24L * 60L * 60L * 1000L
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && now - dir.lastModified() > maxAgeMs) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    private fun writePdfFile(file: File, text: String) {
        val document = PdfDocument()
        try {
            val paint = Paint().apply { textSize = 12f }
            val pageWidth = 595
            val pageHeight = 842
            val margin = 40
            val lineHeight = 18
            val maxLinesPerPage = (pageHeight - (margin * 2)) / lineHeight
            val lines = wrapTextForPdf(text, paint, (pageWidth - (margin * 2)).toFloat())
            var index = 0
            var pageNumber = 1
            while (index < lines.size) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                val canvas = page.canvas
                var y = margin.toFloat()
                var row = 0
                while (index < lines.size && row < maxLinesPerPage) {
                    canvas.drawText(lines[index], margin.toFloat(), y, paint)
                    y += lineHeight.toFloat()
                    row++
                    index++
                }
                document.finishPage(page)
                pageNumber++
            }
            FileOutputStream(file).use { out -> document.writeTo(out) }
        } finally {
            document.close()
        }
    }

    private fun wrapTextForPdf(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (rawLine in text.replace("\r", "").split("\n")) {
            val words = rawLine.split(" ")
            if (words.isEmpty()) {
                result += ""
                continue
            }
            var current = ""
            for (word in words) {
                val candidate = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotBlank()) result += current
                    current = word
                }
            }
            result += current
        }
        return if (result.isEmpty()) listOf("") else result
    }

    private fun writeDocxFile(file: File, text: String) {
        val paragraphs = text.replace("\r", "").split("\n").ifEmpty { listOf("") }
            .joinToString("") { line ->
                "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
            }
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                $paragraphs
                <w:sectPr>
                  <w:pgSz w:w="11906" w:h="16838"/>
                  <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
                </w:sectPr>
              </w:body>
            </w:document>
        """.trimIndent()
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent()
        val rels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypes.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(rels.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun writeXlsxFile(file: File, text: String) {
        val rows = text.replace("\r", "").split("\n").ifEmpty { listOf("") }
            .take(2000)
            .mapIndexed { index, line ->
                val rowNumber = index + 1
                """<row r="$rowNumber"><c r="A$rowNumber" t="inlineStr"><is><t>${escapeXml(line)}</t></is></c></row>"""
            }
            .joinToString("")

        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
        """.trimIndent()
        val rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
        """.trimIndent()
        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="Export" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()
        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$rows</sheetData>
            </worksheet>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypes.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(rootRels.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbookXml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(workbookRels.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace(Regex("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]"), "")
    }

    /**
     * Accept both OpenAI-style `max_tokens` and llama.cpp-style `n_predict`.
     * If unlimited/invalid is requested (<= 0), use a generous local default
     * so long document answers (PDF/DOCX context) are not cut off at 512.
     */
    private fun resolvePredictLength(body: JSONObject): Int {
        val candidate = when {
            body.has("max_tokens") -> body.optInt("max_tokens", 0)
            body.has("n_predict") -> body.optInt("n_predict", 0)
            else -> 0
        }
        val fallback = 2048
        return if (candidate <= 0) fallback else candidate.coerceIn(1, 4096)
    }

    private fun createChatCompletionJson(content: String): JSONObject {
        val id = "chatcmpl-${UUID.randomUUID()}"
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", activeModelName())
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", JSONObject().put("role", "assistant").put("content", content))
                        .put("finish_reason", "stop")
                )
            )
            .put("usage", JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0).put("total_tokens", 0))
    }

    private fun extractLastUserMessage(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val item = messages.optJSONObject(i) ?: continue
            if (item.optString("role") == "user") {
                val content = item.opt("content")
                return when (content) {
                    is JSONArray -> extractTextFromContentParts(content)
                    is String -> content
                    null -> ""
                    else -> content.toString()
                }
            }
        }
        return ""
    }

    private fun extractTextFromContentParts(parts: JSONArray): String {
        val textParts = mutableListOf<String>()
        for (j in 0 until parts.length()) {
            val part = parts.optJSONObject(j) ?: continue
            if (part.optString("type") == "text") {
                val text = part.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    textParts += text
                }
            }
        }

        if (textParts.isNotEmpty()) {
            return textParts.joinToString("\n\n")
        }
        return ""
    }

    private fun extractAttachmentContextFallback(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val item = messages.optJSONObject(i) ?: continue
            if (item.optString("role") != "user") continue

            val content = item.opt("content")
            val synthetic = when (content) {
                is JSONArray -> synthesizeAttachmentContext(content)
                is String -> content.trim()
                null -> ""
                else -> content.toString().trim()
            }

            if (synthetic.isNotBlank()) {
                return synthetic
            }
        }
        return ""
    }

    private fun synthesizeAttachmentContext(parts: JSONArray): String {
        val markers = mutableListOf<String>()
        val text = extractTextFromContentParts(parts)

        for (j in 0 until parts.length()) {
            val part = parts.optJSONObject(j) ?: continue
            when (part.optString("type")) {
                "image_url" -> markers += "Bild angehängt"
                "input_audio" -> markers += "Audio angehängt"
            }
        }

        return when {
            text.isNotBlank() -> text.trim()
            markers.isNotEmpty() -> "Kontext:\n- ${markers.distinct().joinToString("\n- ")}"
            else -> ""
        }
    }
}

private class ImportProgressDialog(activity: AppCompatActivity) {
    private val dialog: AlertDialog
    private val messageView: TextView
    private val progressBar: ProgressBar
    private val percentLabel: TextView

    init {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_model_import, null, false)
        messageView = view.findViewById(R.id.importMessage)
        progressBar = view.findViewById(R.id.importProgressBar)
        percentLabel = view.findViewById(R.id.importPercent)
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_model_import_title))
            .setView(view)
            .setCancelable(false)
            .create()
    }

    fun show() = dialog.show()
    fun close() = dialog.dismiss()
    fun setMessage(message: String) {
        messageView.text = message
    }

    fun updateProgress(value: Int) {
        val v = value.coerceIn(0, 100)
        progressBar.progress = v
        percentLabel.text = "$v%"
    }
}

private fun stripModelTags(input: String): String {
    var text = input

    val blockPatterns = listOf(
        Regex("(?s)<\\|channel\\>thought.*?<channel\\|>"),
        Regex("(?s)<think>.*?</think>"),
        Regex("(?s)<\\|think\\>.*?<\\|/think\\>")
    )
    for (pattern in blockPatterns) {
        text = pattern.replace(text, "")
    }

    val tokenPatterns = listOf(
        Regex("(?m)^\\s*<\\|channel\\>thought\\s*"),
        Regex("(?m)^\\s*<channel\\|>\\s*"),
        Regex("(?m)^\\s*<think>\\s*"),
        Regex("(?m)^\\s*</think>\\s*"),
        Regex("(?m)^\\s*Thinking Process:\\s*")
    )
    for (pattern in tokenPatterns) {
        text = pattern.replace(text, "")
    }

    return text.trim()
}

private class LocalApiServer(
    port: Int,
    private val webRootResolver: () -> android.content.res.AssetManager,
    private val getModelName: () -> String,
    private val onChatCompletion: (JSONObject) -> JSONObject,
    private val artifactsRootResolver: () -> File
) : NanoHTTPD("127.0.0.1", port) {
    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/health" && session.method == Method.GET -> jsonResponse(
                    JSONObject()
                        .put("ok", true)
                        .put("server", "chat-api")
                        .put("model", getModelName())
                )

                session.uri == "/v1/models" && session.method == Method.GET -> jsonResponse(
                    JSONObject()
                        .put("object", "list")
                        .put(
                            "data",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", getModelName())
                                    .put("object", "model")
                                    .put("owned_by", "local")
                            )
                        )
                )

                session.uri == "/props" && session.method == Method.GET -> jsonResponse(
                    JSONObject()
                        .put("model_path", getModelName())
                        .put("chat_template", "")
                        .put("total_slots", 1)
                        .put("default_generation_settings", JSONObject().put("n_ctx", 4096))
                        .put(
                            "modalities",
                            JSONObject()
                                .put("vision", false)
                                .put("audio", false)
                        )
                )

                session.uri == "/slots" && session.method == Method.GET -> jsonResponse(
                    JSONArray().put(JSONObject().put("id", 0).put("is_processing", false))
                )

                session.uri == "/v1/chat/completions" && session.method == Method.POST -> {
                    val body = parseBodyJson(session)
                    val stream = body.optBoolean("stream", true)
                    val completion = onChatCompletion(body)

                    if (!stream) {
                        jsonResponse(completion)
                    } else {
                        val text = completion.optString("content")
                        sseResponse(text, getModelName())
                    }
                }

                session.uri == "/tools" && session.method == Method.GET -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"tools disabled"}""")
                }

                session.uri.startsWith("/artifacts/") && session.method == Method.GET -> {
                    artifactResponse(session.uri)
                }

                else -> staticResponse(session.uri)
            }
        } catch (t: Throwable) {
            Log.e("LocalApiServer", "serve error", t)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", t.message ?: "internal error").toString()
            )
        }
    }

    private fun parseBodyJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: ""
        return JSONObject(raw)
    }

    private fun staticResponse(uri: String): Response {
        val clean = when (uri) {
            "/", "" -> "index.html"
            else -> uri.removePrefix("/")
        }
        val path = "webui/$clean"
        return try {
            val assets = webRootResolver()
            val stream = assets.open(path)
            newChunkedResponse(Response.Status.OK, mimeFor(path), stream)
        } catch (_: Throwable) {
            try {
                val stream = webRootResolver().open("webui/index.html")
                newChunkedResponse(Response.Status.OK, "text/html", stream)
            } catch (_: Throwable) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }
    }

    private fun artifactResponse(uri: String): Response {
        val clean = uri.removePrefix("/artifacts/").trim()
        if (clean.isBlank() || clean.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid artifact path")
        }

        val root = artifactsRootResolver().canonicalFile
        val candidate = File(root, clean).canonicalFile
        if (!candidate.path.startsWith(root.path) || !candidate.exists() || !candidate.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "artifact not found")
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            mimeFor(candidate.name),
            FileInputStream(candidate)
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"${candidate.name}\"")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun sseResponse(text: String, model: String): Response {
        val visibleText = stripModelTags(text)
        val id = "chatcmpl-${UUID.randomUUID()}"
        val chunk = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", JSONObject().put("content", visibleText))
                        .put("finish_reason", JSONObject.NULL)
                )
            )
        val done = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", JSONObject())
                        .put("finish_reason", "stop")
                )
            )

        val sse = buildString {
            append("data: ").append(chunk.toString()).append("\n\n")
            append("data: ").append(done.toString()).append("\n\n")
            append("data: [DONE]\n\n")
        }
        val resp = newFixedLengthResponse(Response.Status.OK, "text/event-stream", sse)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        return resp
    }

    private fun jsonResponse(obj: Any): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
    }

    private fun mimeFor(path: String): String {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".html") -> "text/html"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".csv") -> "text/csv"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".md") -> "text/markdown"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}

private class LocalAnalysisServer(
    private val appContext: android.content.Context,
    port: Int
) : NanoHTTPD("127.0.0.1", port) {
    override fun serve(session: IHTTPSession): Response {
        return try {
            val response = when {
                session.method == Method.OPTIONS ->
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")

                session.uri == "/health" && session.method == Method.GET ->
                    jsonOk(JSONObject().put("ok", true))

                session.uri == "/analyze" && session.method == Method.POST -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val filePath = files["file"] ?: files.values.firstOrNull().orEmpty()
                    if (filePath.isBlank() || !File(filePath).exists()) {
                        jsonOk(JSONObject().put("ok", false).put("error", "file missing"))
                    } else {
                        val file = File(filePath)
                        val originalName = session.parameters["file"]?.firstOrNull()?.trim()
                        val analysis = analyzeFile(file, originalName)
                        jsonOk(JSONObject().put("ok", true).put("analysis", analysis))
                    }
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"ok":false}""")
            }
            withCors(response)
        } catch (t: Throwable) {
            withCors(
                newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("ok", false).put("error", t.message ?: "internal error").toString()
                )
            )
        }
    }

    private fun analyzeFile(file: File, originalName: String? = null): JSONObject {
        val name = originalName?.takeIf { it.isNotBlank() } ?: file.name
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val imageExts = setOf("png", "jpg", "jpeg", "webp", "bmp", "heic", "heif")
        val audioExts = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac", "amr", "3gp")
        val videoExts = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "mpeg", "mpg", "3gp")
        val officeZipExts = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")

        val text = when (ext) {
            "txt", "md", "json", "xml", "yaml", "yml", "cs", "csproj", "sln", "kt", "java",
            "csv", "tsv", "log", "ini", "conf", "properties", "rtf" ->
                file.readTextSafe()
            in officeZipExts ->
                extractOfficeContainerText(file, name)
            "zip" -> summarizeZip(file)
            in imageExts -> analyzeImage(file)
            in audioExts -> analyzeMedia(file, name, isVideo = false)
            in videoExts -> analyzeMedia(file, name, isVideo = true)
            else -> {
                val inferredOfficeKind = detectOfficeKindFromZip(file)
                if (inferredOfficeKind != null) {
                    extractOfficeContainerText(file, name)
                } else {
                    appContext.getString(R.string.analysis_binary_uploaded, name)
                }
            }
        }.take(120000)

        return buildAnalysisSummary(
            fileName = name,
            detectedKind = ext.ifBlank { "file" },
            rawText = text,
            fileSize = file.length()
        )
    }

    private fun buildAnalysisSummary(
        fileName: String,
        detectedKind: String,
        rawText: String,
        fileSize: Long
    ): JSONObject {
        val normalized = rawText.replace("\r", "").trim()
        val characters = normalized.length
        val estimatedTokens = maxOf(1, ceil(characters / 4.0).toInt())
        val keyPoints = extractKeyPoints(fileName, detectedKind, normalized)
        val keywords = extractKeywords(normalized)
        val chunks = buildTextChunks(normalized)
        val summary = keyPoints.firstOrNull() ?: appContext.getString(R.string.analysis_file_analyzed, fileName)

        val keyPointsJson = JSONArray()
        keyPoints.forEach { keyPointsJson.put(it) }

        val keywordsJson = JSONArray()
        keywords.forEach { keywordsJson.put(it) }

        val chunksJson = JSONArray()
        chunks.forEachIndexed { index, chunk ->
            chunksJson.put(
                JSONObject()
                    .put("id", "chunk-${index + 1}")
                    .put("title", "Section ${index + 1}")
                    .put("text", chunk)
                    .put("keywords", JSONArray(extractKeywords(chunk)))
                    .put("characters", chunk.length)
            )
        }

        val metadata = JSONObject()
            .put("size", fileSize)
            .put("languageHint", Locale.getDefault().toLanguageTag())

        return JSONObject()
            .put("title", fileName)
            .put("detectedKind", detectedKind)
            .put("summary", summary)
            .put("keyPoints", keyPointsJson)
            .put("keywords", keywordsJson)
            .put("characters", characters)
            .put("estimatedTokens", estimatedTokens)
            .put("chunks", chunksJson)
            .put("metadata", metadata)
            .put("tables", JSONArray())
            .put("rawText", normalized)
    }

    private fun extractKeyPoints(fileName: String, detectedKind: String, text: String): List<String> {
        val result = mutableListOf<String>()
        result += appContext.getString(R.string.analysis_filename, fileName)
        result += appContext.getString(R.string.analysis_file_type, detectedKind)

        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(4)
            .map { if (it.length > 180) "${it.take(177)}..." else it }
            .toList()
        result += lines

        return result.take(6)
    }

    private fun extractKeywords(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val stopWords = setOf(
            "the", "and", "for", "with", "this", "that", "you", "your",
            "und", "der", "die", "das", "ein", "eine", "mit", "ist", "sind"
        )

        val frequencies = linkedMapOf<String, Int>()
        text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 4 }
            .filterNot { stopWords.contains(it) }
            .forEach { token ->
                frequencies[token] = (frequencies[token] ?: 0) + 1
            }

        return frequencies.entries
            .sortedByDescending { it.value }
            .take(12)
            .map { it.key }
    }

    private fun buildTextChunks(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val chunkSize = 1300
        val overlap = 140
        val chunks = mutableListOf<String>()
        var cursor = 0

        while (cursor < text.length && chunks.size < 8) {
            val end = minOf(text.length, cursor + chunkSize)
            val chunk = text.substring(cursor, end).trim()
            if (chunk.isNotEmpty()) {
                chunks += chunk
            }
            if (end >= text.length) {
                break
            }
            cursor = end - overlap
        }

        return chunks
    }

    private fun analyzeImage(file: File): String {
        val uri = Uri.fromFile(file)
        val image = runCatching { InputImage.fromFilePath(appContext, uri) }.getOrNull()
            ?: return appContext.getString(R.string.analysis_image_unavailable, file.name)

        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.55f)
                .build()
        )

        val ocrText = runCatching {
            extractBestOcrText(file, image, textRecognizer)
        }.getOrElse { "" }.trim()

        val labels = runCatching {
            Tasks.await(labeler.process(image), 18, TimeUnit.SECONDS)
                .sortedByDescending { it.confidence }
                .take(10)
                .map { "${it.text} (${String.format(Locale.US, "%.2f", it.confidence)})" }
        }.getOrElse { emptyList() }

        runCatching { textRecognizer.close() }
        runCatching { labeler.close() }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight

        val ocrSummary = if (ocrText.isBlank()) appContext.getString(R.string.analysis_none) else ocrText
        val labelSummary = if (labels.isEmpty()) appContext.getString(R.string.analysis_none) else labels.joinToString(", ")

        return buildString {
            append(appContext.getString(R.string.analysis_image_uploaded, file.name)).append('\n')
            if (width > 0 && height > 0) {
                append(appContext.getString(R.string.analysis_resolution, width, height)).append('\n')
            }
            append(appContext.getString(R.string.analysis_ocr_header)).append('\n').append(ocrSummary).append('\n')
            append(appContext.getString(R.string.analysis_labels_header, labelSummary))
        }
    }

    private fun extractBestOcrText(
        file: File,
        fallbackImage: InputImage,
        textRecognizer: com.google.mlkit.vision.text.TextRecognizer
    ): String {
        val sourceBitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (sourceBitmap == null) {
            return runCatching {
                Tasks.await(textRecognizer.process(fallbackImage), 18, TimeUnit.SECONDS).text
            }.getOrElse { "" }
        }

        val candidates = mutableListOf<Pair<String, Int>>()
        val variants = listOf(
            prepareOcrVariant(sourceBitmap, highContrast = false),
            prepareOcrVariant(sourceBitmap, highContrast = true)
        )
        val rotationAttempts = listOf(0, 90, 270)

        for (variant in variants) {
            for (rotation in rotationAttempts) {
                val text = runCatching {
                    val input = InputImage.fromBitmap(variant, rotation)
                    Tasks.await(textRecognizer.process(input), 18, TimeUnit.SECONDS).text
                }.getOrElse { "" }.trim()
                if (text.isNotBlank()) {
                    candidates += text to scoreOcrText(text)
                }
            }

            if (variant !== sourceBitmap && !variant.isRecycled) {
                runCatching { variant.recycle() }
            }
        }

        if (!sourceBitmap.isRecycled) {
            runCatching { sourceBitmap.recycle() }
        }

        if (candidates.isEmpty()) {
            return ""
        }

        val merged = mergeOcrCandidates(
            candidates
                .sortedByDescending { it.second }
                .take(3)
                .map { it.first }
        )
        return merged.ifBlank { candidates.maxByOrNull { it.second }?.first.orEmpty() }
    }

    private fun mergeOcrCandidates(texts: List<String>): String {
        if (texts.isEmpty()) {
            return ""
        }

        val mergedLines = linkedSetOf<String>()
        for (text in texts) {
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val normalized = line.replace(Regex("\\s+"), " ")
                    if (normalized.length >= 2) {
                        mergedLines += normalized
                    }
                }
        }

        return mergedLines.joinToString("\n").trim()
    }

    private fun prepareOcrVariant(source: Bitmap, highContrast: Boolean): Bitmap {
        val targetMinSide = if (highContrast) 1800 else 1400
        val maxSide = max(source.width, source.height)
        val scale = if (maxSide < targetMinSide) {
            targetMinSide.toFloat() / maxSide.toFloat()
        } else {
            1f
        }

        val scaled = if (scale > 1.01f) {
            val w = (source.width * scale).roundToInt().coerceAtLeast(1)
            val h = (source.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, w, h, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (!highContrast) {
            return scaled
        }

        val enhanced = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.35f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(grayscale)
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        if (!scaled.isRecycled && scaled !== source) {
            runCatching { scaled.recycle() }
        }
        return enhanced
    }

    private fun scoreOcrText(text: String): Int {
        if (text.isBlank()) {
            return 0
        }
        val cleaned = text.trim()
        val alphaNum = cleaned.count { it.isLetterOrDigit() }
        val words = cleaned.split(Regex("\\s+")).count { it.length >= 2 }
        return alphaNum + words * 10
    }

    private fun analyzeMedia(file: File, displayName: String, isVideo: Boolean): String {
        val retriever = MediaMetadataRetriever()
        var durationMs: Long? = null
        var mime: String? = null
        var bitrate: String? = null
        var width: Int? = null
        var height: Int? = null

        runCatching {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (isVideo) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            }
        }
        runCatching { retriever.release() }

        val transcript = transcribeMediaWithLocalRecognizer(file)
        val transcriptText = transcript?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.analysis_none)

        return buildString {
            append(if (isVideo) "Video uploaded: " else "Audio uploaded: ").append(displayName).append('\n')
            if (mime != null) append("MIME: ").append(mime).append('\n')
            if (durationMs != null) append("Duration: ").append(durationMs).append(" ms").append('\n')
            if (bitrate != null) append("Bitrate: ").append(bitrate).append('\n')
            if (isVideo && width != null && height != null) {
                append("Resolution: ").append(width).append("x").append(height).append('\n')
            }
            append("Transcription:\n").append(transcriptText)
        }
    }

    private fun transcribeMediaWithLocalRecognizer(file: File): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return null
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return null
        }

        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        var transcript: String? = null
        var recognizer: SpeechRecognizer? = null
        var parcel: android.os.ParcelFileDescriptor? = null

        mainHandler.post {
            try {
                parcel = android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )

                recognizer = if (
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onResults(results: Bundle?) {
                        transcript =
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                        latch.countDown()
                    }

                    override fun onError(error: Int) {
                        latch.countDown()
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, parcel)
                }

                recognizer?.startListening(intent)
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        latch.await(14, TimeUnit.SECONDS)

        mainHandler.post {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            runCatching { parcel?.close() }
        }

        return transcript
    }

    private fun summarizeZip(file: File): String {
        val builder = StringBuilder("ZIP entries:\n")
        ZipInputStream(FileInputStream(file)).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null && count < 300) {
                if (!entry.isDirectory) {
                    builder.append("- ").append(entry.name).append('\n')
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return builder.toString()
    }

    private fun detectOfficeKindFromZip(file: File): String? {
        if (!file.exists() || file.length() < 4L) return null
        return runCatching {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name.lowercase(Locale.ROOT) }.toList()
                when {
                    names.any { it.startsWith("word/") } -> "docx"
                    names.any { it.startsWith("xl/") } -> "xlsx"
                    names.any { it.startsWith("ppt/") } -> "pptx"
                    names.any { it == "content.xml" } -> "odf"
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun extractOfficeContainerText(file: File, displayName: String): String {
        return runCatching {
            val snippets = mutableListOf<String>()
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter {
                        val lower = it.name.lowercase(Locale.ROOT)
                        lower == "content.xml" ||
                            lower == "word/document.xml" ||
                            lower.startsWith("word/header") ||
                            lower.startsWith("word/footer") ||
                            lower.startsWith("word/footnotes") ||
                            lower.startsWith("word/endnotes") ||
                            lower == "xl/sharedstrings.xml" ||
                            lower.startsWith("xl/worksheets/sheet") ||
                            lower.startsWith("ppt/slides/slide") ||
                            lower.startsWith("ppt/notesSlides/notesSlide")
                    }
                    .sortedBy { it.name }
                    .take(36)
                    .toList()

                entries.forEach { entry ->
                    val xml = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val extracted = extractReadableTextFromXml(xml)
                    if (extracted.isNotBlank()) {
                        snippets += "[${entry.name}]\n$extracted"
                    }
                }
            }

            if (snippets.isEmpty()) {
                appContext.getString(R.string.analysis_binary_uploaded, displayName)
            } else {
                snippets.joinToString("\n\n")
            }
        }.getOrElse {
            appContext.getString(R.string.analysis_binary_uploaded, displayName)
        }
    }

    private fun extractReadableTextFromXml(xml: String): String {
        if (xml.isBlank()) return ""
        val withBreaks = xml
            .replace(Regex("(?i)</(w:p|w:tr|p|text:p|a:p|tr|h[1-6]|div|li)>"), "\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")

        val noTags = withBreaks.replace(Regex("<[^>]+>"), " ")
        val unescaped = noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                val code = match.groupValues[1].toIntOrNull() ?: return@replace " "
                code.toChar().toString()
            }

        return unescaped
            .lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(120000)
    }

    private fun File.readTextSafe(): String {
        return try {
            val bytes = this.readBytes()
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun jsonOk(obj: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
    }

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
        return response
    }
}

    private data class ModelDownloadPreset(
        val label: String,
        val subtitle: String,
        val source: String,
        val minimumMemoryBytes: Long,
        val recommended: Boolean,
        val customUrl: Boolean,
        val familyKey: String? = null,
        val enabled: Boolean = true
    )

private data class ResolvedModelDownload(
    val url: String,
    val fileName: String,
    val expectedSizeBytes: Long = -1L
)

private data class ResolvedModelCandidate(
    val fileName: String,
    val size: Long
)
