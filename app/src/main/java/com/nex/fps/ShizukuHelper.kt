package com.nex.fps

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

data class ShizukuState(
    val installed: Boolean = false,   // aplikasi Shizuku terpasang
    val running: Boolean = false,     // layanan Shizuku sudah dijalankan (Start)
    val granted: Boolean = false,     // Nex Fps Overlay sudah diberi izin
    val connected: Boolean = false,   // layanan shell sudah terhubung
    val denied: Boolean = false       // izin ditolak permanen
)

/** Pembungkus API Shizuku: status, permintaan izin, dan eksekusi perintah shell. */
object ShizukuHelper {

    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 7001

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state

    @Volatile
    private var service: IUserService? = null
    private var appContext: Context? = null
    private var binding = false
    private var initialized = false

    val isConnected: Boolean get() = service != null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refresh()
        bindIfPossible()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        refresh()
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refresh()
        bindIfPossible()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                service = IUserService.Stub.asInterface(binder)
            }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            refresh()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        val installed = try {
            ctx.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        var granted = false
        var denied = false
        if (running) {
            try {
                if (!Shizuku.isPreV11()) {
                    granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    denied = !granted && Shizuku.shouldShowRequestPermissionRationale()
                }
            } catch (e: Throwable) {
                // binder baru saja mati; status diperbarui lagi saat pulih
            }
        }
        _state.value = ShizukuState(installed, running, granted, service != null, denied)
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Throwable) {
        }
    }

    /** Menghubungkan layanan shell bila Shizuku aktif dan izin sudah diberikan. */
    fun bindIfPossible() {
        val ctx = appContext ?: return
        if (service != null || binding) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(version)
            binding = true
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            binding = false
        }
    }

    /** Menjalankan perintah shell. Panggil dari thread latar (bukan main thread). */
    fun exec(command: String): String? = try {
        service?.exec(command)
    } catch (e: Throwable) {
        null
    }
}
