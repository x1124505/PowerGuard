package com.powerguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后如果之前是启用状态，自动续上定时链。
 * 注意：Shizuku 重启后会停止，任务仍会跑但会被跳过，需用户手动重启 Shizuku。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = Prefs(context)
        if (prefs.enabled) {
            CleanWorker.scheduleNext(context, prefs.intervalMinutes)
            LogStore.append(context, "开机/更新后恢复定时，间隔 ${prefs.intervalMinutes} 分钟")
        }
    }
}
