package com.nex.fps

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Layanan yang dijalankan Shizuku di proses terpisah dengan hak akses "shell".
 * Dipakai untuk membaca /proc, /sys, dan dumpsys yang tidak bisa dibaca aplikasi biasa.
 */
@Keep
class ShellService : IUserService.Stub {

    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            // Pengaman: matikan proses bila tak selesai dalam 6 detik.
            val watchdog = Thread {
                try {
                    if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (e: InterruptedException) {
                    // dihentikan normal setelah proses selesai
                }
            }
            watchdog.isDaemon = true
            watchdog.start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            watchdog.interrupt()
            text
        } catch (t: Throwable) {
            ""
        }
    }
}
