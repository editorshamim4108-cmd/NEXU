package com.boss.nexu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * এই সার্ভিসটি অ্যাপ বন্ধ থাকলেও বা স্ক্রিন লক/অফ থাকলেও ব্যাকগ্রাউন্ডে
 * সব সময় "Hey Boss" শোনার জন্য সক্রিয় থাকে।
 * আপনি "Hey Boss" বলার সাথে সাথে এটি নিজে থেকেই NEXU স্ক্রিন ওপেন করে সাড়া দেয়।
 */
class NexuVoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nexu_persistent_session_channel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
    }

    private var wakeRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        acquirePartialWakeLock()
        initBackgroundWakeWordListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXU Always-On — 'Hey Boss' বললেই হাজির হব")
            .setContentText("অ্যাপ খোলার প্রয়োজন নেই বস। যেকোনো সময় 'Hey Boss' বলে ডাকুন।")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startWakeListeningLoop()
        return START_STICKY
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NEXU::AlwaysOnWakeWordLock"
            ).apply {
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun initBackgroundWakeWordListener() {
        mainHandler.post {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                wakeRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (isRunning) {
                            mainHandler.postDelayed({ startWakeListeningLoop() }, 1200)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val heard = matches?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
                        if (heard.contains("hey boss") || heard.contains("হেই বস") || heard.contains("বস")) {
                            launchNexuFloatingInterface(heard)
                        } else if (isRunning) {
                            mainHandler.postDelayed({ startWakeListeningLoop() }, 400)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.lowercase(Locale.ROOT) ?: ""
                        if (partial.contains("hey boss") || partial.contains("হেই বস")) {
                            launchNexuFloatingInterface(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun startWakeListeningLoop() {
        if (!isRunning) return
        mainHandler.post {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                wakeRecognizer?.startListening(intent)
            } catch (_: Exception) {}
        }
    }

    private fun launchNexuFloatingInterface(triggerPhrase: String) {
        val popupIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra("WAKE_WORD_TRIGGERED", true)
            putExtra("INITIAL_COMMAND", triggerPhrase)
        }
        startActivity(popupIntent)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        mainHandler.post { wakeRecognizer?.destroy() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEXU Hands-Free Always-On Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "অ্যাপ না খুলেই 'Hey Boss' ডাক শোনামাত্র NEXU চালু করার ব্যাকগ্রাউন্ড সার্ভিস।"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
