package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Double power press (two SCREEN_OFF/ON events within 600ms) -> show overlay. Registered dynamically by CallMonitorService. */
class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private var lastTime = 0L
        private var count = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_SCREEN_OFF && a != Intent.ACTION_SCREEN_ON) return
        val now = System.currentTimeMillis()
        count = if (now - lastTime <= 600) count + 1 else 1
        lastTime = now
        if (count >= 2) {
            count = 0
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MyraOverlayService::class.java).setAction(MyraOverlayService.ACTION_SHOW_OVERLAY)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
