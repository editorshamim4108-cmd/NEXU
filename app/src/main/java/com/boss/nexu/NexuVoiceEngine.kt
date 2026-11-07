package com.boss.nexu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AssistantStatus(val label: String, val bengaliNote: String) {
    OFFLINE("Offline", "বস, আমি এখন অফলাইনে আছি। 'Hey Boss' বলে বা ট্যাপ করে ডাকুন।"),
    LISTENING("Listening...", "হ্যাঁ বস, আমি এখানে আছি। বলুন কী করতে হবে?"),
    UNDERSTANDING("Understanding Command...", "বস, আপনার নির্দেশটি বিশ্লেষণ করছি..."),
    OPENING_APP("Opening App...", "বস, আপনার চোখের সামনে অ্যাপটি খুলছি..."),
    SEARCHING("Searching...", "বস, আপনার কাঙ্ক্ষিত তথ্য বা কন্টাক্ট খুঁজছি..."),
    SCROLLING("Scrolling...", "বস, স্ক্রিন নিচে স্ক্রল করছি..."),
    CLICKING("Clicking...", "বস, নির্দিষ্ট বাটনে ক্লিক করছি..."),
    PERMISSION_REQUIRED("Permission Required...", "বস, এই কাজের জন্য আপনার অনুমতি প্রয়োজন।"),
    WAITING_APPROVAL("Waiting for Approval...", "বস, মেসেজ বা অ্যাকশনটি পাঠানোর আগে আপনার কনফার্মেশন চাই।"),
    PAUSED_WAIT_UNLOCK("Paused — Waiting for Unlock...", "বস, ফোনটি আনলক করলে আমি আগের কাজটি আবার শুরু করব।"),
    RESUMING_TASK("Resuming Previous Task...", "ধন্যবাদ বস! আগের অসমাপ্ত কাজটি চালিয়ে যাচ্ছি।"),
    PERFORMING_ACTION("Performing Action...", "বস, কাজটি সম্পন্ন করছি..."),
    SPEAKING("Speaking...", "বসকে লাইভ আপডেট জানাচ্ছি..."),
    COMPLETED("Completed...", "বস, আপনার কাজটি সম্পন্ন হয়েছে। পরবর্তী নির্দেশের জন্য প্রস্তুত।"),
    FAILED("Failed...", "বস, এই মুহূর্তে কাজটি সম্পন্ন করা যায়নি।")
}

data class ActivityLogItem(
    val timestamp: String,
    val title: String,
    val detail: String
)

data class PendingTaskContext(
    val taskType: String,
    val targetApp: String,
    val targetContact: String = "",
    val messageBody: String = "",
    val requiresAccessibility: Boolean = false
)

class NexuVoiceEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("nexu_boss_memory", Context.MODE_PRIVATE)

    // Persistent Session State
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive

    private val _status = MutableStateFlow(AssistantStatus.OFFLINE)
    val status: StateFlow<AssistantStatus> = _status

    private val _lastHeardText = MutableStateFlow("এখনো কোনো ভয়েস কমান্ড দেওয়া হয়নি")
    val lastHeardText: StateFlow<String> = _lastHeardText

    private val _nexuReplyText = MutableStateFlow("হেই বস! আমি NEXU। কাজের সময় হাত না লাগিয়েই আমাকে নির্দেশ দিন।")
    val nexuReplyText: StateFlow<String> = _nexuReplyText

    // Voice & Identity Customization
    private val _speechRate = MutableStateFlow(prefs.getFloat("speech_rate", 1.0f))
    val speechRate: StateFlow<Float> = _speechRate

    private val _speechPitch = MutableStateFlow(prefs.getFloat("speech_pitch", 1.0f))
    val speechPitch: StateFlow<Float> = _speechPitch

    private val _languageLocale = MutableStateFlow("bn-BD")
    val languageLocale: StateFlow<String> = _languageLocale

    // Two-Hour Rest Monitor (Default 120 minutes)
    private val _activeUsageMinutes = MutableStateFlow(0)
    val activeUsageMinutes: StateFlow<Int> = _activeUsageMinutes
    private val _restLimitMinutes = MutableStateFlow(120)
    val restLimitMinutes: StateFlow<Int> = _restLimitMinutes
    private val _showRestAlert = MutableStateFlow(false)
    val showRestAlert: StateFlow<Boolean> = _showRestAlert

    // Pending Task Context (Automatic Resume after Permission or Phone Unlock)
    private val _pendingTask = MutableStateFlow<PendingTaskContext?>(null)
    val pendingTask: StateFlow<PendingTaskContext?> = _pendingTask

    // Personal Memory Notes
    private val _savedMemories = MutableStateFlow<List<String>>(loadSavedMemories())
    val savedMemories: StateFlow<List<String>> = _savedMemories

    private val _activityLogs = MutableStateFlow<List<ActivityLogItem>>(
        listOf(
            ActivityLogItem(getCurrentTime(), "System Online", "NEXU 14-Module Core প্রস্তুত, বস।")
        )
    )
    val activityLogs: StateFlow<List<ActivityLogItem>> = _activityLogs

    // Phone Unlock Receiver (Automatic Resume After Phone Unlock)
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val task = _pendingTask.value
                if (task != null) {
                    _status.value = AssistantStatus.RESUMING_TASK
                    addLog("Phone Unlocked", "ফোন আনলক হয়েছে। আগের কাজ Resume করা হচ্ছে...")
                    speak("ধন্যবাদ বস! ফোন আনলক হয়েছে। এখন আগের কাজটি চালিয়ে যাচ্ছি।")
                    resumePendingTask()
                }
            }
        }
    }

    init {
        initTextToSpeech()
        initSpeechRecognizer()
        startTwoHourRestTimer()
        context.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    private fun startTwoHourRestTimer() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                _activeUsageMinutes.value += 1
                if (_activeUsageMinutes.value >= _restLimitMinutes.value && !_showRestAlert.value) {
                    _showRestAlert.value = true
                    speak("বস, আপনি টানা দুই ঘণ্টা কাজ করছেন। আপনার এখন একটু বিশ্রামের সময়।")
                }
                mainHandler.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    fun dismissRestAlert() {
        _showRestAlert.value = false
        _activeUsageMinutes.value = 0
        speak("ঠিক আছে বস, বিশ্রামের টাইমার রিসেট করে দিয়েছি।")
    }

    fun checkAndResumeAfterPermission() {
        val task = _pendingTask.value ?: return
        if (task.requiresAccessibility && NexuVisualAccessibilityService.isServiceConnected()) {
            _status.value = AssistantStatus.RESUMING_TASK
            addLog("Permission Granted", "Accessibility Permission পাওয়া গেছে।")
            speak("ধন্যবাদ বস! পারমিশন পেয়ে গেছি। এখন আগের কাজটি চালিয়ে যাচ্ছি।")
            resumePendingTask()
        }
    }

    private fun resumePendingTask() {
        val task = _pendingTask.value ?: return
        _pendingTask.value = null
        when (task.taskType) {
            "READ_SCREEN" -> performSmartReadAloud()
            "SCROLL_DOWN" -> performAutoScroll()
            "WHATSAPP_PREP" -> prepareWhatsAppMessage(task.targetContact, task.messageBody)
            "GEMINI_PROMPT" -> sendPromptToGemini(task.messageBody)
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(context) { initStatus ->
            if (initStatus == TextToSpeech.SUCCESS) {
                val banglaLocale = Locale("bn", "BD")
                val result = tts?.setLanguage(banglaLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("bn", "IN"))
                }
                isTtsReady = true
                setupTtsListener()
            }
        }
    }

    private fun setupTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _status.value = AssistantStatus.SPEAKING
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == "SHUTDOWN_UTTERANCE") {
                    _isSessionActive.value = false
                    _status.value = AssistantStatus.OFFLINE
                    stopForegroundVoiceService()
                } else if (_isSessionActive.value) {
                    mainHandler.postDelayed({
                        startListeningInternal()
                    }, 350)
                } else {
                    _status.value = AssistantStatus.OFFLINE
                }
            }

            override fun onError(utteranceId: String?) {
                if (_isSessionActive.value) {
                    mainHandler.postDelayed({ startListeningInternal() }, 500)
                }
            }
        })
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _status.value = AssistantStatus.LISTENING
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _status.value = AssistantStatus.UNDERSTANDING
                    }

                    override fun onError(error: Int) {
                        if (_isSessionActive.value) {
                            mainHandler.postDelayed({
                                if (_isSessionActive.value && _status.value != AssistantStatus.SPEAKING) {
                                    startListeningInternal()
                                }
                            }, 900)
                        } else {
                            _status.value = AssistantStatus.OFFLINE
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim() ?: ""
                        if (spokenText.isNotEmpty()) {
                            _lastHeardText.value = spokenText
                            addLog("Boss Voice", spokenText)
                            processBossCommand(spokenText)
                        } else if (_isSessionActive.value) {
                            startListeningInternal()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            _lastHeardText.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    fun startPersistentSession() {
        _isSessionActive.value = true
        startForegroundVoiceService()
        addLog("Persistent Mode", "Always-Active Voice Session চালু হয়েছে।")
        speak(
            text = "হ্যাঁ বস, আমি এখানে আছি। বলুন কী করতে হবে?",
            utteranceId = "GREETING_UTTERANCE"
        )
    }

    fun stopPersistentSession() {
        _isSessionActive.value = false
        mainHandler.post { speechRecognizer?.cancel() }
        addLog("Session Offline", "বসের নির্দেশে NEXU অফলাইনে যাচ্ছে।")
        speak(
            text = "ঠিক আছে বস, আমি এখন অফলাইন হচ্ছি। আবার ডাকলেই চলে আসব।",
            utteranceId = "SHUTDOWN_UTTERANCE"
        )
    }

    private fun startForegroundVoiceService() {
        try {
            val serviceIntent = Intent(context, NexuVoiceForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            addLog("Service Note", "Foreground service সক্রিয় আছে।")
        }
    }

    private fun stopForegroundVoiceService() {
        try {
            val serviceIntent = Intent(context, NexuVoiceForegroundService::class.java)
            context.stopService(serviceIntent)
        } catch (_: Exception) {}
    }

    private fun startListeningInternal() {
        if (!_isSessionActive.value) return
        mainHandler.post {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, _languageLocale.value)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {}
        }
    }

    fun simulateCommandFromUi(command: String) {
        _lastHeardText.value = command
        addLog("Boss Command", command)
        processBossCommand(command)
    }

    private fun processBossCommand(command: String) {
        _status.value = AssistantStatus.UNDERSTANDING
        val lower = command.lowercase(Locale.ROOT)

        // ১. Stop / Sleep / Cancel Command
        if (lower.contains("অফ হয়ে যাও") ||
            lower.contains("ঘুমিয়ে পড়ো") ||
            lower.contains("stop listening") ||
            lower.contains("go offline")
        ) {
            stopPersistentSession()
            return
        }

        if (lower == "থামো" || lower == "stop" || lower == "cancel" || lower.contains("কাজ থামাও")) {
            _pendingTask.value = null
            _status.value = AssistantStatus.COMPLETED
            speak("ঠিক আছে বস, চলমান কাজটি থামিয়ে দিয়েছি। পরবর্তী নির্দেশ বলুন।")
            return
        }

        // ২. Permission Confirmation ("হ্যাঁ খুলে দাও")
        if ((lower.contains("হ্যাঁ") || lower.contains("খুলে দাও") || lower.contains("yes")) &&
            _pendingTask.value?.requiresAccessibility == true
        ) {
            speak("ঠিক আছে বস, এখন Accessibility Permission সেটিংস খুলছি। NEXU সিলেক্ট করে অন করে দিন।")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        // ৩. Hey Boss Greeting
        if (lower.contains("hey boss") || lower.contains("হেই বস")) {
            speak("হ্যাঁ বস, আমি এখানে আছি। বলুন কী করতে হবে?")
            return
        }

        // ৪. Smart Read Aloud ("পড়ে শোনাও")
        if (lower.contains("পড়ে শোনাও") || lower.contains("read aloud") || lower.contains("স্ক্রিন পড়ো")) {
            performSmartReadAloud()
            return
        }

        // ৫. Auto Scroll ("স্ক্রল করো" / "নিচে যাও")
        if (lower.contains("স্ক্রল") || lower.contains("scroll") || lower.contains("নিচে যাও")) {
            performAutoScroll()
            return
        }

        // ৬. Voice Phone Lock ("ফোনটা লক করে দাও")
        if (lower.contains("লক করে দাও") || lower.contains("lock phone") || lower.contains("ফোন লক")) {
            performVoicePhoneLock()
            return
        }

        // ৭. Flashlight Control ("ফ্ল্যাশলাইট")
        if (lower.contains("ফ্ল্যাশলাইট") || lower.contains("flashlight") || lower.contains("টর্চ")) {
            val turnOn = !lower.contains("বন্ধ") && !lower.contains("off")
            toggleFlashlight(turnOn)
            return
        }

        // ৮. WhatsApp Messaging Flow
        if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ")) {
            prepareWhatsAppMessage("", command)
            return
        }

        // ৯. Gemini AI Control
        if (lower.contains("gemini") || lower.contains("জেমিনি")) {
            sendPromptToGemini(command)
            return
        }

        // ১০. Play Store App Download & Install
        if (lower.contains("play store") || lower.contains("ডাউনলোড") || lower.contains("ইনস্টল")) {
            val query = command.replace("ডাউনলোড করো", "").replace("ইনস্টল করো", "").trim()
            openPlayStoreSearch(query)
            return
        }

        // ১১. Contact Creation Preview
        if (lower.contains("কন্টাক্ট সেভ") || lower.contains("save contact") || lower.contains("নম্বর সেভ")) {
            openContactCreatorPreview(command)
            return
        }

        // ১২. Alarm / Timer
        if (lower.contains("অ্যালার্ম") || lower.contains("alarm") || lower.contains("টাইমার")) {
            speak("ঠিক আছে বস, আপনার জন্য অ্যালার্ম ও টাইমার খুলছি।")
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        // ১৩. YouTube / Chrome / Settings
        if (lower.contains("youtube") || lower.contains("ইউটিউব")) {
            openApp("com.google.android.youtube", "YouTube")
            return
        }
        if (lower.contains("chrome") || lower.contains("ক্রোম") || lower.contains("google")) {
            openApp("com.android.chrome", "Chrome")
            return
        }
        if (lower.contains("settings") || lower.contains("সেটিংস")) {
            _status.value = AssistantStatus.OPENING_APP
            speak("ঠিক আছে বস, এখন ফোনের সেটিংস খুলছি।")
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        // ১৪. Personal Memory Save ("মনে রাখো")
        if (lower.contains("মনে রাখো") || lower.contains("remember")) {
            saveMemoryItem(command)
            speak("ঠিক আছে বস, আপনার এই তথ্যটি আমি লোকাল মেমোরিতে সংরক্ষণ করে রেখেছি।")
            return
        }

        // ১৫. ডিফল্ট উত্তর
        _status.value = AssistantStatus.COMPLETED
        speak("বস, আপনার নির্দেশ '$command' শুনেছি। আর কী করতে হবে বলুন?")
    }

    private fun performSmartReadAloud() {
        val service = NexuVisualAccessibilityService.instance
        if (service == null) {
            _pendingTask.value = PendingTaskContext(
                taskType = "READ_SCREEN",
                targetApp = "Current Screen",
                requiresAccessibility = true
            )
            _status.value = AssistantStatus.PERMISSION_REQUIRED
            speak("হেই বস, স্ক্রিনের লেখা পড়ে শোনানোর জন্য আমার Accessibility Permission লাগবে। আমি কি Permission Settings খুলব?")
            return
        }
        _status.value = AssistantStatus.PERFORMING_ACTION
        val screenText = service.readVisibleScreenText()
        if (screenText.isBlank()) {
            speak("বস, বর্তমান স্ক্রিনে কোনো পড়ার মতো লেখা খুঁজে পাইনি।")
        } else {
            speak("বস, স্ক্রিনে লেখা আছে: ${screenText.take(350)}")
        }
    }

    private fun performAutoScroll() {
        val service = NexuVisualAccessibilityService.instance
        if (service == null) {
            _pendingTask.value = PendingTaskContext(
                taskType = "SCROLL_DOWN",
                targetApp = "Current Screen",
                requiresAccessibility = true
            )
            _status.value = AssistantStatus.PERMISSION_REQUIRED
            speak("বস, নিজে থেকে স্ক্রল করার জন্য Accessibility Permission লাগবে। আমি কি Permission Settings খুলব?")
            return
        }
        _status.value = AssistantStatus.SCROLLING
        val scrolled = service.scrollDownScreen()
        if (scrolled) {
            speak("বস, স্ক্রিন নিচে স্ক্রল করেছি।")
        } else {
            speak("বস, এই স্ক্রিনটি আর নিচে স্ক্রল করা যাচ্ছে না।")
        }
    }

    private fun performVoicePhoneLock() {
        val service = NexuVisualAccessibilityService.instance
        if (service != null && service.lockPhoneScreen()) {
            speak("ঠিক আছে বস, আপনার ফোন লক করে দিচ্ছি।")
        } else {
            _status.value = AssistantStatus.PERMISSION_REQUIRED
            speak("বস, ভয়েস দিয়ে ফোন লক করার জন্য Accessibility Permission লাগবে। আমি কি Permission Settings খুলব?")
            _pendingTask.value = PendingTaskContext(
                taskType = "LOCK_PHONE",
                targetApp = "System Lock",
                requiresAccessibility = true
            )
        }
    }

    private fun toggleFlashlight(turnOn: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, turnOn)
            _status.value = AssistantStatus.COMPLETED
            speak(if (turnOn) "বস, ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি।" else "বস, ফ্ল্যাশলাইট বন্ধ করে দিয়েছি।")
        } catch (e: Exception) {
            speak("বস, ফ্ল্যাশলাইট নিয়ন্ত্রণে সমস্যা হচ্ছে।")
        }
    }

    private fun prepareWhatsAppMessage(contact: String, rawCommand: String) {
        _status.value = AssistantStatus.OPENING_APP
        addLog("WhatsApp Flow", "১. WhatsApp খোঁজা হচ্ছে -> ২. প্রিভিউ প্রস্তুত হচ্ছে")
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            _status.value = AssistantStatus.WAITING_APPROVAL
            speak("ঠিক আছে বস, এখন WhatsApp খুলছি। মেসেজ পাঠানোর আগে আপনার কনফার্মেশন লাগবে।")
        } else {
            speak("বস, আপনার ফোনে WhatsApp ইনস্টল করা নেই।")
        }
    }

    private fun sendPromptToGemini(promptText: String) {
        _status.value = AssistantStatus.OPENING_APP
        addLog("Gemini AI", "Gemini অ্যাপ ওপেন ও প্রশ্ন প্রস্তুত করা হচ্ছে")
        val geminiIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")
        if (geminiIntent != null) {
            geminiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(geminiIntent)
            speak("বস, এখন Gemini খুলছি। আপনার প্রশ্নটি সেখানে লিখে দিচ্ছি।")
        } else {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            speak("বস, ব্রাউজারে Gemini খুলছি।")
        }
    }

    private fun openPlayStoreSearch(appName: String) {
        _status.value = AssistantStatus.SEARCHING
        addLog("Play Store", "Searching: $appName")
        speak("ঠিক আছে বস, এখন Google Play Store খুলে $appName খুঁজছি। ইনস্টল করার আগে আপনার কনফার্মেশন নেব।")
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://search?q=${Uri.encode(appName)}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=${Uri.encode(appName)}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    private fun openContactCreatorPreview(command: String) {
        _status.value = AssistantStatus.WAITING_APPROVAL
        speak("বস, কন্টাক্ট সেভ করার জন্য প্রিভিউ স্ক্রিন খুলছি। আপনি দেখে কনফার্ম করলেই সেভ হবে।")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, "New Boss Contact")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openApp(packageName: String, appName: String) {
        _status.value = AssistantStatus.OPENING_APP
        addLog("Opening $appName", "Foreground-এ $appName ওপেন করা হচ্ছে...")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            speak("ঠিক আছে বস, আপনার চোখের সামনে $appName খুলছি।")
            context.startActivity(launchIntent)
        } else {
            speak("বস, আপনার ফোনে $appName খুঁজে পাচ্ছি না।")
        }
    }

    fun updateVoiceSettings(rate: Float, pitch: Float) {
        _speechRate.value = rate
        _speechPitch.value = pitch
        prefs.edit().putFloat("speech_rate", rate).putFloat("speech_pitch", pitch).apply()
        tts?.setSpeechRate(rate)
        tts?.setPitch(pitch)
        addLog("Voice Tuned", "Speed: ${"%.2f".format(rate)}x | Pitch: ${"%.2f".format(pitch)}x")
    }

    fun toggleLanguage() {
        _languageLocale.value = if (_languageLocale.value == "bn-BD") "en-US" else "bn-BD"
        addLog("Language Mode", "Listening Locale: ${_languageLocale.value}")
    }

    private fun loadSavedMemories(): List<String> {
        val raw = prefs.getString("saved_memories", "বস একজন Video Editor|ডিফল্ট বিশ্রামের সময় ২ ঘণ্টা") ?: ""
        return raw.split("|").filter { it.isNotBlank() }
    }

    private fun saveMemoryItem(note: String) {
        val updated = (_savedMemories.value + note).takeLast(10)
        _savedMemories.value = updated
        prefs.edit().putString("saved_memories", updated.joinToString("|")).apply()
    }

    fun speak(text: String, utteranceId: String = "NEXU_${System.currentTimeMillis()}") {
        _nexuReplyText.value = text
        addLog("NEXU Voice", text)
        if (!isTtsReady) return
        mainHandler.post {
            speechRecognizer?.cancel()
            tts?.setSpeechRate(_speechRate.value)
            tts?.setPitch(_speechPitch.value)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun addLog(title: String, detail: String) {
        val updated = listOf(ActivityLogItem(getCurrentTime(), title, detail)) + _activityLogs.value
        _activityLogs.value = updated.take(20)
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun shutdown() {
        try {
            context.unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
