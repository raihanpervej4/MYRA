package com.myra.assistant.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.ui.main.OrbAnimationView
import com.myra.assistant.util.ensureChannel
import com.myra.assistant.util.startFg
import kotlin.math.abs

class MyraOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "myra_overlay_channel"
        const val ACTION_SHOW_OVERLAY = "com.myra.SHOW_OVERLAY"
        @Volatile var isRunning = false
        private const val NOTIF_ID = 1001
    }

    private var wm: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel(CHANNEL_ID, "MYRA overlay", NotificationManager.IMPORTANCE_LOW)
        startFg(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_myra_notif)
                .setContentTitle("MYRA")
                .setContentText("Overlay active")
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_OVERLAY) showOverlay()
        else if (overlayView == null) stopSelf()
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_orb, null)
        val density = resources.displayMetrics.density
        val size = (160 * density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - size) / 2
            y = (resources.displayMetrics.heightPixels - size) / 2
        }

        v.findViewById<OrbAnimationView>(R.id.overlayOrb).setState(OrbAnimationView.State.ACTIVE)
        v.findViewById<ImageButton>(R.id.overlayClose).setOnClickListener { removeOverlay(); stopSelf() }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        removeOverlay(); stopSelf()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm?.addView(v, lp)
            overlayView = v
        } catch (e: Exception) {
            e.printStackTrace(); stopSelf()
        }
    }

    private fun removeOverlay() {
        overlayView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    override fun onDestroy() {
        removeOverlay()
        isRunning = false
        super.onDestroy()
    }
}
