package com.nex.fps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Tampilan overlay yang digambar langsung di Canvas (ringan, tanpa layout bertingkat).
 * Dua gaya: STYLE_TEXT (satu baris teks) dan STYLE_GAUGE (panel dengan lingkaran seperti gambar).
 */
class OverlayView(context: Context) : View(context) {

    private var showFps = true
    private var showCpu = true
    private var showGpu = true
    private var showRam = true
    private var mode = OverlayConfig.STYLE_GAUGE
    private var snap = Snapshot()

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202733L.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(6f)
        color = 0x44FFFFFF
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(6f)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = bold
        textSize = sp(18f)
        textAlign = Paint.Align.CENTER
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = bold
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFFL.toInt()
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val tLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8C2D6L.toInt()
        textSize = sp(11f)
    }
    private val tValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        textSize = sp(13f)
    }
    private val rect = RectF()

    // ---- data ------------------------------------------------------------

    fun configure(fps: Boolean, cpu: Boolean, gpu: Boolean, ram: Boolean, styleMode: Int) {
        if (fps == showFps && cpu == showCpu && gpu == showGpu && ram == showRam && styleMode == mode) return
        showFps = fps
        showCpu = cpu
        showGpu = gpu
        showRam = ram
        mode = styleMode
        requestLayout()
        invalidate()
    }

    fun update(s: Snapshot) {
        snap = s
        invalidate()   // ukuran bergantung konfigurasi saja, tidak pada nilai
    }

    // ---- warna & teks ----------------------------------------------------

    private fun levelColor(pct: Int): Int = when {
        pct < 0 -> 0xFFB0BAC9L.toInt()
        pct < 40 -> 0xFF4ADE80L.toInt()    // hijau
        pct < 70 -> 0xFFFACC15L.toInt()    // kuning
        pct < 90 -> 0xFFFB923CL.toInt()    // oranye
        else -> 0xFFF87171L.toInt()        // merah
    }

    private fun fpsColor(fps: Int): Int = when {
        fps < 0 -> 0xFFB0BAC9L.toInt()
        fps >= 55 -> 0xFF4ADE80L.toInt()
        fps >= 30 -> 0xFFFACC15L.toInt()
        fps >= 20 -> 0xFFFB923CL.toInt()
        else -> 0xFFF87171L.toInt()
    }

    private fun pctText(p: Int) = if (p < 0) "--%" else "$p%"
    private fun fpsText() = if (snap.fps < 0) "--" else snap.fps.toString()
    private fun mhzText(m: Int) = if (m < 0) "--" else m.toString()
    private fun ramText(mb: Int): String = when {
        mb < 0 -> "--"
        mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024f)
        else -> "$mb MB"
    }

    private fun headerText(): String =
        if (snap.resP > 0) "${fpsText()} FPS \u00B7 ${snap.resP}p" else "${fpsText()} FPS"

    // ---- ukuran ----------------------------------------------------------

    private class Item(val label: String, val value: String, val color: Int, val wide: Boolean)
    private class Ring(val pct: Int, val center: String, val label: String)

    private fun textItems(): List<Item> {
        val l = ArrayList<Item>()
        if (showFps) l.add(Item("FPS", fpsText(), fpsColor(snap.fps), false))
        if (showCpu) l.add(Item("CPU", pctText(snap.cpuPct), levelColor(snap.cpuPct), true))
        if (showGpu) l.add(Item("GPU", pctText(snap.gpuPct), levelColor(snap.gpuPct), true))
        if (showRam) l.add(Item("RAM", pctText(snap.ramPct), levelColor(snap.ramPct), true))
        return l
    }

    private fun rings(): List<Ring> {
        val l = ArrayList<Ring>()
        if (showCpu) l.add(Ring(snap.cpuPct, pctText(snap.cpuPct), "CPU ${mhzText(snap.cpuMhz)} MHz"))
        if (showGpu) l.add(Ring(snap.gpuPct, pctText(snap.gpuPct), "GPU ${mhzText(snap.gpuMhz)} MHz"))
        if (showRam) l.add(Ring(snap.ramPct, pctText(snap.ramPct), "RAM ${ramText(snap.ramUsedMb)}"))
        return l
    }

    private fun ringCount(): Int = (if (showCpu) 1 else 0) + (if (showGpu) 1 else 0) + (if (showRam) 1 else 0)
    private fun valueSlot(wide: Boolean): Float = tValue.measureText(if (wide) "100%" else "000")

    private fun textSize(): IntArray {
        val items = textItems()
        if (items.isEmpty()) return intArrayOf(1, 1)
        val pad = dp(10f)
        val gap = dp(10f)
        val inner = dp(4f)
        var w = pad * 2
        for ((i, item) in items.withIndex()) {
            if (i > 0) w += gap
            w += tLabel.measureText(item.label) + inner + valueSlot(item.wide)
        }
        val h = pad * 2 + max(tLabel.fontSpacing, tValue.fontSpacing)
        return intArrayOf(ceil(w.toDouble()).toInt(), ceil(h.toDouble()).toInt())
    }

    private fun gaugeSize(): IntArray {
        val n = ringCount()
        if (!showFps && n == 0) return intArrayOf(1, 1)
        val pad = dp(12f)
        val slot = dp(76f)
        val gap = dp(4f)
        val ring = dp(52f)
        val headerH = dp(24f)
        val headerGap = dp(6f)
        val labelGap = dp(4f)
        val labelH = dp(13f)
        val rowW = if (n > 0) n * slot + (n - 1) * gap else 0f
        val headerW = if (showFps) headerPaint.measureText("000 FPS \u00B7 0000p") else 0f
        val w = pad * 2 + max(rowW, headerW)
        var h = pad * 2
        if (showFps) h += headerH
        if (showFps && n > 0) h += headerGap
        if (n > 0) h += ring + labelGap + labelH
        return intArrayOf(ceil(w.toDouble()).toInt(), ceil(h.toDouble()).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = if (mode == OverlayConfig.STYLE_TEXT) textSize() else gaugeSize()
        setMeasuredDimension(resolveSize(s[0], widthMeasureSpec), resolveSize(s[1], heightMeasureSpec))
    }

    // ---- gambar ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (mode == OverlayConfig.STYLE_TEXT) drawTextStyle(canvas) else drawGaugeStyle(canvas)
    }

    private fun baselineFor(centerY: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawTextStyle(c: Canvas) {
        val items = textItems()
        if (items.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, dp(12f), dp(12f), bgPaint)
        val inner = dp(4f)
        val baseLabel = baselineFor(h / 2f, tLabel)
        val baseValue = baselineFor(h / 2f, tValue)

        // Ukuran panel tetap (berdasarkan slot terlebar). Sisa ruang dibagi rata
        // di tepi kiri, di antara item, dan tepi kanan, jadi teks tampil seimbang.
        var contentW = 0f
        for (item in items) {
            contentW += tLabel.measureText(item.label) + inner + tValue.measureText(item.value)
        }
        val space = max(dp(4f), (w - contentW) / (items.size + 1))
        var x = space

        for (item in items) {
            c.drawText(item.label, x, baseLabel, tLabel)
            x += tLabel.measureText(item.label) + inner
            tValue.color = item.color
            c.drawText(item.value, x, baseValue, tValue)
            x += tValue.measureText(item.value) + space
        }
    }

    private fun drawGaugeStyle(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, dp(16f), dp(16f), bgPaint)

        val pad = dp(12f)
        val slot = dp(76f)
        val gap = dp(4f)
        val ring = dp(52f)
        val stroke = dp(6f)
        val headerH = dp(24f)
        val headerGap = dp(6f)
        val labelGap = dp(4f)

        var y = pad
        val list = rings()
        if (showFps) {
            c.drawText(headerText(), w / 2f, baselineFor(y + headerH / 2f, headerPaint), headerPaint)
            y += headerH
            if (list.isNotEmpty()) y += headerGap
        }
        if (list.isEmpty()) return

        val rowW = list.size * slot + (list.size - 1) * gap
        var x = (w - rowW) / 2f
        for (r in list) {
            val cx = x + slot / 2f
            val cy = y + ring / 2f
            val half = ring / 2f - stroke / 2f
            rect.set(cx - half, cy - half, cx + half, cy + half)
            c.drawArc(rect, 0f, 360f, false, trackPaint)
            if (r.pct > 0) {
                arcPaint.color = levelColor(r.pct)
                c.drawArc(rect, -90f, 360f * r.pct / 100f, false, arcPaint)
            }
            c.drawText(r.center, cx, baselineFor(cy, centerPaint), centerPaint)
            val labelTop = y + ring + labelGap
            c.drawText(r.label, cx, labelTop - labelPaint.fontMetrics.ascent, labelPaint)
            x += slot + gap
        }
    }
}
