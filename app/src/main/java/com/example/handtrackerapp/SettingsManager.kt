package com.example.handtrackerapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ControlSettings(
    val cursorSpeed: Float = 2.8f,      // ความเร็วพื้นฐาน (1.0f - 5.0f)
    val pointerRadius: Float = 22f,     // ขนาดจุด Pointer (12f - 40f)
    val pinchThreshold: Float = 0.25f,  // ความไวการจีบนิ้วคลิก (0.18f - 0.35f)
    val hapticEnabled: Boolean = true   // เปิด/ปิดการสั่นเตือน
)

object SettingsManager {
    private const val PREFS_NAME = "air_hand_prefs"
    private val _settings = MutableStateFlow(ControlSettings())
    val settings = _settings.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _settings.value = ControlSettings(
            cursorSpeed = prefs.getFloat("cursorSpeed", 2.8f),
            pointerRadius = prefs.getFloat("pointerRadius", 22f),
            pinchThreshold = prefs.getFloat("pinchThreshold", 0.25f),
            hapticEnabled = prefs.getBoolean("hapticEnabled", true)
        )
    }

    fun update(context: Context, newSettings: ControlSettings) {
        _settings.value = newSettings
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putFloat("cursorSpeed", newSettings.cursorSpeed)
            putFloat("pointerRadius", newSettings.pointerRadius)
            putFloat("pinchThreshold", newSettings.pinchThreshold)
            putBoolean("hapticEnabled", newSettings.hapticEnabled)
            apply()
        }
    }
}