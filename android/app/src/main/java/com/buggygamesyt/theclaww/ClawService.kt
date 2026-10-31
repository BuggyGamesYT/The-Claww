package com.buggygamesyt.theclaww

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground service that keeps the app process (and its Ollama + claw child processes) running in the background. */
class ClawService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            MainActivity.ollama?.destroy(); MainActivity.ollama = null
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("claww", "The Claww running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ClawService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "claww")
            .setContentTitle("The Claww is running")
            .setContentText("Ollama stays ready in the background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop Ollama", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claww:ollama").apply { setReferenceCounted(false); acquire() }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MainActivity.ollama?.destroy(); MainActivity.ollama = null
        stopSelf()
    }

    override fun onDestroy() { wake?.release(); wake = null; super.onDestroy() }
}
