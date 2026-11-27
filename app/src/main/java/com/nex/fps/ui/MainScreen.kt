package com.nex.fps.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.nex.fps.OverlayConfig
import com.nex.fps.OverlayService
import com.nex.fps.Prefs
import com.nex.fps.Session
import com.nex.fps.SessionStore
import com.nex.fps.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ======================================================================
// State dan aksi
// ======================================================================

class UiState(private val prefs: Prefs) {
    var cfg by mutableStateOf(prefs.load())
        private set
    var overlayGranted by mutableStateOf(false)
    var notifGranted by mutableStateOf(true)

    // Menyegarkan tampilan bila pengaturan diubah dari luar (mis. editor posisi di layanan).
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cfg = prefs.load() }

    init {
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun update(transform: (OverlayConfig) -> OverlayConfig) {
        cfg = transform(cfg)
        prefs.save(cfg)
    }

    fun refresh(ctx: Context) {
        overlayGranted = Settings.canDrawOverlays(ctx)
        notifGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

class Actions(
    val toggleOverlay: () -> Unit,
    val requestShizuku: () -> Unit,
    val openShizukuDownload: () -> Unit,
    val openShizukuApp: () -> Unit,
    val openDevOptions: () -> Unit,
    val openOverlaySettings: () -> Unit,
    val requestNotification: () -> Unit
)

class AppEntry(val pkg: String, val label: String)

object AppLoader {
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in infos) {
            val p = ri.activityInfo.packageName
            if (p == ctx.packageName || !seen.add(p)) continue
            out.add(AppEntry(p, ri.loadLabel(pm).toString()))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }
}

private object IconCache {
    val map = HashMap<String, ImageBitmap>()
}

private fun loadIcon(ctx: Context, pkg: String): ImageBitmap? = try {
    ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
} catch (e: Exception) {
    null
}

// ======================================================================
// Kerangka: 3 tab (Monitor, Riwayat, Aplikasi), semuanya satu layar
// ======================================================================

@Composable
fun MainScreen(ui: UiState, actions: Actions) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(GhBg)
            .statusBarsPadding()
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MonitorTab(ui, actions)
                1 -> HistoryTab(ui)
                else -> AppsTab(ui)
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Monitor", "Riwayat", "Aplikasi")
    Row(
        Modifier
            .fillMaxWidth()
            .background(GhBar)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (i in names.indices) {
            val on = i == selected
            val c = if (on) Color.White else GhMuted
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Color(0xFF2A2A31) else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 5.dp)
                ) { NavIcon(i, c) }
                Spacer(Modifier.height(2.dp))
                Text(names[i], color = c, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun NavIcon(kind: Int, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            0 -> { // denyut (monitor)
                val p = Path()
                p.moveTo(w * 0.04f, h * 0.55f)
                p.lineTo(w * 0.28f, h * 0.55f)
                p.lineTo(w * 0.40f, h * 0.20f)
                p.lineTo(w * 0.58f, h * 0.85f)
                p.lineTo(w * 0.70f, h * 0.45f)
                p.lineTo(w * 0.78f, h * 0.55f)
                p.lineTo(w * 0.96f, h * 0.55f)
                drawPath(p, color, style = st)
            }
            1 -> { // jam (riwayat)
                drawCircle(color, radius = w * 0.42f, style = st)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.27f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.67f, h * 0.60f), sw, StrokeCap.Round)
            }
            else -> { // kisi (aplikasi)
                val s = w * 0.38f
                val r = CornerRadius(3.dp.toPx())
                for (a in 0..1) for (b in 0..1) {
                    drawRoundRect(
                        color,
                        topLeft = Offset(w * 0.06f + a * w * 0.50f, h * 0.06f + b * h * 0.50f),
                        size = Size(s, s), cornerRadius = r, style = st
                    )
                }
            }
        }
    }
}

// ======================================================================
// Komponen umum
// ======================================================================

