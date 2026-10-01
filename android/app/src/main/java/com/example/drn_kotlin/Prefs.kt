package com.example.drn_kotlin

import android.content.Context

data class StreamSettings(
    val width: Int = 640,
    val height: Int = 480,
    val fps: Int = 5,
    val quality: Int = 20,
    val zoom: Float = 1.0f
)

/**
 * Збережені налаштування стріму, щоб вони переживали перезапуск сервісу/застосунку.
 */
object Prefs {
    private const val NAME = "drn_settings"
    private const val K_W = "width"
    private const val K_H = "height"
    private const val K_FPS = "fps"
    private const val K_Q = "quality"
    private const val K_Z = "zoom"

    fun load(ctx: Context): StreamSettings {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return StreamSettings(
            width = p.getInt(K_W, 640),
            height = p.getInt(K_H, 480),
            fps = p.getInt(K_FPS, 5),
            quality = p.getInt(K_Q, 20),
            zoom = p.getFloat(K_Z, 1.0f)
        )
    }

    fun saveResolution(ctx: Context, width: Int, height: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(K_W, width).putInt(K_H, height).apply()
    }

    fun saveFps(ctx: Context, fps: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(K_FPS, fps).apply()
    }

    fun saveQuality(ctx: Context, quality: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(K_Q, quality).apply()
    }

    fun saveZoom(ctx: Context, zoom: Float) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putFloat(K_Z, zoom).apply()
    }
}
