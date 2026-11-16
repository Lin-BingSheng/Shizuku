package moe.shizuku.manager.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logd
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级看门狗引导。
 *
 * 与 [WatchdogService] 的职责划分：
 * - Service 需要 startService 才能存活，App 在后台被广播拉起时系统会拒绝，因此不能依赖它做网络触发
 * - 这里只用进程内注册的回调，进程活着就有效，不依赖任何组件生命周期
 *
 * 典型场景：开机时由外部自动化工具发送广播拉起本进程，Wi-Fi 随后才就绪，
 * 此时靠 [onAvailable] 完成 server 拉起，全程不弹出任何界面。
 */
object WatchdogBootstrap {

    private const val TAG = "WatchdogBootstrap"

    private val started = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /**
     * server 死亡时立刻尝试恢复。
     *
     * 进程被广播在后台拉起时 [WatchdogService] 起不来，这条监听只能由这里持有，
     * 否则 server 被杀后要等到下一次网络变化才有触发源。
     */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!ShizukuSettings.isWatchdogEnabled()) return@OnBinderDeadListener
        if (ShizukuSettings.isWatchdogUserStopped()) return@OnBinderDeadListener
        logd(TAG, "binder dead, try to recover")
        triggerRecovery()
    }

    /** 拿到 binder 之后才注册死亡监听，注册前没有 proxy 可监听。 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    /**
     * 幂等。进程内首次调用时注册网络监听并触发一次恢复尝试。
     */
    fun ensure(context: Context) {
        if (!ShizukuSettings.isWatchdogEnabled()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return

        registerNetworkCallback()
        registerWirelessAdbObserver()
        registerUserPresentReceiver()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        triggerRecovery()
    }

    /**
     * 监听无线调试开关。
     *
     * 部分 ROM 会在开机后把 adb_wifi_enabled 重置为 0，且拒绝程序化写入，只能由用户手动打开。
     * 用户打开的那一刻是唯一确定的恢复时机，不监听它就会一直停在"进程在、端口没起来"的状态。
     */
    private fun registerWirelessAdbObserver() {
        val context = appContext ?: return
        try {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_WIFI_ENABLED), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        logd(TAG, "adb_wifi_enabled changed, try to recover")
                        triggerRecovery()
                    }
                }
            )
        } catch (t: Throwable) {
            logd(TAG, "register settings observer failed: $t")
        }
    }

    /** 解锁也是一个明确的时机：锁屏期间无线调试往往不可用，解锁后才真正开始监听。 */
    private fun registerUserPresentReceiver() {
        val context = appContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                logd(TAG, "user present, try to recover")
                triggerRecovery()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } catch (t: Throwable) {
            logd(TAG, "register user present receiver failed: $t")
        }
    }

    private fun registerNetworkCallback() {
        val context = appContext ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        logd(TAG, "wifi available, try to recover")
                        triggerRecovery()
                    }
                })
        } catch (t: Throwable) {
            logd(TAG, "register network callback failed: $t")
        }
    }

    /**
     * 先立即试一次，失败按指数退避再试。
     *
     * 广播拉起进程时 Wi-Fi 与 adbd 可能都还没就绪，只试一次会在端口出现前就放弃。
     * 这里最多观察约 70 秒，仍远低于后台进程被回收前的典型存活时间。
     */
    private fun triggerRecovery() {
        val context = appContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            WatchdogCore.ensureServer(context)

            var interval = 10_000L
            repeat(3) {
                delay(interval)
                if (Shizuku.pingBinder()) return@launch
                if (!ShizukuSettings.isWatchdogEnabled()) return@launch
                if (ShizukuSettings.isWatchdogUserStopped()) return@launch
                WatchdogCore.ensureServer(context)
                interval *= 2
            }
        }
    }
}
