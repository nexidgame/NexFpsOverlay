package com.nex.fps

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Satu sesi pengujian. Seri (fps, frameMs, temp, power) berisi satu nilai per titik grafik
 * (sudah diringkas maksimal [SessionRecorder.MAX_POINTS] titik). Seri kosong = sensor tidak tersedia.
 * Nilai agregat -1 = tidak tersedia.
 */
class Session(
    val id: Long,
    val startMs: Long,
    val durationSec: Int,
    val pkg: String,
    val label: String,
    val avgFps: Float,
    val low5Fps: Float,
    val avgFrameMs: Float,
    val maxTemp: Float,
    val avgPower: Float,
    val fps: List<Float>,
    val frameMs: List<Float>,
    val temp: List<Float>,
    val power: List<Float>
)

/** Riwayat sesi (maksimal 5), disimpan di SharedPreferences sebagai JSON kecil. */
object SessionStore {
    const val MAX = 5
    private const val FILE = "nex_fps_history"
    private const val KEY = "sessions"

    private val _items = MutableStateFlow<List<Session>>(emptyList())
    val items: StateFlow<List<Session>> = _items

    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live

    private var sp: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _items.value = read(p.getString(KEY, null))
    }

    fun setLive(v: Boolean) {
        _live.value = v
    }

    @Synchronized
    fun add(s: Session) {
        val l = (listOf(s) + _items.value).take(MAX)
        _items.value = l
        write(l)
    }

    @Synchronized
    fun delete(id: Long) {
        val l = _items.value.filter { it.id != id }
        _items.value = l
        write(l)
    }

    @Synchronized
    fun clearAll() {
        _items.value = emptyList()
        write(emptyList())
    }

    private fun write(l: List<Session>) {
        val arr = JSONArray()
        for (s in l) arr.put(toJson(s))
        sp?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private fun read(text: String?): List<Session> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(text)
            val out = ArrayList<Session>()
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            out.take(MAX)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun series(a: List<Float>): JSONArray {
        val j = JSONArray()
        for (v in a) j.put(Math.round(v * 10f) / 10.0)
        return j
    }

    private fun readSeries(a: JSONArray?): List<Float> {
        if (a == null) return emptyList()
        val out = ArrayList<Float>(a.length())
        for (i in 0 until a.length()) out.add(a.optDouble(i, 0.0).toFloat())
        return out
    }

    private fun toJson(s: Session) = JSONObject()
        .put("id", s.id).put("start", s.startMs).put("dur", s.durationSec)
        .put("pkg", s.pkg).put("label", s.label)
        .put("avgFps", s.avgFps.toDouble()).put("low5", s.low5Fps.toDouble())
        .put("avgMs", s.avgFrameMs.toDouble()).put("maxT", s.maxTemp.toDouble())
        .put("avgP", s.avgPower.toDouble())
        .put("fps", series(s.fps)).put("ms", series(s.frameMs))
        .put("temp", series(s.temp)).put("pw", series(s.power))

    private fun fromJson(o: JSONObject) = Session(
        id = o.getLong("id"),
        startMs = o.getLong("start"),
        durationSec = o.getInt("dur"),
        pkg = o.optString("pkg"),
        label = o.optString("label"),
        avgFps = o.optDouble("avgFps", -1.0).toFloat(),
        low5Fps = o.optDouble("low5", -1.0).toFloat(),
        avgFrameMs = o.optDouble("avgMs", -1.0).toFloat(),
        maxTemp = o.optDouble("maxT", -1.0).toFloat(),
        avgPower = o.optDouble("avgP", -1.0).toFloat(),
        fps = readSeries(o.optJSONArray("fps")),
        frameMs = readSeries(o.optJSONArray("ms")),
        temp = readSeries(o.optJSONArray("temp")),
        power = readSeries(o.optJSONArray("pw"))
    )
}

/**
 * Merekam satu sesi selama aplikasi terpilih ada di layar. Sesi berakhir bila aplikasi ditutup
 * (beberapa detik tidak terdeteksi), berganti aplikasi, mode rekam dimatikan, atau overlay dihentikan.
 * Sesi di bawah [MIN_SEC] detik dibuang. Panggil dari satu thread (loop layanan).
 */
class SessionRecorder(private val ctx: Context) {

    companion object {
        const val MIN_SEC = 10
        const val MAX_POINTS = 90
        private const val IDLE_TICKS_TO_END = 4
        private const val BUCKETS = 2000          // histogram frametime: 0,1 ms per kotak (0..200 ms)
    }

    private var active = false
    private var pkg = ""
    private var startMs = 0L
    private var lastMs = 0L
    private var idle = 0

    private val fps = ArrayList<Float>()
    private val frameMs = ArrayList<Float>()
    private val temp = ArrayList<Float>()
    private val power = ArrayList<Float>()
    private val hist = IntArray(BUCKETS)
    private var frameCount = 0L
    private var frameSumNs = 0L

