package com.nex.fps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.view.WindowManager

/**
 * Mengambil FPS, CPU, GPU, dan RAM. Satu siklus (tick) = satu perintah shell lewat Shizuku
 * yang sekaligus mengecek aplikasi di layar, sehingga ringan. Panggil dari thread latar.
 */
class Sampler(private val ctx: Context) {

    /** pkg = aplikasi yang sedang dipantau; frames = selisih waktu antar frame baru (ns) sejak tick lalu. */
    class Tick(
        val ok: Boolean,
        val active: Boolean,
        val data: Snapshot,
        val pkg: String? = null,
        val frames: LongArray = LongArray(0)
    )

    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val gpuScript: String = try {
        ctx.assets.open("probe_gpu.sh").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    private var activePkg: String? = null
    private var layer: String? = null
    private var layerMisses = 0
    private var lastDiscovery = 0L
    private var nullForeground = 0
    private var prevCpu: Parsers.CpuTimes? = null

    private var lastFrameNs = 0L
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val markers = setOf("#FG", "#STAT", "#FREQ", "#GPU", "#FPS", "#PWR", "#TEMP")

    private fun reset() {
        activePkg = null
        layer = null
        layerMisses = 0
        prevCpu = null
        nullForeground = 0
        lastFrameNs = 0L
    }

    fun tick(cfg0: OverlayConfig): Tick {
        // Mode rekam butuh data frame walau FPS tidak ditampilkan di overlay.
        val cfg = if (cfg0.record && !cfg0.showFps) cfg0.copy(showFps = true) else cfg0
        if (!ShizukuHelper.isConnected) {
            ShizukuHelper.bindIfPossible()
            reset()
            return Tick(false, false, Snapshot())
        }

        val pkg = activePkg
        val cmd = StringBuilder()
        cmd.append("echo '#FG'\n")
        cmd.append("dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|ResumedActivity' | head -n 4\n")
        if (pkg != null) {
            if (cfg.showCpu) {
                cmd.append("echo '#STAT'\nhead -n 1 /proc/stat\n")
                cmd.append("echo '#FREQ'\ncat /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq 2>/dev/null\n")
            }
            if (cfg.showGpu && gpuScript.isNotEmpty()) cmd.append(gpuScript).append('\n')
            if (cfg.record) {
                cmd.append("echo '#PWR'\n")
                cmd.append("echo \"I:$(cat /sys/class/power_supply/battery/current_now 2>/dev/null)\"\n")
                cmd.append("echo \"V:$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null)\"\n")
                cmd.append("echo \"T:$(cat /sys/class/power_supply/battery/temp 2>/dev/null)\"\n")
                cmd.append("echo '#TEMP'\ncat /sys/class/thermal/thermal_zone*/temp 2>/dev/null\n")
            }
            val l = layer
            if (cfg.showFps && l != null) {
                cmd.append("echo '#FPS'\ndumpsys SurfaceFlinger --latency ").append(quote(l)).append(" 2>/dev/null\n")
            }
        }

        val out = ShizukuHelper.exec(cmd.toString())
        if (out == null) {
            reset()
            return Tick(false, false, Snapshot())
        }
        val now = System.nanoTime()
        val sec = splitSections(out)

        var fg = Parsers.parseForeground(sec["FG"] ?: emptyList())
        if (fg == null && pkg != null && nullForeground < 3) {
            // dumpsys sesekali kosong; anggap aplikasi masih sama
            nullForeground++
            fg = pkg
        } else if (fg != null) {
            nullForeground = 0
        }

        if (fg == null || fg == ctx.packageName || !cfg.apps.contains(fg)) {
            reset()
            return Tick(true, false, Snapshot())
        }

        val (ramPct, ramMb) = readRam()
        val resP = resolutionP()

        if (fg != pkg) {
            // Aplikasi terpilih baru muncul di layar: mulai dari awal.
            reset()
            activePkg = fg
            if (cfg.showFps) discoverLayer(fg)
            return Tick(true, true, Snapshot(ramPct = ramPct, ramUsedMb = ramMb, resP = resP), fg)
        }

        var cpuPct = -1
        var cpuMhz = -1
        if (cfg.showCpu) {
            val cur = sec["STAT"]?.firstOrNull()?.let { Parsers.parseCpuStat(it) }
            cpuPct = Parsers.cpuPercent(prevCpu, cur)
            if (cur != null) prevCpu = cur
            cpuMhz = Parsers.avgFreqMhz(sec["FREQ"] ?: emptyList())
        }

        var gpu = Parsers.Gpu(-1, -1)
        if (cfg.showGpu) gpu = Parsers.parseGpu(sec["GPU"] ?: emptyList())

        var fps = -1
        var deltas = LongArray(0)
        if (cfg.showFps) {
            val text = sec["FPS"]?.joinToString("\n")
            val lat = if (text != null) Parsers.parseLatency(text, now) else null
            if (text != null) deltas = collectDeltas(Parsers.frameTimes(text))
            if (lat != null && lat.lastNs > 0L) {
                fps = lat.fps
                layerMisses = 0
            } else {
                layerMisses++
            }
            val stale = SystemClock.elapsedRealtime() - lastDiscovery > 8000L
            if (layer == null || layerMisses >= 3 || (fps <= 0 && stale)) discoverLayer(fg)
        }

        var tempC = -1f
        var powerW = -1f
        if (cfg.record) {
            val (t, p) = readThermalPower(sec["PWR"] ?: emptyList(), sec["TEMP"] ?: emptyList())
            tempC = t
            powerW = p
        }

        return Tick(
            true, true,
            Snapshot(
                fps = fps, cpuPct = cpuPct, cpuMhz = cpuMhz,
                gpuPct = gpu.pct, gpuMhz = gpu.mhz,
                ramPct = ramPct, ramUsedMb = ramMb, resP = resP,
                tempC = tempC, powerW = powerW
            ),
            fg, deltas
        )
    }

    /** Mencari layer SurfaceFlinger milik aplikasi, lalu memilih yang paling aktif menggambar. */
    private fun discoverLayer(pkg: String) {
        lastDiscovery = SystemClock.elapsedRealtime()
        val safe = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        val cmd = "dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$safe' " +
            "| grep -v -E 'Background for|Bounds for|Splash Screen|Letterbox|ActivityRecordInputSink' " +
            "| head -n 8 | while IFS= read -r L; do echo \"@@L:\$L\"; " +
            "dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null; done"
        val out = ShizukuHelper.exec(cmd) ?: return
        val best = Parsers.pickBestLayer(out, System.nanoTime())
        if (best != null) {
            layer = best
            layerMisses = 0
        }
    }

    /** Selisih antar frame baru (ns). Selisih di luar 1 ms..1 detik dibuang (duplikat / jeda layar). */
    private fun collectDeltas(ts: List<Long>): LongArray {
        if (ts.isEmpty()) return LongArray(0)
        val out = ArrayList<Long>()
        var prev = lastFrameNs
        for (t in ts) {
            if (t <= prev) continue
            if (prev > 0L) {
                val d = t - prev
                if (d in 1_000_000L..1_000_000_000L) out.add(d)
            }
            prev = t
        }
        if (prev > lastFrameNs) lastFrameNs = prev
        return out.toLongArray()
    }

    /** Suhu tertinggi (zona termal + baterai) dan daya perangkat. Cadangan: BatteryManager tanpa Shizuku. */
    private fun readThermalPower(pwr: List<String>, zones: List<String>): Pair<Float, Float> {
        var cur = 0L
        var volt = 0L
        var batt = -1f
        for (l in pwr) {
            if (l.length < 3 || l[1] != ':') continue
            val v = l.substring(2).trim().toLongOrNull() ?: continue
            when (l[0]) {
                'I' -> cur = v
                'V' -> volt = v
                'T' -> batt = Parsers.batteryTempC(v)
            }
        }
        var temp = maxOf(Parsers.maxTempC(zones), batt)
        var power = Parsers.powerW(cur, volt)
        if (temp < 0f || power < 0f) {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (temp < 0f && i != null) {
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (t > 0) temp = t / 10f
            }
            if (power < 0f && i != null) {
                val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).toLong()
                val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                power = Parsers.powerW(ua, mv)
            }
        }
        return Pair(temp, power)
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun splitSections(out: String): Map<String, List<String>> {
        val map = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (raw in out.lineSequence()) {
            val line = raw.trimEnd()
            if (line in markers) {
                cur = map.getOrPut(line.substring(1)) { ArrayList() }
                continue
            }
            cur?.add(line)
        }
        return map
    }

    private fun readRam(): Pair<Int, Int> {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        if (total <= 0L) return Pair(-1, -1)
        val used = total - mi.availMem
        return Pair((used * 100L / total).toInt().coerceIn(0, 100), (used / 1_048_576L).toInt())
    }

    private fun resolutionP(): Int {
        val b = wm.currentWindowMetrics.bounds
        return minOf(b.width(), b.height())
    }
}
