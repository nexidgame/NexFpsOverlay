package com.nex.fps

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.nex.fps.ui.Actions
import com.nex.fps.ui.MainScreen
import com.nex.fps.ui.NexTheme
import com.nex.fps.ui.UiState

class MainActivity : ComponentActivity() {

    private lateinit var ui: UiState
    private var pendingStart = false

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            ui.refresh(this)
            if (pendingStart) {
                pendingStart = false
                startOverlayNow()   // notifikasi opsional: layanan tetap berjalan walau ditolak
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        ShizukuHelper.init(this)
        SessionStore.init(this)
        ui = UiState(Prefs(this))
        ui.refresh(this)

        val actions = Actions(
            toggleOverlay = { toggleOverlay() },
            requestShizuku = { ShizukuHelper.requestPermission() },
            openShizukuDownload = { openUrl("https://shizuku.rikka.app/download/") },
            openShizukuApp = { openShizukuApp() },
            openDevOptions = { openDevOptions() },
            openOverlaySettings = { openOverlaySettings() },
            requestNotification = {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        )
        setContent { NexTheme { MainScreen(ui, actions) } }
    }

    override fun onResume() {
        super.onResume()
        ui.refresh(this)
        ShizukuHelper.refresh()
        ShizukuHelper.bindIfPossible()
    }

    private fun toggleOverlay() {
        if (OverlayService.running.value) {
            OverlayService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan \"Tampil di atas aplikasi lain\" dulu.")
            openOverlaySettings()
            return
        }
        val s = ShizukuHelper.state.value
        if (!s.running) {
            toast("Aktifkan Shizuku dulu (lihat panduan di atas).")
            return
        }
        if (!s.granted) {
            toast("Beri izin Shizuku ke aplikasi ini dulu.")
            ShizukuHelper.requestPermission()
            return
        }
        if (ui.cfg.apps.isEmpty()) toast("Belum ada aplikasi yang dipilih di daftar bawah.")
        if (Build.VERSION.SDK_INT >= 33 && !ui.notifGranted) {
            pendingStart = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startOverlayNow()
    }

    private fun startOverlayNow() {
        ShizukuHelper.bindIfPossible()
        OverlayService.start(this)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Tidak ada peramban untuk membuka tautan.")
        }
    }

    private fun openShizukuApp() {
        val i = packageManager.getLaunchIntentForPackage(ShizukuHelper.PACKAGE)
        if (i != null) startActivity(i) else toast("Shizuku belum terpasang.")
    }

    private fun openDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            } catch (e2: Exception) {
                toast("Buka Pengaturan > Tentang ponsel secara manual.")
            }
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            toast("Buka Pengaturan > Aplikasi > Akses khusus > Tampil di atas aplikasi lain.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
