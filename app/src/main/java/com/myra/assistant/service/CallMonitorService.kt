package com.myra.assistant.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.util.ensureChannel
import com.myra.assistant.util.startFg

@Suppress("DEPRECATION")
class CallMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "myra_call_channel"
        const val ALERT_CHANNEL_ID = "myra_call_alert_channel"
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        const val EXTRA_INCOMING = "INCOMING_CALL"
        const val EXTRA_CALLER = "CALLER_NAME"
        private const val NOTIF_ID = 1002
    }

    private var tm: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private val powerReceiver = PowerButtonReceiver()
    private var powerRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(CHANNEL_ID, "MYRA call monitor", NotificationManager.IMPORTANCE_LOW)
        ensureChannel(ALERT_CHANNEL_ID, "MYRA incoming call", NotificationManager.IMPORTANCE_HIGH)
        startFg(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_myra_notif)
                .setContentTitle("MYRA standby")
                .setContentText("Call monitor active")
                .setOngoing(true)
                .build()
        )
        if (!powerRegistered) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(this, powerReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            powerRegistered = true
        }
        registerPhoneListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerPhoneListener() // retry after permissions granted
        return START_STICKY
    }

    private fun registerPhoneListener() {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return
        tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleState(state, phoneNumber)
            }
        }
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    tm?.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                    listener = l
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleState(state: Int, number: String?) {
        if (state == lastState) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val name = resolveCallerName(number)
                launchMain(name)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                sendBroadcast(Intent(ACTION_CALL_ENDED).setPackage(packageName))
            }
            else -> {} // OFFHOOK: nothing
        }
        lastState = state
    }

    private fun launchMain(callerName: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_INCOMING, true)
            putExtra(EXTRA_CALLER, callerName)
        }
        try { startActivity(i) } catch (e: Exception) { e.printStackTrace() }

        // fallback: full-screen notification
        val pi = PendingIntent.getActivity(
            this, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1003, n)
        } catch (_: Exception) {}
    }

    private fun resolveCallerName(number: String?): String {
        if (number.isNullOrBlank()) return "Unknown number"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return number
        val digits = number.filter { it.isDigit() }
        val tail = if (digits.length > 7) digits.takeLast(10) else digits
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val d = c.getString(1)?.filter { ch -> ch.isDigit() } ?: continue
                    if (tail.length >= 7 && d.endsWith(tail.takeLast(7))) return c.getString(0)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return number
    }

    override fun onDestroy() {
        try { listener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) } } catch (_: Exception) {}
        listener = null
        if (powerRegistered) {
            try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
            powerRegistered = false
        }
        super.onDestroy()
    }
}
