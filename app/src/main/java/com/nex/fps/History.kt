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
    val power: List<Float>,
    val tempGpu: List<Float> = emptyList(),   // suhu GPU (temp = suhu CPU)
    val maxTempGpu: Float = -1f,
    val tempBat: List<Float> = emptyList(),   // suhu baterai
    val maxTempBat: Float = -1f,
    val fpsMax: List<Float> = emptyList(),    // FPS puncak per titik (1000 / frametime terkecil)
    val fpsMin: List<Float> = emptyList(),    // FPS terendah per titik (1000 / frametime terbesar)
    val smooth: List<Float> = emptyList(),    // FPS Smoothness (%) per titik
    val variance: List<Float> = emptyList(),  // varian frametime (ms^2) per titik
    val maxFps: Float = -1f,
    val minFps: Float = -1f,
    val avgSmooth: Float = -1f,
    val varianceAll: Float = -1f
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
        .put("tempG", series(s.tempGpu)).put("maxTG", s.maxTempGpu.toDouble())
        .put("tempB", series(s.tempBat)).put("maxTB", s.maxTempBat.toDouble())
        .put("fMax", series(s.fpsMax)).put("fMin", series(s.fpsMin))
        .put("sm", series(s.smooth)).put("var", series(s.variance))
        .put("maxF", s.maxFps.toDouble()).put("minF", s.minFps.toDouble())
        .put("avgSm", s.avgSmooth.toDouble()).put("varAll", s.varianceAll.toDouble())

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
        power = readSeries(o.optJSONArray("pw")),
        tempGpu = readSeries(o.optJSONArray("tempG")),
        maxTempGpu = o.optDouble("maxTG", -1.0).toFloat(),
        tempBat = readSeries(o.optJSONArray("tempB")),
        maxTempBat = o.optDouble("maxTB", -1.0).toFloat(),
        fpsMax = readSeries(o.optJSONArray("fMax")),
        fpsMin = readSeries(o.optJSONArray("fMin")),
        smooth = readSeries(o.optJSONArray("sm")),
        variance = readSeries(o.optJSONArray("var")),
        maxFps = o.optDouble("maxF", -1.0).toFloat(),
        minFps = o.optDouble("minF", -1.0).toFloat(),
        avgSmooth = o.optDouble("avgSm", -1.0).toFloat(),
        varianceAll = o.optDouble("varAll", -1.0).toFloat()
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
    private val tempGpu = ArrayList<Float>()
    private val tempBat = ArrayList<Float>()
    private val fpsMaxL = ArrayList<Float>()
    private val fpsMinL = ArrayList<Float>()
    private val smoothL = ArrayList<Float>()
    private val varL = ArrayList<Float>()
    private var sumSqMs = 0.0
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
        fps.clear(); frameMs.clear(); temp.clear(); tempGpu.clear(); tempBat.clear(); power.clear()
        fpsMaxL.clear(); fpsMinL.clear(); smoothL.clear(); varL.clear()
        sumSqMs = 0.0
        java.util.Arrays.fill(hist, 0)
        frameCount = 0L
        frameSumNs = 0L
        idle = 0
    }

    private fun add(t: Sampler.Tick) {
        lastMs = System.currentTimeMillis()
        fps.add(t.data.fps.toFloat())
        temp.add(t.data.tempC)
        tempGpu.add(t.data.gpuTempC)
        tempBat.add(t.data.batTempC)
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

        // Per tick (1 detik): FPS puncak/terendah dari frametime terkecil/terbesar, smoothness, dan varian.
        val n = t.frames.size
        if (n > 0) {
            var mn = Long.MAX_VALUE
            var mx = 0L
            var sumD = 0.0
            var sumSq = 0.0
            for (d in t.frames) {
                val ms = d / 1e6
                sumD += ms
                sumSq += ms * ms
                if (d < mn) mn = d
                if (d > mx) mx = d
            }
            val mean = sumD / n
            val variance = maxOf(0.0, sumSq / n - mean * mean)
            sumSqMs += sumSq
            fpsMaxL.add(1000f / (mn / 1e6f))
            fpsMinL.add(1000f / (mx / 1e6f))
            if (n >= 2 && mean > 0.0) {
                smoothL.add((100.0 * (1.0 - minOf(1.0, Math.sqrt(variance) / mean))).toFloat())
                varL.add(variance.toFloat())
            } else {
                smoothL.add(-1f)
                varL.add(-1f)
            }
        } else {
            fpsMaxL.add(t.data.fps.toFloat())
            fpsMinL.add(t.data.fps.toFloat())
            smoothL.add(-1f)
            varL.add(-1f)
        }
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

        val fMaxPos = fpsMaxL.filter { it > 0f }
        val fMinPos = fpsMinL.filter { it > 0f }
        val maxFps = if (fMaxPos.isEmpty()) -1f else fMaxPos.max()
        val minFps = if (fMinPos.isEmpty()) -1f else fMinPos.min()
        val smoothS = fillSeries(smoothL)
        val avgSm = if (smoothS.isEmpty()) -1f else smoothS.average().toFloat()
        val varS = fillSeries(varL)
        var varAll = -1f
        if (frameCount > 1L) {
            val mean = frameSumNs / 1e6 / frameCount
            varAll = maxOf(0.0, sumSqMs / frameCount - mean * mean).toFloat()
        }
        val tempS = fillSeries(temp)
        val tempGS = fillSeries(tempGpu)
        val maxTG = if (tempGS.isEmpty()) -1f else tempGS.max()
        val tempBS = fillSeries(tempBat)
        val maxTB = if (tempBS.isEmpty()) -1f else tempBS.max()
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
                fps = shrink(fps, 0),
                frameMs = shrink(frameMs, 0),
                temp = shrink(tempS, 1),
                power = shrink(powS, 0),
                tempGpu = shrink(tempGS, 1),
                maxTempGpu = maxTG,
                tempBat = shrink(tempBS, 1),
                maxTempBat = maxTB,
                fpsMax = shrink(fpsMaxL, 1),
                fpsMin = shrink(fpsMinL, 2),
                smooth = shrink(smoothS, 0),
                variance = shrink(varS, 0),
                maxFps = maxFps, minFps = minFps, avgSmooth = avgSm, varianceAll = varAll
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

    /** Ringkas ke maksimal [MAX_POINTS] titik: rata-rata per kelompok (mode 1 = nilai tertinggi, 2 = terendah). */
    private fun shrink(src: List<Float>, mode: Int): List<Float> {
        if (src.size <= MAX_POINTS) return src.toList()
        val out = ArrayList<Float>(MAX_POINTS)
        for (i in 0 until MAX_POINTS) {
            val a = i * src.size / MAX_POINTS
            val b = maxOf(a + 1, (i + 1) * src.size / MAX_POINTS)
            val part = src.subList(a, b)
            out.add(when (mode) { 1 -> part.max(); 2 -> part.min(); else -> part.average().toFloat() })
        }
        return out
    }
}
