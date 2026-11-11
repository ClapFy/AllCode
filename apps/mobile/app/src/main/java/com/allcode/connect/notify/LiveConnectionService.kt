package com.allcode.connect.notify

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.allcode.connect.MainActivity
import com.allcode.connect.R
import com.allcode.connect.data.ThreadPhase
import com.allcode.connect.graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds machine connections open while agents work and the app is in the background,
 * so approvals and finished turns still notify. It ends itself once everything is idle.
 */
class LiveConnectionService : LifecycleService() {
    private var watching: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.LIVE_ID, build("Watching your agents", null), type)
        val graph = graph
        graph.connections.setHeldByService(true)
        if (watching?.isActive == true) return START_NOT_STICKY
        watching = lifecycleScope.launch {
            graph.connections.threads.collectLatest { threads ->
                val working = threads.count { it.thread.phase == ThreadPhase.Working && !it.thread.isSubagent }
                val waiting = threads.count { it.thread.phase == ThreadPhase.NeedsYou && !it.thread.isSubagent }
                if (working == 0 && waiting == 0) {
                    // Linger briefly so a turn that queues its follow-up doesn't bounce the service.
                    delay(90_000)
                    stopSelf()
                    return@collectLatest
                }
                val text = listOfNotNull(
                    working.takeIf { it > 0 }?.let { "$it working" },
                    waiting.takeIf { it > 0 }?.let { "$it waiting on you" },
                ).joinToString(" · ")
                Notifications.post(this@LiveConnectionService, Notifications.LIVE_ID, builder("Agents at work", text))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        graph.connections.setHeldByService(false)
        super.onDestroy()
    }

    private fun builder(title: String, text: String?) = NotificationCompat.Builder(this, Notifications.LIVE)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFFD97757.toInt())
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    private fun build(title: String, text: String?): Notification = builder(title, text).build()
}
