package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.watchdog.WatchdogCore

/**
 * 处理 BOOT_COMPLETED 的接收器，刻意不声明 directBootAware。
 *
 * BootCompleteReceiver 是 directBootAware 的，会在 LOCKED_BOOT_COMPLETED 阶段被拉起，
 * 此时凭据存储与网络均不可用，ADB 模式的启动必然失败，部分 ROM 之后也不再投递
 * BOOT_COMPLETED。这里用非 direct boot 的组件单独接管 ADB 模式的开机启动。
 */
class AdbBootReceiver : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        if (ShizukuSettings.getLastLaunchMode() != LaunchMethod.ADB) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WatchdogCore.ensureServer(context, ignoreSwitch = true)
            } finally {
                pending.finish()
            }
        }
    }
}
