package cz.suku.rokidglass

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import cz.suku.rokidglass.assistant.RokidController

class RokidConnectionService : Service() {
    inner class LocalBinder : Binder() {
        val service: RokidConnectionService
            get() = this@RokidConnectionService
    }

    private val binder = LocalBinder()
    lateinit var controller: RokidController
        private set

    override fun onCreate() {
        super.onCreate()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.background_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PhoneActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.background_notification_title))
            .setContentText(getString(R.string.background_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        controller = RokidController(applicationContext)
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "rokid_connection"
        const val NOTIFICATION_ID = 1001
    }
}
