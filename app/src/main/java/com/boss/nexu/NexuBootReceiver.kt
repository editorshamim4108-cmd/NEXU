package com.boss.nexu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * ফোন অন বা রিস্টার্ট হওয়ার সাথে সাথে স্বয়ংক্রিয়ভাবে NEXU Wake-Word Service চালু করে,
 * যাতে বসের কখনোই ম্যানুয়ালি অ্যাপ ওপেন করতে না হয়।
 */
class NexuBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("nexu_boss_memory", Context.MODE_PRIVATE)
            val alwaysOnEnabled = prefs.getBoolean("always_on_wake_word", true)
            if (alwaysOnEnabled) {
                val serviceIntent = Intent(context, NexuVoiceForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
