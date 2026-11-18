package com.myra.assistant.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build

fun Service.startFg(id: Int, notification: Notification) {
    try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Context.ensureChannel(id: String, name: String, importance: Int) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
