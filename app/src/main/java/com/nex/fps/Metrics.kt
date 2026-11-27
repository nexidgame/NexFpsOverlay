package com.nex.fps

/** Satu pembacaan metrik. Nilai -1 berarti "tidak tersedia". */
data class Snapshot(
    val fps: Int = -1,
    val cpuPct: Int = -1,
    val cpuMhz: Int = -1,
    val gpuPct: Int = -1,
    val gpuMhz: Int = -1,
    val ramPct: Int = -1,
    val ramUsedMb: Int = -1,
    val resP: Int = 0,
    val tempC: Float = -1f,     // suhu tertinggi (baterai / zona termal), derajat Celsius
    val powerW: Float = -1f     // daya perangkat dari arus x tegangan baterai, Watt
)

/** Data contoh untuk pratinjau di aplikasi dan editor posisi. */
val SAMPLE_SNAPSHOT = Snapshot(
    fps = 60, cpuPct = 16, cpuMhz = 902, gpuPct = 57, gpuMhz = 612,
    ramPct = 75, ramUsedMb = 2810, resP = 1080
)

/** Fungsi pengurai murni (tanpa Android) untuk keluaran perintah shell. */
object Parsers {

    class CpuTimes(val idle: Long, val total: Long)
    class Gpu(val pct: Int, val mhz: Int)
    class Latency(val fps: Int, val lastNs: Long)

    private val ws = Regex("\\s+")
    private val digits = Regex("\\d+")
    private val fgRegex = Regex("""\bu\d+\s+([A-Za-z0-9_.]+)/""")

    // ---- CPU -------------------------------------------------------------

    /** Baris pertama /proc/stat: "cpu  user nice system idle iowait irq softirq steal ..." */
    fun parseCpuStat(line: String): CpuTimes? {
        val p = line.trim().split(ws)
        if (p.size < 5 || p[0] != "cpu") return null
        val n = p.drop(1).take(8).map { it.toLongOrNull() ?: 0L }
        if (n.size < 4) return null
        val idle = n[3] + (if (n.size > 4) n[4] else 0L)
        return CpuTimes(idle, n.sum())
    }

    fun cpuPercent(prev: CpuTimes?, cur: CpuTimes?): Int {
        if (prev == null || cur == null) return -1
        val dt = cur.total - prev.total
        val di = cur.idle - prev.idle
        if (dt <= 0L) return -1
        return Math.round((dt - di) * 100.0 / dt).toInt().coerceIn(0, 100)
    }

