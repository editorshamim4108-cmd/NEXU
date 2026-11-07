package com.boss.nexu

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningActive = false
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val speechToken = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#050508")
        window.navigationBarColor = Color.parseColor("#050508")

        pendingWake = intent?.getBooleanExtra("WAKE_WORD_TRIGGERED", false) == true

        requestStartupPermissions()

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        notifySpeakDone()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        notifySpeakDone()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        notifySpeakDone()
                    }
                })
            }
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#050508"))
        }
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString JARVIS-Android/2.0"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val micGranted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (micGranted) {
                    request.grant(request.resources)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
                    request.deny()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (pendingWake) {
                    pendingWake = false
                    evaluateJs("window.__jarvisWake && window.__jarvisWake();")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (url.host == "appassets.androidplatform.net" || url.scheme == "file") {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }

        webView.addJavascriptInterface(JarvisBridge(), "AndroidJarvis")
        webView.loadUrl("https://appassets.androidplatform.net/assets/jarvis/index.html")
    }

    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("WAKE_WORD_TRIGGERED", false) == true) {
            evaluateJs("window.__jarvisWake && window.__jarvisWake();")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopSpeakingInternal()
        destroySpeechRecognizer()
        try { tts?.shutdown() } catch (_: Exception) {}
        ioExecutor.shutdownNow()
        if (::webView.isInitialized) {
            try { webView.destroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun evaluateJs(script: String) {
        mainHandler.post {
            if (::webView.isInitialized) {
                try {
                    webView.evaluateJavascript(script, null)
                } catch (_: Exception) {}
            }
        }
    }

    private fun notifySpeakDone() {
        evaluateJs("window.__jarvisNativeSpeakDone && window.__jarvisNativeSpeakDone();")
    }

    // ---------------------------------------------------------------------
    // Native Speech Recognition (Hands-free Bangla / Hindi / English)
    // ---------------------------------------------------------------------
    private fun destroySpeechRecognizer() {
        isListeningActive = false
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListeningActive = false
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        evaluateJs(
                            "window.__jarvisNativePartial && window.__jarvisNativePartial(" +
                                JSONObject.quote(partial) + ");"
                        )
                    }
                }

                override fun onError(error: Int) {
                    isListeningActive = false
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT
                    ) {
                        destroySpeechRecognizer()
                    }
                    evaluateJs("window.__jarvisNativeError && window.__jarvisNativeError($error);")
                }

                override fun onResults(results: Bundle?) {
                    isListeningActive = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotEmpty()) {
                        evaluateJs(
                            "window.__jarvisNativeResult && window.__jarvisNativeResult(" +
                                JSONObject.quote(text) + ");"
                        )
                    } else {
                        evaluateJs(
                            "window.__jarvisNativeError && window.__jarvisNativeError(${SpeechRecognizer.ERROR_NO_MATCH});"
                        )
                    }
                }
            })
        }
    }

    private fun startListening(lang: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
            evaluateJs(
                "window.__jarvisNativeError && window.__jarvisNativeError(${SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS});"
            )
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Boss, ভয়েস শোনার জন্য ফোনে Google অ্যাপ চালু/আপডেট রাখুন।",
                Toast.LENGTH_LONG
            ).show()
            evaluateJs(
                "window.__jarvisNativeError && window.__jarvisNativeError(${SpeechRecognizer.ERROR_CLIENT});"
            )
            return
        }

        if (isListeningActive) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            isListeningActive = false
        }

        ensureSpeechRecognizer()

        val chosenLang = if (lang.isNullOrBlank()) "bn-BD" else lang
        val recogIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, chosenLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, chosenLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            isListeningActive = true
            speechRecognizer?.startListening(recogIntent)
        } catch (_: Exception) {
            destroySpeechRecognizer()
            evaluateJs(
                "window.__jarvisNativeError && window.__jarvisNativeError(${SpeechRecognizer.ERROR_CLIENT});"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Native Neural Voice + Offline TTS Fallback (Element 3)
    // ---------------------------------------------------------------------
    private fun stopSpeakingInternal() {
        speechToken.incrementAndGet()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun splitTextChunks(text: String, maxLen: Int = 180): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= maxLen) return listOf(cleaned)

        val chunks = mutableListOf<String>()
        val sentences = cleaned.split(Regex("(?<=[।.!?\\n])\\s*"))
        var current = StringBuilder()
        for (s in sentences) {
            if (s.isBlank()) continue
            if (current.length + s.length + 1 <= maxLen) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(s)
            } else {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current = StringBuilder()
                }
                if (s.length <= maxLen) {
                    current.append(s)
                } else {
                    var idx = 0
                    while (idx < s.length) {
                        val end = (idx + maxLen).coerceAtMost(s.length)
                        chunks.add(s.substring(idx, end))
                        idx = end
                    }
                }
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks.take(6)
    }

    private fun speakNeuralOrOffline(text: String, langCode: String?, speed: Float) {
        // প্রথমে মাইক থামানো হচ্ছে যাতে নিজের গলার আওয়াজ নিজে না শোনে
        if (isListeningActive) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            isListeningActive = false
        }
        stopSpeakingInternal()
        val token = speechToken.get()
        val safeSpeed = speed.coerceIn(0.75f, 1.4f)
        val tl = when {
            langCode.isNullOrBlank() -> "bn"
            langCode.lowercase(Locale.ROOT).startsWith("en") -> "en"
            langCode.lowercase(Locale.ROOT).startsWith("hi") -> "hi"
            else -> "bn"
        }

        val chunks = splitTextChunks(text, 180)
        if (chunks.isEmpty()) {
            notifySpeakDone()
            return
        }

        ioExecutor.execute {
            val tempFiles = mutableListOf<File>()
            var allDownloaded = true
            for ((index, chunk) in chunks.withIndex()) {
                if (speechToken.get() != token) return@execute
                try {
                    val encoded = URLEncoder.encode(chunk, "UTF-8")
                    val url = URL("https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$encoded")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5500
                        readTimeout = 6500
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                        )
                    }
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.size > 200) {
                            val f = File(cacheDir, "jarvis_tts_${token}_$index.mp3")
                            FileOutputStream(f).use { it.write(bytes) }
                            tempFiles.add(f)
                        } else {
                            allDownloaded = false
                            break
                        }
                    } else {
                        allDownloaded = false
                        break
                    }
                } catch (_: Exception) {
                    allDownloaded = false
                    break
                }
            }

            if (speechToken.get() != token) return@execute

            if (allDownloaded && tempFiles.isNotEmpty()) {
                mainHandler.post { playChunkQueue(tempFiles, 0, safeSpeed, token, text, tl) }
            } else {
                mainHandler.post { speakLocalTts(text, tl, safeSpeed) }
            }
        }
    }

    private fun playChunkQueue(
        files: List<File>,
        index: Int,
        speed: Float,
        token: Int,
        fullTextFallback: String,
        tl: String
    ) {
        if (speechToken.get() != token) return
        if (index >= files.size) {
            notifySpeakDone()
            return
        }
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(files[index].absolutePath)
                prepare()
                try {
                    playbackParams = PlaybackParams().setSpeed(speed)
                } catch (_: Exception) {}
                setOnCompletionListener { mp ->
                    try { mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    files[index].delete()
                    playChunkQueue(files, index + 1, speed, token, fullTextFallback, tl)
                }
                setOnErrorListener { mp, _, _ ->
                    try { mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    speakLocalTts(fullTextFallback, tl, speed)
                    true
                }
                start()
            }
        } catch (_: Exception) {
            if (index == 0) {
                speakLocalTts(fullTextFallback, tl, speed)
            } else {
                notifySpeakDone()
            }
        }
    }

    private fun speakLocalTts(text: String, tl: String, speed: Float) {
        val engine = tts
        if (engine == null || !ttsReady) {
            notifySpeakDone()
            return
        }
        val locale = when (tl) {
            "en" -> Locale.US
            "hi" -> Locale("hi", "IN")
            else -> Locale("bn", "BD")
        }
        val res = engine.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(speed)
        val speakRes = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "JARVIS_${System.currentTimeMillis()}"
        )
        if (speakRes == TextToSpeech.ERROR) {
            notifySpeakDone()
        }
    }

    // ---------------------------------------------------------------------
    // Native Phone Controls (Flashlight, App Launcher, Floating Bubble)
    // ---------------------------------------------------------------------
    private fun toggleTorch(turnOn: Boolean) {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, turnOn)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "ফ্ল্যাশলাইট চালু করা যাচ্ছে না Boss", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppByName(rawName: String): Boolean {
        val name = rawName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false

        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "হোয়াটসঅ্যাপ" to "com.whatsapp",
            "হোয়াটসঅ্যাপে" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "ইউটিউব" to "com.google.android.youtube",
            "facebook" to "com.facebook.katana",
            "ফেসবুক" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "মেসেঞ্জার" to "com.facebook.orca",
            "instagram" to "com.instagram.android",
            "ইনস্টাগ্রাম" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "টিকটক" to "com.zhiliaoapp.musically",
            "chrome" to "com.android.chrome",
            "ক্রোম" to "com.android.chrome",
            "gemini" to "com.google.android.apps.bard",
            "জেমিনি" to "com.google.android.apps.bard",
            "chatgpt" to "com.openai.chatgpt",
            "চ্যাটজিপিটি" to "com.openai.chatgpt",
            "gmail" to "com.google.android.gm",
            "জিমেইল" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "ম্যাপ" to "com.google.android.apps.maps",
            "telegram" to "org.telegram.messenger",
            "টেলিগ্রাম" to "org.telegram.messenger",
            "bkash" to "com.bKash.customerapp",
            "বিকাশ" to "com.bKash.customerapp",
            "nagad" to "com.konasl.nagad",
            "নগদ" to "com.konasl.nagad",
            "settings" to "com.android.settings",
            "সেটিংস" to "com.android.settings",
            "camera" to "com.android.camera2",
            "ক্যামেরা" to "com.android.camera2"
        )
        knownPackages[name]?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                return true
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
        for (info in apps) {
            val label = info.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
            if (label.isNotEmpty() && (label.contains(name) || name.contains(label))) {
                val pkg = info.activityInfo.packageName
                packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    return true
                }
            }
        }
        Toast.makeText(this, "\"$rawName\" নামের অ্যাপ পাওয়া যাচ্ছে না Boss", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun handleToggleFloatingMode(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Boss, ভাসমান বাটন চালু করতে 'Display over other apps' অনুমতিটি ON করুন",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {}
            return false
        }

        return if (JarvisFloatingService.isRunning) {
            stopService(Intent(this, JarvisFloatingService::class.java))
            Toast.makeText(this, "Floating Mode বন্ধ করা হয়েছে Boss", Toast.LENGTH_SHORT).show()
            false
        } else {
            val svc = Intent(this, JarvisFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            Toast.makeText(
                this,
                "JARVIS ভাসমান বাটন চালু হয়েছে! অন্য যেকোনো অ্যাপে গিয়ে 🎙 বাটনে ট্যাপ করুন",
                Toast.LENGTH_LONG
            ).show()
            true
        }
    }

    // ---------------------------------------------------------------------
    // JavaScript Bridge -> window.AndroidJarvis
    // ---------------------------------------------------------------------
    inner class JarvisBridge {

        private val prefs by lazy {
            getSharedPreferences("jarvis_native_prefs", Context.MODE_PRIVATE)
        }

        @JavascriptInterface
        fun saveSetting(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun getSetting(key: String, defaultValue: String): String {
            return prefs.getString(key, defaultValue) ?: defaultValue
        }

        @JavascriptInterface
        fun startSpeechRecognition(lang: String?) {
            mainHandler.post { startListening(lang) }
        }

        @JavascriptInterface
        fun stopSpeechRecognition() {
            mainHandler.post {
                isListeningActive = false
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun speak(text: String, lang: String?) {
            mainHandler.post { speakNeuralOrOffline(text, lang, 1.0f) }
        }

        @JavascriptInterface
        fun speakNeuralOrTts(text: String, lang: String?, speed: Float) {
            mainHandler.post { speakNeuralOrOffline(text, lang, speed) }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            mainHandler.post { stopSpeakingInternal() }
        }

        @JavascriptInterface
        fun getScreenText(): String {
            return try {
                NexuVisualAccessibilityService.instance?.readVisibleScreenText()
                    ?: NexuVisualAccessibilityService.lastExternalScreenText
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean {
            return NexuVisualAccessibilityService.instance != null
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            mainHandler.post {
                try {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Boss, তালিকা থেকে JARVIS Screen Vision সার্ভিসটি ON করুন",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun toggleFloatingOverlay(): Boolean {
            mainHandler.post {
                val started = handleToggleFloatingMode()
                evaluateJs("window.__jarvisFloatingState && window.__jarvisFloatingState($started);")
            }
            return JarvisFloatingService.isRunning
        }

        @JavascriptInterface
        fun toggleFlashlight(turnOn: Boolean) {
            mainHandler.post { toggleTorch(turnOn) }
        }

        @JavascriptInterface
        fun scrollDown(): Boolean {
            return try {
                NexuVisualAccessibilityService.instance?.scrollDownScreen() == true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun lockPhone(): Boolean {
            mainHandler.post {
                val locked = try {
                    NexuVisualAccessibilityService.instance?.lockPhoneScreen() == true
                } catch (_: Exception) {
                    false
                }
                if (!locked) {
                    Toast.makeText(
                        this@MainActivity,
                        "Boss, ভয়েসে ফোন লক করতে Accessibility সার্ভিস ON লাগবে — Settings খুলে দিচ্ছি",
                        Toast.LENGTH_LONG
                    ).show()
                    openAccessibilitySettings()
                }
            }
            return true
        }

        @JavascriptInterface
        fun openApp(appName: String): Boolean {
            mainHandler.post { openAppByName(appName) }
            return true
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }
}
