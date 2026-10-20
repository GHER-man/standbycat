package com.xiaomi8.standbycat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class PowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // 1) 尝试直接拉起时钟（App 在前台时有效）
                try {
                    context.startActivity(
                        Intent(context, ClockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
                // 2) 全屏通知兜底（后台/锁屏时点亮屏幕，点按即进入）
                showFullScreenNotification(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFY_ID)
            }
        }
    }

    private fun showFullScreenNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "充电待机时钟", NotificationManager.IMPORTANCE_HIGH
            )
            ch.description = "插上电源时点亮待机时钟"
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, ClockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }

        val n = builder
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("静夜时钟")
            .setContentText("正在充电，点击查看时钟 ⚡")
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()

        try {
            nm.notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "standby"
        private const val NOTIFY_ID = 1001
    }
}
