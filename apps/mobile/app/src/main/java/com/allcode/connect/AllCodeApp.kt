package com.allcode.connect

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.allcode.connect.data.ConnectionManager
import com.allcode.connect.data.EnvironmentStore
import com.allcode.connect.data.PreferencesStore
import com.allcode.connect.notify.ActivityWatcher
import com.allcode.connect.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** App-wide services. One instance, created with the process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = PreferencesStore(app)
    val connections = ConnectionManager(app, EnvironmentStore(app), prefs, scope)
    val foreground = MutableStateFlow(false)
    /** The thread on screen, so its own activity doesn't notify. */
    val openThread = MutableStateFlow<Pair<String, String>?>(null)
    val watcher = ActivityWatcher(app, this)
}

class AllCodeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.watcher.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.foreground.value = true
                graph.connections.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                graph.foreground.value = false
                graph.connections.setForeground(false)
            }
        })
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as AllCodeApp).graph
