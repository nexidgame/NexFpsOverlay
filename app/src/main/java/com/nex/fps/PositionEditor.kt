package com.nex.fps

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Jendela sementara yang bisa disentuh untuk menggeser overlay ke posisi bebas.
 * Overlay normal sengaja tembus sentuhan; jendela ini hanya muncul saat "Atur posisi".
 * Posisi disimpan sebagai pecahan 0..1 dari ruang gerak layar, jadi tetap pas saat layar diputar.
 */
class PositionEditor(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onClosed: () -> Unit
) {
    private var box: LinearLayout? = null
    private var preview: OverlayView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    fun show(cfg: OverlayConfig) {
        val d = ctx.resources.displayMetrics.density
        val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Light)
        fun dp(v: Float): Int = (v * d).toInt()

        val pv = OverlayView(themed)
        pv.configure(cfg.showFps, cfg.showCpu, cfg.showGpu, cfg.showRam, cfg.style)
        pv.update(SAMPLE_SNAPSHOT)

        val hint = TextView(themed)
        hint.text = "Geser panel ke posisi yang diinginkan, lalu tekan Simpan."
        hint.setTextColor(Color.WHITE)
        hint.textSize = 12f
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(10f), 0, dp(6f))

        val cancel = Button(themed)
        cancel.text = "Batal"
        cancel.setOnClickListener { close(false) }
        val save = Button(themed)
        save.text = "Simpan posisi"
        save.setOnClickListener { close(true) }
        val row = LinearLayout(themed)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.addView(cancel)
        row.addView(save)

        val frame = GradientDrawable()
        frame.setColor(0xE60B1630L.toInt())
        frame.cornerRadius = 20f * d
        frame.setStroke(dp(2f), 0xFF2F7BFFL.toInt())

        val root = LinearLayout(themed)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(14f), dp(14f), dp(14f), dp(10f))
        root.background = frame
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        root.addView(pv, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(hint, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(row, LinearLayout.LayoutParams(wrap, wrap))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "NexFpsPositionEditor"
        params.x = 0
        params.y = 0

        root.setOnTouchListener { _, e ->
            val l = lp
            if (l == null) {
                false
            } else {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX
                        downRawY = e.rawY
                        startX = l.x
                        startY = l.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val b = wm.currentWindowMetrics.bounds
                        l.x = (startX + (e.rawX - downRawX)).toInt().coerceIn(0, max(0, b.width() - root.width))
                        l.y = (startY + (e.rawY - downRawY)).toInt().coerceIn(0, max(0, b.height() - root.height))
                        try {
                            wm.updateViewLayout(root, l)
                        } catch (ex: Exception) {
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        box = root
        preview = pv
        lp = params
        try {
            wm.addView(root, params)
        } catch (ex: Exception) {
            box = null
            preview = null
            lp = null
            onClosed()
            return
        }

        // Setelah ukuran diketahui, letakkan panel pratinjau tepat di posisi yang tersimpan.
        root.post {
            val b = wm.currentWindowMetrics.bounds
            val rx = max(0, b.width() - pv.width)
            val ry = max(0, b.height() - pv.height)
            val wantX = if (cfg.position == OverlayConfig.POS_FREE) (cfg.freeX * rx).toInt() else rx / 2
            val wantY = if (cfg.position == OverlayConfig.POS_FREE) (cfg.freeY * ry).toInt() else dp(24f)
            params.x = (wantX - pv.left).coerceIn(0, max(0, b.width() - root.width))
            params.y = (wantY - pv.top).coerceIn(0, max(0, b.height() - root.height))
            try {
                wm.updateViewLayout(root, params)
            } catch (ex: Exception) {
            }
        }
    }

    private fun close(save: Boolean) {
        val r = box
        val p = preview
        val l = lp
        if (save && r != null && p != null && l != null) {
            val b = wm.currentWindowMetrics.bounds
            val ox = l.x + p.left          // sudut kiri atas panel pratinjau di layar
            val oy = l.y + p.top
            val rx = max(1, b.width() - p.width)
            val ry = max(1, b.height() - p.height)
            Prefs(ctx).setFree(ox.toFloat() / rx, oy.toFloat() / ry)
        }
        dismiss()
        onClosed()
    }

    /** Menutup jendela tanpa memanggil onClosed (dipakai saat layanan berhenti). */
    fun dismiss() {
        val r = box
        if (r != null) {
            try {
                wm.removeViewImmediate(r as View)
            } catch (ex: Exception) {
            }
        }
        box = null
        preview = null
        lp = null
    }
}
