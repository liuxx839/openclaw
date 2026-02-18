package com.aihealth.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ReminderService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("health_bg", "后台服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, "health_bg")
            .setContentTitle("AI Health").setContentText("健康助手运行中")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).setSilent(true).build()
        startForeground(4001, n)
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIHealth::BG").apply { acquire(4 * 60 * 60 * 1000L); wakeLock = this }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
