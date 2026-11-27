package com.nex.fps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Layanan latar depan: mengukur secara berkala dan menampilkan overlay hanya saat salah satu
 * aplikasi terpilih berada di layar.
 *
 * Posisi kiri/tengah/kanan atas: jendela tembus sentuhan (tidak mengganggu game).
 * Posisi Bebas: jendela menerima sentuhan supaya panel bisa digeser langsung dengan jari.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.nex.fps.action.STOP"
        private const val CHANNEL_ID = "nex_fps_overlay"
        private const val NOTIF_ID = 1001

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var recorder: SessionRecorder
    private var view: OverlayView? = null
    private var shown = false
    private var lastKey = ""

    // status geser (posisi Bebas)
    private var currentLp: WindowManager.LayoutParams? = null
    private var lastScr = intArrayOf(0, 0)
    private var lastSize = intArrayOf(0, 0)
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        SessionStore.init(this)
        recorder = SessionRecorder(applicationContext)
        ShizukuHelper.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay FPS", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        startLoop()
        return START_NOT_STICKY
    }

    private fun startLoop() {
        job?.cancel()
        val sampler = Sampler(applicationContext)
        job = scope.launch {
            while (isActive) {
                val cfg = prefs.load()
                val tick = try {
                    sampler.tick(cfg)
                } catch (e: Exception) {
                    Sampler.Tick(false, false, Snapshot())
                }
                try {
                    recorder.onTick(cfg, tick)
                } catch (e: Exception) {
                }
                withContext(Dispatchers.Main) { render(cfg, tick) }
                delay(if (tick.active) 1000L else 2000L)
            }
        }
    }

    private fun render(cfg: OverlayConfig, t: Sampler.Tick) {
        if (!t.active || !cfg.anyMetric) {
            hide()
            return
        }
        val v = view ?: createView()
        v.configure(cfg.showFps, cfg.showCpu, cfg.showGpu, cfg.showRam, cfg.style)

        val size = measured(v)
        val scr = screenSize()
        lastSize = size
        lastScr = scr
        val key = "${cfg.position}|${cfg.freeX}|${cfg.freeY}|${scr[0]}x${scr[1]}|${size[0]}x${size[1]}"
        if (!shown) {
            val lp = params(cfg, size[0], size[1], scr)
            try {
                wm.addView(v, lp)
                currentLp = lp
                shown = true
                lastKey = key
            } catch (e: Exception) {
                shown = false
            }
        } else if (key != lastKey && !dragging) {
            val lp = params(cfg, size[0], size[1], scr)
            try {
                wm.updateViewLayout(v, lp)
                currentLp = lp
                lastKey = key
            } catch (e: Exception) {
            }
        }
        v.update(t.data)
    }

    private fun createView(): OverlayView {
        val v = OverlayView(this)
        // Hanya menerima sentuhan saat posisi Bebas (jendela lain tembus sentuhan).
        v.setOnTouchListener { _, e -> onDragTouch(e) }
        view = v
        return v
    }

    /** Geser panel dengan jari; posisi disimpan sebagai pecahan layar saat jari diangkat. */
    private fun onDragTouch(e: MotionEvent): Boolean {
        val lp = currentLp ?: return false
        val v = view ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downRawX = e.rawX
                downRawY = e.rawY
                startX = lp.x
                startY = lp.y
            }
            MotionEvent.ACTION_MOVE -> {
                val maxX = max(0, lastScr[0] - lastSize[0])
                val maxY = max(0, lastScr[1] - lastSize[1])
                lp.x = (startX + (e.rawX - downRawX)).toInt().coerceIn(0, maxX)
                lp.y = (startY + (e.rawY - downRawY)).toInt().coerceIn(0, maxY)
                try {
                    wm.updateViewLayout(v, lp)
                } catch (ex: Exception) {
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val rx = max(1, lastScr[0] - lastSize[0])
                val ry = max(1, lastScr[1] - lastSize[1])
                prefs.setFree(lp.x.toFloat() / rx, lp.y.toFloat() / ry)
            }
        }
        return true
    }

    private fun hide() {
        val v = view
        if (shown && v != null) {
            try {
                wm.removeViewImmediate(v)
            } catch (e: Exception) {
            }
            shown = false
            dragging = false
        }
    }

    private fun measured(v: OverlayView): IntArray {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(spec, spec)
        return intArrayOf(v.measuredWidth, v.measuredHeight)
    }

    private fun screenSize(): IntArray {
        val b = wm.currentWindowMetrics.bounds
        return intArrayOf(b.width(), b.height())
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun params(cfg: OverlayConfig, vw: Int, vh: Int, scr: IntArray): WindowManager.LayoutParams {
        val free = cfg.position == OverlayConfig.POS_FREE
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val flags = if (free) {
            base or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN   // dapat disentuh, koordinat layar penuh
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE      // tembus sentuhan
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        when (cfg.position) {
            OverlayConfig.POS_FREE -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = (cfg.freeX.coerceIn(0f, 1f) * max(0, scr[0] - vw)).toInt()
                lp.y = (cfg.freeY.coerceIn(0f, 1f) * max(0, scr[1] - vh)).toInt()
            }
            OverlayConfig.POS_CENTER -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.x = 0
                lp.y = dp(6f)
            }
            OverlayConfig.POS_RIGHT -> {
                lp.gravity = Gravity.TOP or Gravity.END
                lp.x = dp(8f)
                lp.y = dp(6f)
            }
            else -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = dp(8f)
                lp.y = dp(6f)
            }
        }
        // Android 12+: sentuhan hanya diteruskan ke aplikasi di bawah bila opasitas jendela <= 0.8.
        lp.alpha = 0.8f
        lp.title = "NexFpsOverlay"
        return lp
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fps)
            .setContentTitle("Nex Fps Overlay aktif")
            .setContentText("Overlay muncul saat aplikasi terpilih dibuka")
            .setContentIntent(open)
            .addAction(0, "Hentikan", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        recorder.finish()
        hide()
        _running.value = false
        super.onDestroy()
    }
}
