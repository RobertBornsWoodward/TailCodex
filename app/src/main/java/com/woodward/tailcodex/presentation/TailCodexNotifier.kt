package com.woodward.tailcodex.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.woodward.tailcodex.MainActivity
import com.woodward.tailcodex.R
import com.woodward.tailcodex.domain.ServerRequest
import java.util.UUID

data class NotificationCommand(
    val hostId: String,
    val requestId: String,
    val action: String,
)

class TailCodexNotifier(private val context: Context) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Codex 任务", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun showTask(id: Int, title: String, body: String) {
        show(id, title, body, contentIntent(id, ACTION_OPEN_APP))
    }

    fun showRequest(hostId: String, request: ServerRequest, title: String, body: String) {
        val requestId = request.requestId.toString()
        val id = "$hostId:$requestId".hashCode()
        val nonce = UUID.randomUUID().toString()
        preferences().edit { putString(nonceKey(hostId, requestId), nonce) }
        val open = requestIntent(id, hostId, requestId, ACTION_OPEN_REQUEST, nonce)
        val builder = builder(title, body, open)
        if (request is ServerRequest.CommandApproval ||
            request is ServerRequest.FileApproval ||
            request is ServerRequest.PermissionsApproval
        ) {
            builder.addAction(
                0,
                "允许一次",
                requestIntent(id + 1, hostId, requestId, ACTION_APPROVE, nonce),
            ).addAction(
                0,
                "拒绝",
                requestIntent(id + 2, hostId, requestId, ACTION_REJECT, nonce),
            )
        }
        notify(id, builder.build())
    }

    fun consumeCommand(intent: Intent?): NotificationCommand? {
        val action = intent?.action ?: return null
        if (action !in REQUEST_ACTIONS) return null
        val hostId = intent.getStringExtra(EXTRA_HOST_ID)?.takeIf(String::isNotBlank) ?: return null
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return null
        val nonce = intent.getStringExtra(EXTRA_NONCE) ?: return null
        val key = nonceKey(hostId, requestId)
        if (preferences().getString(key, null) != nonce) return null
        preferences().edit { remove(key) }
        return NotificationCommand(hostId, requestId, action)
    }

    private fun show(id: Int, title: String, body: String, pending: PendingIntent) {
        notify(id, builder(title, body, pending).build())
    }

    private fun builder(
        title: String,
        body: String,
        pending: PendingIntent,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setContentIntent(pending)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun notify(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun contentIntent(id: Int, action: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            this.action = action
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestIntent(
        requestCode: Int,
        hostId: String,
        requestId: String,
        action: String,
        nonce: String,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            this.action = action
            putExtra(EXTRA_HOST_ID, hostId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_NONCE, nonce)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun nonceKey(hostId: String, requestId: String) = "$hostId:$requestId"

    companion object {
        const val ACTION_OPEN_APP = "com.woodward.tailcodex.notification.OPEN_APP"
        const val ACTION_OPEN_REQUEST = "com.woodward.tailcodex.notification.OPEN_REQUEST"
        const val ACTION_APPROVE = "com.woodward.tailcodex.notification.APPROVE"
        const val ACTION_REJECT = "com.woodward.tailcodex.notification.REJECT"
        private const val EXTRA_HOST_ID = "notification_host_id"
        private const val EXTRA_REQUEST_ID = "notification_request_id"
        private const val EXTRA_NONCE = "notification_nonce"
        private const val CHANNEL = "codex_requests"
        private const val PREFERENCES = "notification_actions"
        private val REQUEST_ACTIONS = setOf(ACTION_OPEN_REQUEST, ACTION_APPROVE, ACTION_REJECT)
    }
}
