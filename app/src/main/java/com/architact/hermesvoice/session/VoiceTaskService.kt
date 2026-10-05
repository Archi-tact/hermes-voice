package com.architact.hermesvoice.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.architact.hermesvoice.MainActivity

/**
 * Keeps the app process alive (with a visible notification) while Hermes works on a request or
 * the reply is being read, so a multi-minute task survives the screen turning off.
 */
class VoiceTaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "작업 중입니다"
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "헤르메스 작업", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("헤르메스")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "hermes-voice-task"
        private const val NOTIFICATION_ID = 7
        private const val EXTRA_TEXT = "text"

        fun update(context: Context, text: String) {
            try {
                context.startForegroundService(Intent(context, VoiceTaskService::class.java).putExtra(EXTRA_TEXT, text))
            } catch (e: Exception) {
                // Starting from the background is not allowed; the request still runs while the process lives.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceTaskService::class.java))
        }
    }
}
