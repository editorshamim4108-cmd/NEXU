package com.boss.nexu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class JarvisFloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_floating_overlay_channel"
        const val NOTIFICATION_ID = 2002

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var rootLayout: LinearLayout? = null
    private var menuLayout: LinearLayout? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForegroundSafely()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        setupFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_FLOATING") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingOpen = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val stopIntent = Intent(this, JarvisFloatingService::class.java).apply {
            action = "STOP_FLOATING"
        }
        val pendingStop = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS Floating Mode চালু আছে")
            .setContentText("ভাসমান বাটনে ট্যাপ করে যেকোনো স্ক্রিন বাংলায় বা ইংরেজিতে শুনুন")
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ করুন", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun setupFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Glowing Orb Button
        val orbDrawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#00F0FF"),
                Color.parseColor("#0066FF"),
                Color.parseColor("#B026FF")
            )
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.parseColor("#E0FFFFFF"))
        }

        val orbBtn = TextView(this).apply {
            text = "🎙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            background = orbDrawable
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8).toFloat()
            }
        }

        // Expandable Cyber Action Pill
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#EE0A0C16"))
            setStroke(dp(1), Color.parseColor("#00F0FF"))
        }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }

        fun makeActionChip(label: String, textHex: String, onClick: () -> Unit): TextView {
            val chipBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#181C30"))
                setStroke(1, Color.parseColor(textHex))
            }
            return TextView(this).apply {
                text = label
                setTextColor(Color.parseColor(textHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                background = chipBg
                setPadding(dp(9), dp(5), dp(9), dp(5))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(6)
                layoutParams = lp
                setOnClickListener { onClick() }
            }
        }

        menu.addView(makeActionChip("বাংলায় পড়ো", "#00FF9D") {
            menu.visibility = View.GONE
            readLiveScreen("bn")
        })
        menu.addView(makeActionChip("English", "#00F0FF") {
            menu.visibility = View.GONE
            readLiveScreen("en")
        })
        menu.addView(makeActionChip("JARVIS", "#D98BFF") {
            menu.visibility = View.GONE
            val openApp = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("WAKE_WORD_TRIGGERED", true)
            }
            startActivity(openApp)
        })

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF6B81"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(4), dp(4), dp(4))
            setOnClickListener {
                stopAudio()
                stopSelf()
            }
        }
        menu.addView(closeBtn)

        container.addView(orbBtn)
        container.addView(menu)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        orbBtn.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager?.updateViewLayout(container, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        stopAudio()
                        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        rootLayout = container
        menuLayout = menu
        try {
            windowManager?.addView(container, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun readLiveScreen(targetLang: String) {
        val service = NexuVisualAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "Boss, স্ক্রিন পড়ার জন্য Accessibility সেটিংস থেকে JARVIS চালু করুন",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            return
        }

        val rawScreen = service.readVisibleScreenText().trim()
        if (rawScreen.isEmpty()) {
            Toast.makeText(
                this,
                "Boss, এই স্ক্রিনে পড়ার মতো কোনো লেখা পাওয়া যায়নি",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Toast.makeText(
            this,
            if (targetLang == "en") "JARVIS reading screen in English..." else "জার্ভিস স্ক্রিন বাংলায় পড়ছে...",
            Toast.LENGTH_SHORT
        ).show()

        ioExecutor.execute {
            val snippet = rawScreen.take(900)
            val translated = translateText(snippet, targetLang).ifBlank { snippet }
            val fullSpeech = if (targetLang == "en") {
                "Boss, on your screen it says: $translated"
            } else {
                "Boss, আপনার স্ক্রিনে লেখা আছে: $translated"
            }
            playSpeechText(fullSpeech, targetLang)
        }
    }

    private fun translateText(text: String, tl: String): String {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tl&dt=t&q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val outer = JSONArray(body)
                val segments = outer.optJSONArray(0)
                val sb = StringBuilder()
                if (segments != null) {
                    for (i in 0 until segments.length()) {
                        val seg = segments.optJSONArray(i)
                        val piece = seg?.optString(0).orEmpty()
                        sb.append(piece)
                    }
                }
                sb.toString().trim()
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun playSpeechText(text: String, lang: String) {
        val shortChunk = text.take(190)
        try {
            val encoded = URLEncoder.encode(shortChunk, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$lang&q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            }
            if (conn.responseCode == 200) {
                val mp3Bytes = conn.inputStream.use { it.readBytes() }
                if (mp3Bytes.size > 256) {
                    val tempFile = File(cacheDir, "jarvis_float_tts.mp3")
                    FileOutputStream(tempFile).use { it.write(mp3Bytes) }
                    mainHandler.post {
                        stopAudio()
                        try {
                            mediaPlayer = MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                setDataSource(tempFile.absolutePath)
                                prepare()
                                start()
                            }
                        } catch (_: Exception) {
                            speakFallbackTts(text, lang)
                        }
                    }
                    return
                }
            }
        } catch (_: Exception) {}

        mainHandler.post { speakFallbackTts(text, lang) }
    }

    private fun speakFallbackTts(text: String, lang: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        val locale = if (lang == "en") Locale.US else Locale("bn", "BD")
        val res = engine.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FLOAT_${System.currentTimeMillis()}")
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        stopAudio()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        rootLayout?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        rootLayout = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Floating Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "অন্য অ্যাপের ওপর ভাসমান জার্ভিস বাটন"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
