package com.allcode.connect.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.allcode.connect.MainActivity
import com.allcode.connect.R
import com.allcode.connect.data.Commands
import com.allcode.connect.graph
import kotlinx.coroutines.launch

object Notifications {
    const val ATTENTION = "attention"
    const val FINISHED = "finished"
    const val LIVE = "live"
    const val LIVE_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_attention_description)
                },
                NotificationChannel(FINISHED, context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_finished_description)
                },
                NotificationChannel(LIVE, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.channel_live_description)
                    setShowBadge(false)
                },
            ),
        )
    }

    fun openThread(context: Context, environmentId: String, threadId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("allcode://thread?env=${Uri.encode(environmentId)}&id=${Uri.encode(threadId)}"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, (environmentId + threadId).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun notificationId(environmentId: String, threadId: String) = (environmentId + threadId).hashCode()

    fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun approvalAction(context: Context, environmentId: String, threadId: String, requestId: String, decision: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .putExtra("env", environmentId).putExtra("thread", threadId).putExtra("request", requestId).putExtra("decision", decision)
        val pending = PendingIntent.getBroadcast(
            context,
            (requestId + decision).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }
}

/** Answers an approval straight from the notification shade. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val env = intent.getStringExtra("env") ?: return
        val thread = intent.getStringExtra("thread") ?: return
        val request = intent.getStringExtra("request") ?: return
        val decision = intent.getStringExtra("decision") ?: return
        val pending = goAsync()
        val graph = context.graph
        graph.scope.launch {
            try {
                graph.connections.connection(env)?.dispatch(Commands.respondApproval(thread, request, decision))
                Notifications.cancel(context, Notifications.notificationId(env, thread))
            } catch (_: Exception) {
                Notifications.post(
                    context,
                    Notifications.notificationId(env, thread),
                    NotificationCompat.Builder(context, Notifications.ATTENTION)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Couldn't send your answer")
                        .setContentText("Open the thread to try again.")
                        .setContentIntent(Notifications.openThread(context, env, thread))
                        .setAutoCancel(true),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
