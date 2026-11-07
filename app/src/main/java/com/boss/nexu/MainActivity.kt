package com.boss.nexu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/**
 * JARVIS — মোবাইল অ্যাপের মূল স্ক্রিন।
 *
 * আপনার দেওয়া JARVIS HTML UI-টা (assets/jarvis/index.html) WebView-তে চলে,
 * আর যেসব কাজ ব্রাউজার/WebView একা পারে না, সেগুলো নেটিভ Android দিয়ে করা হয়:
 *  - মাইকে কথা শোনা  -> Android SpeechRecognizer  (AndroidJarvis.startSpeechRecognition)
 *  - কথা বলা (ফলব্যাক) -> Android TextToSpeech      (AndroidJarvis.speak)
 *  - স্ক্রিনের লেখা পড়া -> NexuVisualAccessibilityService (AndroidJarvis.getScreenText)
 *  - ফ্ল্যাশলাইট / ফোন লক / অ্যাপ খোলা -> নেটিভ ব্রিজ
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pendingWake = intent?.getBooleanExtra("WAKE_WORD_TRIGGERED", false) == true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        webView = WebView(this)
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString JARVIS-Android/1.0"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val micGranted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (micGranted) {
                    request.grant(request.resources)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    request.deny()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
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
                if (url.scheme == "file") return false
                // বাইরের লিংক ফোনের ব্রাউজারে খুলবে, অ্যাপের ভেতরে আটকে থাকবে না
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        webView.addJavascriptInterface(JarvisBridge(), "AndroidJarvis")
        webView.loadUrl("file:///android_asset/jarvis/index.html")
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
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun evaluateJs(script: String) {
        mainHandler.post {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Native Speech Recognition (WebView-তে webkitSpeechRecognition নেই)
    // ---------------------------------------------------------------------
    private fun startListening(lang: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            evaluateJs("window.__jarvisNativeError && window.__jarvisNativeError();")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Boss, এই ফোনে Google Speech Recognition পাওয়া যাচ্ছে না। Google অ্যাপ আপডেট করুন।",
                Toast.LENGTH_LONG
            ).show()
            evaluateJs("window.__jarvisNativeError && window.__jarvisNativeError();")
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        evaluateJs("window.__jarvisNativeError && window.__jarvisNativeError();")
                    }

                    override fun onResults(results: Bundle?) {
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
                            evaluateJs("window.__jarvisNativeError && window.__jarvisNativeError();")
                        }
                    }
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang.isNullOrBlank()) "bn-BD" else lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            evaluateJs("window.__jarvisNativeError && window.__jarvisNativeError();")
        }
    }

    private fun speakNative(text: String, langCode: String?) {
        val engine = tts ?: return
        if (!ttsReady) return
        val code = (langCode ?: "").lowercase(Locale.ROOT)
        val locale = when {
            code.startsWith("en") -> Locale.US
            code.startsWith("hi") -> Locale("hi", "IN")
            code.startsWith("bn") -> Locale("bn", "BD")
            else -> Locale("bn", "BD")
        }
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_${System.currentTimeMillis()}")
    }

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
        } catch (e: Exception) {
            Toast.makeText(this, "ফ্ল্যাশলাইট চালু করা যাচ্ছে না Boss", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppByName(rawName: String): Boolean {
        val name = rawName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false

        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "হোয়াটসঅ্যাপ" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "ইউটিউব" to "com.google.android.youtube",
            "facebook" to "com.facebook.katana",
            "ফেসবুক" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "ক্রোম" to "com.android.chrome",
            "gemini" to "com.google.android.apps.bard",
            "জেমিনি" to "com.google.android.apps.bard",
            "settings" to "com.android.settings",
            "সেটিংস" to "com.android.settings",
            "camera" to "com.android.camera2",
            "ক্যামেরা" to "com.android.camera2"
        )
        knownPackages[name]?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                startActivity(launch)
                return true
            }
        }

        // ইনস্টল করা অ্যাপের নাম মিলিয়ে খোঁজা
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
        for (info in apps) {
            val label = info.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
            if (label.contains(name) || name.contains(label)) {
                val pkg = info.activityInfo.packageName
                packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                    startActivity(launch)
                    return true
                }
            }
        }
        Toast.makeText(this, "\"$rawName\" নামের অ্যাপ পাওয়া যাচ্ছে না Boss", Toast.LENGTH_SHORT).show()
        return false
    }

    // ---------------------------------------------------------------------
    // HTML (JavaScript) থেকে ডাকা নেটিভ ব্রিজ: window.AndroidJarvis.*
    // ---------------------------------------------------------------------
    inner class JarvisBridge {

        @JavascriptInterface
        fun startSpeechRecognition(lang: String?) {
            mainHandler.post { startListening(lang) }
        }

        @JavascriptInterface
        fun stopSpeechRecognition() {
            mainHandler.post {
                try { speechRecognizer?.stopListening() } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun speak(text: String, lang: String?) {
            mainHandler.post { speakNative(text, lang) }
        }

        /** Accessibility Service চালু থাকলে অন্য অ্যাপের স্ক্রিনের লেখা; নিজের অ্যাপের লেখা বাদ। */
        @JavascriptInterface
        fun getScreenText(): String {
            return try {
                NexuVisualAccessibilityService.instance?.readVisibleScreenText() ?: ""
            } catch (e: Exception) {
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
                        "Boss, তালিকা থেকে JARVIS/NEXU সার্ভিসটি ON করুন",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun toggleFlashlight(turnOn: Boolean) {
            mainHandler.post { toggleTorch(turnOn) }
        }

        @JavascriptInterface
        fun lockPhone(): Boolean {
            var locked = false
            mainHandler.post {
                locked = try {
                    NexuVisualAccessibilityService.instance?.lockPhoneScreen() == true
                } catch (e: Exception) {
                    false
                }
                if (!locked) {
                    Toast.makeText(
                        this@MainActivity,
                        "Boss, লক করতে Accessibility সার্ভিস ON লাগবে — Settings খুলে দিচ্ছি",
                        Toast.LENGTH_LONG
                    ).show()
                    openAccessibilitySettings()
                }
            }
            return locked
        }

        @JavascriptInterface
        fun openApp(appName: String): Boolean {
            var opened = false
            mainHandler.post { opened = openAppByName(appName) }
            return opened
        }
    }

    companion object {
        private const val REQ_MIC = 1001
    }
}