    /** Rata-rata scaling_cur_freq (kHz) semua inti yang aktif, dalam MHz. */
    fun avgFreqMhz(lines: List<String>): Int {
        val v = lines.mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L }
        if (v.isEmpty()) return -1
        return (v.average() / 1000.0).toInt()
    }

    // ---- GPU -------------------------------------------------------------

    /**
     * Baris berformat "G:<path>:<isi>" (beban) dan "F:<path>:<isi>" (frekuensi),
     * dihasilkan oleh assets/probe_gpu.sh. Dipakai nilai valid pertama.
     */
    fun parseGpu(lines: List<String>): Gpu {
        var pct = -1
        var mhz = -1
        for (l in lines) {
            if (l.length < 4 || l[1] != ':') continue
            val kind = l[0]
            val rest = l.substring(2)
            val sep = rest.indexOf(':')
            if (sep < 0) continue
            val path = rest.substring(0, sep)
            val nums = digits.findAll(rest.substring(sep + 1)).map { it.value.toLongOrNull() ?: 0L }.toList()
            if (nums.isEmpty()) continue
            if (kind == 'G' && pct < 0) {
                val v: Int = if (path.endsWith("gpubusy")) {
                    if (nums.size >= 2 && nums[1] > 0L) (nums[0] * 100L / nums[1]).toInt() else -1
                } else nums[0].toInt()
                if (v in 0..100) pct = v
            } else if (kind == 'F' && mhz < 0) {
                val raw = nums[0]
                val m = when {
                    raw >= 100_000_000L -> raw / 1_000_000L   // Hz -> MHz
                    raw >= 100_000L -> raw / 1_000L           // kHz -> MHz
                    else -> raw                               // sudah MHz
                }
                if (m in 50L..4000L) mhz = m.toInt()
            }
        }
        return Gpu(pct, mhz)
    }

    // ---- Suhu dan daya ------------------------------------------------------

    /** Suhu tertinggi dari daftar nilai thermal_zone (miliderajat atau derajat). -1 bila tidak ada. */
    fun maxTempC(lines: List<String>): Float {
        var best = -1f
        for (l in lines) {
            val raw = l.trim().toLongOrNull() ?: continue
            val c = if (raw >= 1000L) raw / 1000f else raw.toFloat()
            if (c in 15f..110f && c > best) best = c
        }
        return best
    }

    /** Suhu baterai: biasanya persepuluh derajat (mis. 345 = 34,5 C). */
    fun batteryTempC(raw: Long): Float {
        val c = if (raw >= 1000L) raw / 1000f else if (raw >= 100L) raw / 10f else raw.toFloat()
        return if (c in 5f..110f) c else -1f
    }

    /** Daya (Watt) dari arus (uA atau mA) dan tegangan (uV atau mV). Tanda arus diabaikan. */
    fun powerW(currentRaw: Long, voltageRaw: Long): Float {
        val i = Math.abs(currentRaw)
        if (i == 0L || voltageRaw <= 0L) return -1f
        val amps = if (i >= 20_000L) i / 1_000_000.0 else i / 1000.0
        val volts = if (voltageRaw >= 100_000L) voltageRaw / 1_000_000.0 else voltageRaw / 1000.0
        val w = (amps * volts).toFloat()
        return if (w in 0.05f..80f) w else -1f
    }

    // ---- Aplikasi di layar -------------------------------------------------

    /** Dari keluaran dumpsys activity: "... topResumedActivity=ActivityRecord{abc u0 com.pkg/.Main t12}" */
    fun parseForeground(lines: List<String>): String? {
        val line = lines.firstOrNull { it.contains("topResumedActivity") && fgRegex.containsMatchIn(it) }
            ?: lines.firstOrNull { fgRegex.containsMatchIn(it) }
            ?: return null
        return fgRegex.find(line)?.groupValues?.get(1)
    }

    // ---- FPS (SurfaceFlinger --latency) -----------------------------------

    /** Semua stempel waktu tampil yang valid, terurut naik (ns). */
    fun frameTimes(text: String): List<Long> {
        val out = ArrayList<Long>()
        var first = true
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (first) { first = false; continue }   // baris pertama = periode refresh
            val p = line.split(ws)
            if (p.size < 3) continue
            val t = p[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            out.add(t)
        }
        out.sort()
        return out
    }

    /**
     * Format: baris pertama = periode refresh (ns); tiap baris berikutnya:
     * desiredPresentTime actualPresentTime frameReadyTime (ns). Nilai 0 atau Long.MAX = belum valid.
     * FPS dihitung dari frame yang tampil dalam 1 detik terakhir.
     */
    fun parseLatency(text: String, nowNs: Long): Latency? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return null
        val times = ArrayList<Long>()
        for (i in 1 until lines.size) {
            val p = lines[i].split(ws)
            if (p.size < 3) continue
            val t = p[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            times.add(t)
        }
        if (times.isEmpty()) return Latency(0, 0L)
        times.sort()
        val last = times[times.size - 1]
        if (times.size < 2) return Latency(0, last)
        val from = last - 1_000_000_000L
        var first = last
        var count = 0
        for (t in times) {
            if (t >= from) {
                if (count == 0) first = t
                count++
            }
        }
        var fps = if (count >= 2 && last > first) Math.round((count - 1) * 1e9 / (last - first)).toInt() else 0
        // Layer berhenti menggambar: stempel terakhir sudah lama (jam yang sama: CLOCK_MONOTONIC)
        val age = nowNs - last
        if (age in 1_500_000_000L..3_600_000_000_000L) fps = 0
        return Latency(fps.coerceIn(0, 1000), last)
    }

    /** Memilih layer terbaik dari keluaran skrip pencarian (bagian "@@L:<nama>" lalu isi --latency). */
    fun pickBestLayer(text: String, nowNs: Long): String? {
        var best: String? = null
        var bestScore = -1L
        var name: String? = null
        val body = StringBuilder()

        fun flush() {
            val n = name ?: return
            val lat = parseLatency(body.toString(), nowNs)
            if (lat != null && lat.lastNs > 0L) {
                val score = lat.fps * 1000L + (if (n.contains("SurfaceView")) 500L else 0L) + 1L
                if (score > bestScore) {
                    bestScore = score
                    best = n
                }
            }
            body.setLength(0)
        }

        for (line in text.lineSequence()) {
            if (line.startsWith("@@L:")) {
                flush()
                name = line.substring(4).trim()
            } else {
                body.append(line).append('\n')
            }
        }
        flush()
        return best
    }
}
