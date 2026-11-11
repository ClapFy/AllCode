package com.allcode.connect.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

data class AppPreferences(
    val theme: ThemeMode = ThemeMode.System,
    val notifyAttention: Boolean = true,
    val notifyFinished: Boolean = true,
    val stayConnected: Boolean = true,
    val haptics: Boolean = true,
    val defaultRuntimeMode: String = "auto-accept-edits",
    val enterSends: Boolean = false,
    val lastEnvironmentId: String? = null,
    val lastProjectId: String? = null,
)

/** Small, synchronous app preferences. Nothing secret lives here. */
class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("allcode", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPreferences> = _state.asStateFlow()

    private fun read() = AppPreferences(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "System") }.getOrDefault(ThemeMode.System),
        notifyAttention = prefs.getBoolean("notifyAttention", true),
        notifyFinished = prefs.getBoolean("notifyFinished", true),
        stayConnected = prefs.getBoolean("stayConnected", true),
        haptics = prefs.getBoolean("haptics", true),
        defaultRuntimeMode = prefs.getString("defaultRuntimeMode", null) ?: "auto-accept-edits",
        enterSends = prefs.getBoolean("enterSends", false),
        lastEnvironmentId = prefs.getString("lastEnvironmentId", null),
        lastProjectId = prefs.getString("lastProjectId", null),
    )

    fun update(transform: (AppPreferences) -> AppPreferences) {
        val next = transform(_state.value)
        prefs.edit()
            .putString("theme", next.theme.name)
            .putBoolean("notifyAttention", next.notifyAttention)
            .putBoolean("notifyFinished", next.notifyFinished)
            .putBoolean("stayConnected", next.stayConnected)
            .putBoolean("haptics", next.haptics)
            .putString("defaultRuntimeMode", next.defaultRuntimeMode)
            .putBoolean("enterSends", next.enterSends)
            .putString("lastEnvironmentId", next.lastEnvironmentId)
            .putString("lastProjectId", next.lastProjectId)
            .apply()
        _state.value = next
    }
}