    @Synchronized
    fun onTick(cfg: OverlayConfig, t: Sampler.Tick) {
        if (!cfg.record) {
            if (active) finish()
            return
        }
        val p = t.pkg
        if (t.ok && t.active && p != null) {
            if (active && p != pkg) finish()
            if (!active) begin(p)
            idle = 0
            if (t.data.fps >= 0) add(t)
        } else if (active) {
            idle++
            if (idle >= IDLE_TICKS_TO_END) finish()
        }
    }

    private fun begin(p: String) {
        clear()
        active = true
        pkg = p
        startMs = System.currentTimeMillis()
        lastMs = startMs
        SessionStore.setLive(true)
    }

    private fun clear() {
        fps.clear(); frameMs.clear(); temp.clear(); power.clear()
        java.util.Arrays.fill(hist, 0)
        frameCount = 0L
        frameSumNs = 0L
        idle = 0
    }

    private fun add(t: Sampler.Tick) {
        lastMs = System.currentTimeMillis()
        fps.add(t.data.fps.toFloat())
        temp.add(t.data.tempC)
        power.add(t.data.powerW)
        var sum = 0L
        for (d in t.frames) {
            val b = (d / 100_000L).toInt().coerceIn(0, BUCKETS - 1)
            hist[b]++
            frameCount++
            frameSumNs += d
            sum += d
        }
        frameMs.add(if (t.frames.isNotEmpty()) sum / 1e6f / t.frames.size else 0f)
    }

    @Synchronized
    fun finish() {
        if (!active) return
        active = false
        SessionStore.setLive(false)
        val dur = ((lastMs - startMs) / 1000L).toInt()
        if (dur >= MIN_SEC && fps.size >= 5) save(dur)
        clear()
    }

    private fun save(dur: Int) {
        val fpsPos = fps.filter { it > 0f }
        val avgFps = if (fpsPos.isEmpty()) 0f else fpsPos.average().toFloat()
        val avgMs = if (frameCount > 0L) frameSumNs / 1e6f / frameCount else -1f

        // 5% terendah: rata-rata frametime dari 5% frame paling lambat, lalu dibalik jadi FPS.
        var low5 = -1f
        if (frameCount > 0L) {
            val need = maxOf(1L, frameCount * 5L / 100L)
            var left = need
            var sumMs = 0.0
            var i = BUCKETS - 1
            while (i >= 0 && left > 0L) {
                val take = minOf(left, hist[i].toLong())
                sumMs += take * ((i + 0.5) / 10.0)   // pusat kotak, ms
                left -= take
                i--
            }
            if (sumMs > 0.0) low5 = (1000.0 / (sumMs / need)).toFloat()
        } else if (fpsPos.isNotEmpty()) {
            low5 = fpsPos.sorted().take(maxOf(1, fpsPos.size * 5 / 100)).average().toFloat()
        }

        val tempS = fillSeries(temp)
        val powS = fillSeries(power)
        val maxT = if (tempS.isEmpty()) -1f else tempS.max()
        val avgP = if (powS.isEmpty()) -1f else powS.average().toFloat()

        val pm = ctx.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        SessionStore.add(
            Session(
                id = startMs, startMs = startMs, durationSec = dur, pkg = pkg, label = label,
                avgFps = avgFps, low5Fps = low5, avgFrameMs = avgMs, maxTemp = maxT, avgPower = avgP,
                fps = shrink(fps, false),
                frameMs = shrink(frameMs, false),
                temp = shrink(tempS, true),
                power = shrink(powS, false)
            )
        )
    }

    /** Isi nilai kosong (-1) dengan nilai sebelumnya. Bila tak ada nilai valid sama sekali: kosong. */
    private fun fillSeries(src: List<Float>): List<Float> {
        if (src.none { it >= 0f }) return emptyList()
        var last = src.first { it >= 0f }
        val out = ArrayList<Float>(src.size)
        for (v in src) {
            if (v >= 0f) last = v
            out.add(last)
        }
        return out
    }

    /** Ringkas ke maksimal [MAX_POINTS] titik: rata-rata per kelompok (atau nilai tertinggi bila useMax). */
    private fun shrink(src: List<Float>, useMax: Boolean): List<Float> {
        if (src.size <= MAX_POINTS) return src.toList()
        val out = ArrayList<Float>(MAX_POINTS)
        for (i in 0 until MAX_POINTS) {
            val a = i * src.size / MAX_POINTS
            val b = maxOf(a + 1, (i + 1) * src.size / MAX_POINTS)
            val part = src.subList(a, b)
            out.add(if (useMax) part.max() else part.average().toFloat())
        }
        return out
    }
}