@Composable
private fun GhCardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(GhCard)
            .border(1.dp, GhLine, RoundedCornerShape(22.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun TitleRow(title: String, sub: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = GhText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = GhMuted, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) GhBlueDeep else GhCard2)
            .border(1.dp, if (selected) GhBlue else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (selected) Color.White else GhMuted, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1
        )
    }
}

@Composable
private fun SmallPill(text: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = when {
        !enabled -> GhCard2
        filled -> GhBlue
        else -> Color.Transparent
    }
    Box(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (filled || !enabled) Color.Transparent else GhBlue, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (!enabled) GhMuted else if (filled) Color.White else GhCyan
        )
    }
}

@Composable
private fun CheckDot(checked: Boolean, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        if (checked) {
            drawCircle(GhBlueDeep)
            val p = Path()
            p.moveTo(w * 0.28f, w * 0.52f)
            p.lineTo(w * 0.44f, w * 0.68f)
            p.lineTo(w * 0.74f, w * 0.34f)
            drawPath(p, Color(0xFF2AA8FF), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(Color(0xFF55555F), radius = w / 2f - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun GhSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = GhBlue,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color(0xFF9A9AA5),
            uncheckedTrackColor = Color(0xFF2A2A31),
            uncheckedBorderColor = Color.Transparent
        )
    )
}

// ======================================================================
// Tab 1: Monitor
// ======================================================================

@Composable
private fun MonitorTab(ui: UiState, actions: Actions) {
    val shiz by ShizukuHelper.state.collectAsState()
    val running by OverlayService.running.collectAsState()
    val cfg = ui.cfg
    val ready = ui.overlayGranted && shiz.granted

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleRow("Nex FPS", "Monitor performa game") {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GhCard2)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) GhGreen else GhMuted))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Aktif" else "Mati", color = GhText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Kartu utama: tombol mulai / hentikan
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF123456), Color(0xFF0B1523))))
                .border(1.dp, Color(0x552AA8FF), RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (running) "Overlay berjalan" else "Overlay siap", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        !ready -> "Selesaikan setup di bawah dulu."
                        cfg.apps.isEmpty() -> "Pilih game di tab Aplikasi."
                        else -> "${cfg.apps.size} aplikasi dipantau."
                    },
                    color = Color(0xB3FFFFFF), fontSize = 12.sp
                )
            }
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .height(46.dp)
                    .clip(shape)
                    .background(
                        if (running) Brush.linearGradient(listOf(Color(0xFF3A1A1E), Color(0xFF3A1A1E)))
                        else Brush.linearGradient(listOf(GhBlue, GhCyan))
                    )
                    .border(1.dp, if (running) GhRed else Color.Transparent, shape)
                    .clickable(onClick = actions.toggleOverlay)
                    .padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (running) "Hentikan" else "Mulai",
                    color = if (running) GhRed else Color(0xFF04121F),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        if (!ready) {
            SetupCard(
                installed = shiz.installed, running = shiz.running, granted = shiz.granted,
                denied = shiz.denied, overlay = ui.overlayGranted, actions = actions
            )
        }

        // Metrik HUD (gaya "HUD Detailed Config")
        GhCardBox {
            Column {
                val all = cfg.showFps && cfg.showCpu && cfg.showGpu && cfg.showRam
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Metrik HUD", color = GhText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        if (all) "Kosongkan" else "Pilih semua", color = GhCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { ui.update { c -> c.copy(showFps = !all, showCpu = !all, showGpu = !all, showRam = !all) } }
                            .padding(6.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    CheckRow("FPS", cfg.showFps, Modifier.weight(1f)) { ui.update { c -> c.copy(showFps = !c.showFps) } }
                    CheckRow("CPU", cfg.showCpu, Modifier.weight(1f)) { ui.update { c -> c.copy(showCpu = !c.showCpu) } }
                }
                Row(Modifier.fillMaxWidth()) {
                    CheckRow("GPU", cfg.showGpu, Modifier.weight(1f)) { ui.update { c -> c.copy(showGpu = !c.showGpu) } }
                    CheckRow("RAM", cfg.showRam, Modifier.weight(1f)) { ui.update { c -> c.copy(showRam = !c.showRam) } }
                }
            }
        }

        // Posisi dan gaya
        GhCardBox {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Posisi", color = GhText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Kiri", cfg.position == OverlayConfig.POS_LEFT, Modifier.weight(1f)) { ui.update { c -> c.copy(position = OverlayConfig.POS_LEFT) } }
                    Chip("Tengah", cfg.position == OverlayConfig.POS_CENTER, Modifier.weight(1f)) { ui.update { c -> c.copy(position = OverlayConfig.POS_CENTER) } }
                    Chip("Kanan", cfg.position == OverlayConfig.POS_RIGHT, Modifier.weight(1f)) { ui.update { c -> c.copy(position = OverlayConfig.POS_RIGHT) } }
                    Chip("Bebas", cfg.position == OverlayConfig.POS_FREE, Modifier.weight(1f)) { ui.update { c -> c.copy(position = OverlayConfig.POS_FREE) } }
                }
                Spacer(Modifier.height(2.dp))
                Text("Gaya", color = GhText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Teks ringkas", cfg.style == OverlayConfig.STYLE_TEXT, Modifier.weight(1f)) { ui.update { c -> c.copy(style = OverlayConfig.STYLE_TEXT) } }
                    Chip("Lingkaran", cfg.style == OverlayConfig.STYLE_GAUGE, Modifier.weight(1f)) { ui.update { c -> c.copy(style = OverlayConfig.STYLE_GAUGE) } }
                }
                if (cfg.position == OverlayConfig.POS_FREE) {
                    Text("Bebas: geser panel overlay dengan jari saat game terbuka.", color = GhMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckDot(checked)
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (checked) GhText else GhMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Panduan Shizuku diringkas jadi satu langkah aktif; muncul hanya sampai setup selesai. */
@Composable
private fun SetupCard(
    installed: Boolean, running: Boolean, granted: Boolean, denied: Boolean, overlay: Boolean, actions: Actions
) {
    val done = booleanArrayOf(installed, running, granted, overlay)
    val cur = done.indexOfFirst { !it }.let { if (it < 0) 3 else it }
    val titles = listOf("Pasang Shizuku", "Jalankan Shizuku", "Beri izin Shizuku", "Tampil di atas aplikasi lain")
    val descs = listOf(
        "Unduh Shizuku terbaru (shizuku.rikka.app atau Play Store).",
        "Aktifkan Opsi pengembang > Debugging nirkabel, buka Shizuku lalu tekan Mulai. Ulangi setelah ponsel restart.",
        if (denied) "Izin ditolak: buka Shizuku > Aplikasi yang diizinkan > Nex Fps Overlay > Izinkan."
        else "Pilih Izinkan pada dialog Shizuku.",
        "Aktifkan sakelar Nex Fps Overlay agar overlay bisa muncul di atas game."
    )
    GhCardBox {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Setup ${cur + 1}/4", color = GhCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                for (i in 0..3) {
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .width(if (i == cur) 22.dp else 12.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (done[i]) GhGreen else if (i == cur) GhBlue else Color(0xFF34343C))
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(titles[cur], color = GhText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(descs[cur], color = GhMuted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (cur) {
                    0 -> SmallPill("Unduh Shizuku", onClick = actions.openShizukuDownload)
                    1 -> {
                        SmallPill("Opsi pengembang", filled = false, onClick = actions.openDevOptions)
                        SmallPill("Buka Shizuku", enabled = installed, onClick = actions.openShizukuApp)
                    }
                    2 -> SmallPill("Beri izin", enabled = running && !granted, onClick = actions.requestShizuku)
                    else -> SmallPill("Buka pengaturan", enabled = !overlay, onClick = actions.openOverlaySettings)
                }
            }
        }
    }
}

// ======================================================================
// Tab 2: Riwayat sesi (ringkasan, grafik, daftar maksimal 5)
// ======================================================================

private class MetricDef(val label: String, val unit: String, val color: Color, val decimals: Int, val zeroBased: Boolean, val title: String)

private val MetricDefs = listOf(
    MetricDef("Avg FPS", "fps", GhBlue, 0, true, "FPS per detik"),
    MetricDef("5% Low", "fps", GhAmber, 0, true, "FPS per detik, garis = 5% Low"),
    MetricDef("Frametime", "ms", GhCyan, 1, true, "Frametime (ms)"),
    MetricDef("Max Temp", "°C", GhRed, 0, false, "Suhu (°C)"),
    MetricDef("Avg Power", "W", GhGreen, 1, true, "Daya (Watt)")
)

private fun seriesOf(s: Session, m: Int): List<Float> = when (m) {
    0, 1 -> s.fps
    2 -> s.frameMs
    3 -> s.temp
    else -> s.power
}

private fun aggOf(s: Session, m: Int): Float = when (m) {
    0 -> s.avgFps
    1 -> s.low5Fps
    2 -> s.avgFrameMs
    3 -> s.maxTemp
    else -> s.avgPower
}

private fun fmt(v: Float, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", v)

private fun fmtDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}

private fun fmtClock(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

private val DateFmt = SimpleDateFormat("d MMM, HH:mm", Locale("id", "ID"))

private fun fmtDate(ms: Long): String = DateFmt.format(Date(ms))

@Composable
private fun HistoryTab(ui: UiState) {
    val items by SessionStore.items.collectAsState()
    val live by SessionStore.live.collectAsState()
    var selId by rememberSaveable { mutableStateOf(-1L) }
    var metric by rememberSaveable { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val sel = items.firstOrNull { it.id == selId } ?: items.firstOrNull()

    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(3000L)
            confirmClear = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TitleRow("Riwayat", if (live) "● Sedang merekam sesi…" else "${items.size}/${SessionStore.MAX} sesi tersimpan") {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GhCard2)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Rekam", color = if (ui.cfg.record) GhText else GhMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                GhSwitch(ui.cfg.record) { v -> ui.update { c -> c.copy(record = v) } }
            }
        }

        if (sel == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(GhCard2), contentAlignment = Alignment.Center) {
                        NavIcon(1, GhMuted)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Belum ada riwayat", color = GhText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (ui.cfg.record) "Mulai overlay, lalu main minimal ${com.nex.fps.SessionRecorder.MIN_SEC} detik. Sesi tersimpan otomatis."
                        else "Mode rekam mati. Nyalakan sakelar Rekam di kanan atas untuk menyimpan sesi.",
                        color = GhMuted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
                    )
                }
            }
        } else {
            // 5 kartu ringkasan; ketuk untuk mengganti grafik
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in MetricDefs.indices) {
                    MetricTile(MetricDefs[i], aggOf(sel, i), i == metric, Modifier.weight(1f)) { metric = i }
                }
            }
            ChartCard(sel, metric, Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in items) {
                    HistoryRow(s, s.id == sel.id, onSelect = { selId = s.id }, onDelete = { SessionStore.delete(s.id) })
                }
            }
        }

        // Tombol Hapus Semua: di bawah, mudah dijangkau jempol. Ketuk dua kali agar tidak terhapus tak sengaja.
        val canClear = items.isNotEmpty()
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .background(if (confirmClear) GhRed else Color.Transparent)
                .border(1.dp, if (canClear) GhRed else GhLine, shape)
                .clickable(enabled = canClear) {
                    if (confirmClear) {
                        SessionStore.clearAll()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (confirmClear) "Ketuk lagi untuk hapus semua" else "Hapus Semua",
                color = if (confirmClear) Color.White else if (canClear) GhRed else GhMuted,
                fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun MetricTile(def: MetricDef, value: Float, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) def.color.copy(alpha = 0.16f) else GhCard)
            .border(1.dp, if (selected) def.color else GhLine, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(def.label, color = GhMuted, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            if (value >= 0f) fmt(value, def.decimals) else "–",
            color = if (selected) def.color else GhText, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
        Text(def.unit, color = GhMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ChartCard(s: Session, metric: Int, modifier: Modifier) {
    val def = MetricDefs[metric]
    val values = seriesOf(s, metric)
    var sel by remember(s.id, metric) { mutableStateOf(-1) }
    val ref = aggOf(s, metric)

    GhCardBox(modifier.heightIn(min = 120.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(def.title, color = GhText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                if (values.isNotEmpty()) {
                    val idx = sel
                    Text(
                        if (idx in values.indices) {
                            val t = if (values.size > 1) s.durationSec * idx / (values.size - 1) else 0
                            "${fmt(values[idx], def.decimals)} ${def.unit} · ${fmtClock(t)}"
                        } else "Min ${fmt(values.min(), def.decimals)} · Maks ${fmt(values.max(), def.decimals)}",
                        color = def.color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (values.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Sensor ini tidak tersedia di perangkat.", color = GhMuted, fontSize = 13.sp)
                }
            } else {
                LineChart(
                    values = values, color = def.color,
                    ref = ref, refLabel = (if (metric == 1) "5% Low " else if (metric == 3) "Maks " else "Rata-rata ") + (if (ref >= 0f) fmt(ref, def.decimals) else ""),
                    refColor = if (metric == 1) GhAmber else Color(0xCCFFFFFF),
                    zeroBased = def.zeroBased, sel = sel, onSel = { sel = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
                    Text("0:00", color = GhMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(fmtClock(s.durationSec), color = GhMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

private val ChartLeft = 34.dp

private fun niceCeil(x: Float): Float {
    if (x <= 0f) return 1f
    val e = 10f.pow(floor(log10(x)))
    val f = x / e
    val steps = floatArrayOf(1f, 1.2f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)
    for (st in steps) if (f <= st) return st * e
    return 10f * e
}

@Composable
private fun LineChart(
    values: List<Float>, color: Color, ref: Float, refLabel: String, refColor: Color,
    zeroBased: Boolean, sel: Int, onSel: (Int) -> Unit, modifier: Modifier
) {
    val tm = rememberTextMeasurer()
    val n = values.size
    val labelStyle = TextStyle(color = GhMuted, fontSize = 9.sp)
    val refStyle = TextStyle(color = refColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)

    var vMin = values.min()
    var vMax = values.max()
    if (ref >= 0f) {
        vMin = min(vMin, ref)
        vMax = max(vMax, ref)
    }
    val lo: Float
    var hi: Float
    if (zeroBased) {
        lo = 0f
        hi = niceCeil(vMax * 1.1f)
    } else {
        lo = floor(vMin - 2f)
        hi = ceil(vMax + 2f)
        if (hi - lo < 4f) hi = lo + 4f
    }
    val range = hi - lo
    val labelDecimals = if (range <= 6f) 1 else 0

    Canvas(
        modifier.pointerInput(n) {
            val left = ChartLeft.toPx()
            val right = 6.dp.toPx()
            fun idx(x: Float): Int {
                if (n < 2) return 0
                val w = size.width - left - right
                return (((x - left) / w) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                onSel(idx(down.position.x))
                drag(down.id) { ch ->
                    onSel(idx(ch.position.x))
                    ch.consume()
                }
            }
        }
    ) {
        val left = ChartLeft.toPx()
        val right = 6.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 4.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        if (w <= 0f || h <= 0f) return@Canvas

        fun yOf(v: Float): Float = top + h - ((v - lo) / range).coerceIn(0f, 1f) * h
        fun xOf(i: Int): Float = if (n < 2) left + w / 2f else left + w * i / (n - 1)

        // garis bantu + label sumbu Y
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        for (k in 0..3) {
            val v = lo + range * k / 3f
            val y = yOf(v)
            drawLine(Color(0x1FFFFFFF), Offset(left, y), Offset(left + w, y), 1.dp.toPx(), pathEffect = if (k == 0) null else dash)
            val r = tm.measure(fmt(v, labelDecimals), labelStyle)
            drawText(r, topLeft = Offset(left - 6.dp.toPx() - r.size.width, y - r.size.height / 2f))
        }

        // area + garis data
        if (n >= 2) {
            val line = Path()
            val area = Path()
            for (i in 0 until n) {
                val x = xOf(i)
                val y = yOf(values[i])
                if (i == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, top + h)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(xOf(n - 1), top + h)
            area.close()
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent), startY = top, endY = top + h))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(color, 4.dp.toPx(), Offset(xOf(0), yOf(values[0])))
        }

        // garis acuan (rata-rata / 5% Low / maks)
        if (ref >= 0f) {
            val y = yOf(ref)
            drawLine(refColor, Offset(left, y), Offset(left + w, y), 1.dp.toPx(), pathEffect = dash)
            val r = tm.measure(refLabel, refStyle)
            val ty = if (y - r.size.height - 1.dp.toPx() < 0f) y + 2.dp.toPx() else y - r.size.height - 1.dp.toPx()
            drawText(r, topLeft = Offset(left + w - r.size.width, ty))
        }

        // penanda saat disentuh
        if (sel in 0 until n) {
            val x = xOf(sel)
            val y = yOf(values[sel])
            drawLine(Color(0x55FFFFFF), Offset(x, top), Offset(x, top + h), 1.dp.toPx())
            drawCircle(color, 5.dp.toPx(), Offset(x, y))
            drawCircle(GhBg, 2.5.dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
private fun HistoryRow(s: Session, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(if (selected) GhCard2 else GhCard)
            .border(1.dp, if (selected) GhBlue else GhLine, shape)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${fmtDate(s.startMs)} – Durasi ${fmtDuration(s.durationSec)}",
                color = GhText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(s.label, color = GhMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(46.dp)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) { Text("✕", color = GhMuted, fontSize = 15.sp) }
    }
}

// ======================================================================
// Tab 3: Aplikasi yang dipantau
// ======================================================================

@Composable
private fun AppsTab(ui: UiState) {
    val ctx = LocalContext.current
    val cfg = ui.cfg
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppLoader.load(ctx) }
        loading = false
    }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TitleRow("Aplikasi", "${cfg.apps.size} dipilih dari ${apps.size}") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallPill("Pilih semua", filled = false) { ui.update { c -> c.copy(apps = c.apps + filtered.map { a -> a.pkg }) } }
                SmallPill("Kosongkan", filled = false, enabled = cfg.apps.isNotEmpty()) { ui.update { c -> c.copy(apps = emptySet()) } }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Cari aplikasi") },
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GhText, unfocusedTextColor = GhText,
                focusedBorderColor = GhBlue, unfocusedBorderColor = GhLine,
                focusedContainerColor = GhCard, unfocusedContainerColor = GhCard,
                focusedPlaceholderColor = GhMuted, unfocusedPlaceholderColor = GhMuted,
                cursorColor = GhBlue
            )
        )
        Text("Overlay hanya muncul saat aplikasi yang dicentang sedang dibuka.", color = GhMuted, fontSize = 11.sp)
        when {
            loading -> Text("Memuat daftar aplikasi…", color = GhMuted, modifier = Modifier.padding(8.dp))
            filtered.isEmpty() -> Text("Tidak ada aplikasi yang cocok.", color = GhMuted, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.pkg }) { app ->
                    AppRow(app, cfg.apps.contains(app.pkg)) {
                        ui.update { c -> c.copy(apps = if (c.apps.contains(app.pkg)) c.apps - app.pkg else c.apps + app.pkg) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = IconCache.map[app.pkg], key1 = app.pkg) {
        if (value == null) {
            val bmp = withContext(Dispatchers.IO) { loadIcon(ctx, app.pkg) }
            if (bmp != null) {
                IconCache.map[app.pkg] = bmp
                value = bmp
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) GhCard else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(GhCard2))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = GhText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.pkg, color = GhMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CheckDot(checked)
    }
}
