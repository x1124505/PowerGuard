package com.powerguard.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 Shizuku UserService 以 shell 身份执行命令。
 *
 * 为什么用 UserService 而不是 Shizuku.newProcess：
 * newProcess 已被标记废弃且在新版 API 中不再公开，官方推荐路径是 bindUserService。
 *
 * 工作方式：
 *   1. Shizuku 在它自己的特权进程里启动 ShellService
 *   2. 我们通过 AIDL 调它的 exec()
 *   3. 该进程是 shell 身份，所以 am force-stop 能作用于其他应用
 */
object ShizukuShell {

    private const val CONNECT_TIMEOUT_SEC = 15L

    @Volatile
    private var service: IShellService? = null

    private val serviceLock = Any()

    /** Shizuku 服务存活且本应用已授权 */
    fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    // ---------------------------------------------------------------- 连接管理

    private var connection: ServiceConnection? = null

    /**
     * 绑定 UserService。已绑定则直接返回。
     * @return 是否成功拿到服务
     */
    fun ensureConnected(context: Context): Boolean {
        if (!isReady()) return false
        service?.let { return true }

        synchronized(serviceLock) {
            service?.let { return true }

            val latch = CountDownLatch(1)
            val error = AtomicReference<String?>(null)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = if (binder != null && binder.pingBinder()) {
                        IShellService.Stub.asInterface(binder)
                    } else null
                    if (service == null) error.set("binder 为空")
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            connection = conn

            return try {
                Shizuku.bindUserService(ShellServiceArgs.args, conn)
                val ok = latch.await(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!ok) {
                    error.set("连接超时")
                    false
                } else {
                    service != null
                }
            } catch (e: Throwable) {
                error.set("${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    fun disconnect() {
        val conn = connection ?: return
        try {
            Shizuku.unbindUserService(ShellServiceArgs.args, conn, true)
        } catch (_: Throwable) {
        }
        service = null
        connection = null
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 执行命令。内部会自动确保连接；连接失败会返回明确错误。
     * @param ctx 用于绑定服务，可传 applicationContext
     */
    fun exec(ctx: Context, command: String, timeoutSec: Long = 20): Result {
        if (!isReady()) {
            return Result(-1, "", "Shizuku 未运行或本应用未获授权")
        }
        if (!ensureConnected(ctx)) {
            return Result(-2, "", "无法连接 Shizuku UserService（请重启 Shizuku 后重试）")
        }

        val svc = service ?: return Result(-2, "", "UserService 为空")

        return try {
            val raw = svc.exec(command)
            // 返回格式: "exitCode\u0001stdout\u0001stderr"
            val parts = raw.split('\u0001')
            if (parts.size < 3) {
                Result(-4, "", "返回格式异常: $raw")
            } else {
                val code = parts[0].trim().toIntOrNull() ?: -4
                // stderr 可能含分隔符，取剩余部分拼接
                val out = parts[1]
                val err = parts.drop(2).joinToString("\u0001")
                Result(code, out, err)
            }
        } catch (e: Throwable) {
            // 进程可能被杀，重置连接供下次重连
            service = null
            Result(-5, "", "调用失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- 具体动作

    fun packageExists(ctx: Context, pkg: String): Boolean {
        val r = exec(ctx, "pm list packages $pkg")
        return r.ok && r.stdout.lineSequence().any { it.trim() == "package:$pkg" }
    }

    /** 结束应用运行 */
    fun forceStop(ctx: Context, pkg: String): Result = exec(ctx, "am force-stop $pkg")

    /**
     * 清除应用数据。
     * 对 com.miui.powerkeeper 这类受保护系统应用，通常会被拒绝，
     * 返回 Failed / SecurityException 属系统限制。
     */
    fun clearData(ctx: Context, pkg: String): Result = exec(ctx, "pm clear $pkg")

    /** 查询进程 PID */
    fun pidOf(ctx: Context, pkg: String): String? {
        val r = exec(ctx, "pidof $pkg")
        return if (r.ok && r.stdout.isNotBlank()) r.stdout.trim() else null
    }

    /** 确认当前 shell 身份（调试用，返回 uid） */
    fun shellUid(ctx: Context): String? {
        val r = exec(ctx, "id -u")
        return if (r.ok) r.stdout.trim() else null
    }

    // ---------------------------------------------------------------- 冻结相关

    /**
     * 冻结应用（设为「已停用」）。
     * pm disable-user 会让系统完全不再启动该应用，比 force-stop 彻底得多。
     * 恢复需要 pm enable。
     */
    fun freeze(ctx: Context, pkg: String): Result = exec(ctx, "pm disable-user --user 0 $pkg")

    /** 解冻应用 */
    fun unfreeze(ctx: Context, pkg: String): Result = exec(ctx, "pm enable $pkg")

    /**
     * 批量查询所有已停用的包。
     * 列表页逐条调用会极慢（每个应用一次 shell），所以必须一次拿全。
     */
    fun listFrozen(ctx: Context): Set<String> {
        val r = exec(ctx, "pm list packages -d")
        if (!r.ok) return emptySet()
        return r.stdout.lineSequence()
            .mapNotNull { line ->
                val t = line.trim()
                if (t.startsWith("package:")) t.removePrefix("package:") else null
            }
            .toSet()
    }

    /**
     * 应用是否被系统列为「受保护」，即不可安全停用。
     */
    fun isSystemCritical(ctx: Context, pkg: String): Boolean {
        if (Prefs.PROTECTED_PACKAGES.contains(pkg)) return true
        return pkg == "android" || pkg.startsWith("com.android.systemui")
    }

}
