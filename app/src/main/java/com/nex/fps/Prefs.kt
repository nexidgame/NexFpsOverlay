package com.nex.fps

import android.content.Context
import android.content.SharedPreferences

/** Semua pengaturan overlay dalam satu objek. */
data class OverlayConfig(
    val showFps: Boolean = true,
    val showCpu: Boolean = true,
    val showGpu: Boolean = true,
    val showRam: Boolean = true,
    val position: Int = POS_LEFT,
    val freeX: Float = 0.5f,    // posisi bebas: 0..1 dari ruang gerak horizontal
    val freeY: Float = 0.05f,   // posisi bebas: 0..1 dari ruang gerak vertikal
    val style: Int = STYLE_GAUGE,
    val apps: Set<String> = emptySet(),
    val record: Boolean = true   // mode rekam riwayat sesi
) {
    val anyMetric: Boolean get() = showFps || showCpu || showGpu || showRam

    companion object {
        const val POS_LEFT = 0      // kiri atas
        const val POS_CENTER = 1    // tengah atas
        const val POS_RIGHT = 2     // kanan atas
        const val POS_FREE = 3      // bebas digeser sendiri

        const val STYLE_TEXT = 0    // teks saja
        const val STYLE_GAUGE = 1   // seperti gambar (lingkaran)
    }
}

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("nex_fps_prefs", Context.MODE_PRIVATE)

    fun load(): OverlayConfig = OverlayConfig(
        showFps = sp.getBoolean(K_FPS, true),
        showCpu = sp.getBoolean(K_CPU, true),
        showGpu = sp.getBoolean(K_GPU, true),
        showRam = sp.getBoolean(K_RAM, true),
        position = sp.getInt(K_POS, OverlayConfig.POS_LEFT).coerceIn(0, 3),
        freeX = sp.getFloat(K_FX, 0.5f).coerceIn(0f, 1f),
        freeY = sp.getFloat(K_FY, 0.05f).coerceIn(0f, 1f),
        style = sp.getInt(K_STYLE, OverlayConfig.STYLE_GAUGE).coerceIn(0, 1),
        apps = HashSet(sp.getStringSet(K_APPS, emptySet()) ?: emptySet()),
        record = sp.getBoolean(K_REC, true)
    )

    fun save(c: OverlayConfig) {
        sp.edit()
            .putBoolean(K_FPS, c.showFps)
            .putBoolean(K_CPU, c.showCpu)
            .putBoolean(K_GPU, c.showGpu)
            .putBoolean(K_RAM, c.showRam)
            .putInt(K_POS, c.position)
            .putFloat(K_FX, c.freeX)
            .putFloat(K_FY, c.freeY)
            .putInt(K_STYLE, c.style)
            .putStringSet(K_APPS, HashSet(c.apps))
            .putBoolean(K_REC, c.record)
            .apply()
    }

    /** Dipanggil editor posisi: simpan titik bebas dan langsung pilih mode Bebas. */
    fun setFree(x: Float, y: Float) {
        sp.edit()
            .putInt(K_POS, OverlayConfig.POS_FREE)
            .putFloat(K_FX, x.coerceIn(0f, 1f))
            .putFloat(K_FY, y.coerceIn(0f, 1f))
            .apply()
    }

    private companion object {
        const val K_FPS = "show_fps"
        const val K_CPU = "show_cpu"
        const val K_GPU = "show_gpu"
        const val K_RAM = "show_ram"
        const val K_POS = "position"
        const val K_FX = "free_x"
        const val K_FY = "free_y"
        const val K_STYLE = "style"
        const val K_APPS = "apps"
        const val K_REC = "record"
    }
}
