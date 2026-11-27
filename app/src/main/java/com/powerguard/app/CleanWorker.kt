package com.powerguard.app

import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 定时执行清理任务。
 *
 * 为什么用 OneTimeWork 自链式续期，而不用 PeriodicWorkRequest：
 * PeriodicWorkRequest 的最小周期是 15 分钟，不允许更短；而需求是 10 分钟。
 * 因此每次执行完自己再排一个 N 分钟后的任务。
 *
 * 先续期、再干活：避免执行过程中异常导致链条永久断裂。
 */
class CleanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)

        // 1) 先续期下一次，保证链不断
        if (prefs.enabled) {
            scheduleNext(applicationContext, prefs.intervalMinutes)
        } else {
            return Result.success()
        }

        // 2) 屏幕条件
        if (prefs.onlyWhenScreenOn && !isScreenOn()) {
            record(prefs, "跳过：屏幕熄灭")
            return Result.success()
        }

        // 3) Shizuku 可用性
        if (!ShizukuShell.isReady()) {
            val msg = if (ShizukuShell.isBinderAlive())
                "跳过：Shizuku 已运行但本应用未授权"
            else
                "跳过：Shizuku 未运行（手机重启后需重新启动 Shizuku）"
            record(prefs, msg)
            return Result.success()
        }

        // 4) 建立 UserService 连接
        if (!ShizukuShell.ensureConnected(applicationContext)) {
            record(prefs, "跳过：无法连接 Shizuku UserService")
            return Result.success()
        }

        val pkg = prefs.targetPackage
        if (pkg.isBlank()) {
            record(prefs, "跳过：目标包名为空")
            return Result.success()
        }

        val sb = StringBuilder()

        // 5) 按所选动作执行
        when (prefs.action) {
            Prefs.ACTION_FREEZE -> {
                if (ShizukuShell.isSystemCritical(applicationContext, pkg)) {
                    record(prefs, "已阻止：$pkg 是系统关键组件，冻结会导致系统异常")
                    return Result.success()
                }
                val r = ShizukuShell.freeze(applicationContext, pkg)
                if (r.ok) {
                    prefs.markFrozen(pkg)
                    sb.append("已冻结 ").append(pkg)
                    sb.append("（解冻：pm enable ").append(pkg).append("）")
                } else {
                    val d = r.stderr.ifBlank { r.stdout }
                    sb.append("冻结失败: ").append(d.ifBlank { "exitCode=${r.exitCode}" })
                }
            }

            Prefs.ACTION_UNFREEZE -> {
                val r = ShizukuShell.unfreeze(applicationContext, pkg)
                if (r.ok) {
                    prefs.markUnfrozen(pkg)
                    sb.append("已解冻 ").append(pkg)
                } else {
                    val d = r.stderr.ifBlank { r.stdout }
                    sb.append("解冻失败: ").append(d.ifBlank { "exitCode=${r.exitCode}" })
                }
            }

            else -> {
                val kill = ShizukuShell.forceStop(applicationContext, pkg)
                if (kill.ok) {
                    sb.append("已结束 ").append(pkg)
                    val pidAfter = ShizukuShell.pidOf(applicationContext, pkg)
                    if (pidAfter != null) {
                        sb.append("（PID ").append(pidAfter).append(" 已被系统重新拉起）")
                    } else {
                        sb.append("（进程已终止）")
                    }
                } else {
                    val detail = kill.stderr.ifBlank { kill.stdout }
                    sb.append("结束失败: ").append(detail.ifBlank { "exitCode=${kill.exitCode}" })
                }

                // 仅 force-stop 模式支持附带清除数据
                if (prefs.clearData) {
                    val clr = ShizukuShell.clearData(applicationContext, pkg)
                    sb.append("\n")
                    if (clr.ok && clr.stdout.contains("Success")) {
                        sb.append("已清除数据")
                    } else {
                        val err = (clr.stdout + " " + clr.stderr).trim()
                        sb.append("清除数据未成功: ").append(err.ifBlank { "未知原因" })
                        if (err.contains("SecurityException") || err.contains("Failed") ||
                            err.contains("permission", ignoreCase = true)
                        ) {
                            sb.append("（系统应用禁止清除数据，属正常限制）")
                        }
                    }
                }
            }
        }

        record(prefs, sb.toString())
        return Result.success()
    }

    private fun record(prefs: Prefs, msg: String) {
        prefs.lastRunTime = System.currentTimeMillis()
        prefs.lastResult = msg
        prefs.runCount = prefs.runCount + 1
        LogStore.append(applicationContext, msg)
    }

    private fun isScreenOn(): Boolean {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    companion object {
        const val WORK_NAME = "powerguard_chain"

        /** 排下一次执行 */
        fun scheduleNext(context: Context, intervalMinutes: Int) {
            val req = OneTimeWorkRequestBuilder<CleanWorker>()
                .setInitialDelay(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        /** 立即执行一次（测试按钮） */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<CleanWorker>()
                .addTag("powerguard_immediate")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        /** 停止链条 */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
