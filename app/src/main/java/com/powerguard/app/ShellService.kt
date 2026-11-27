package com.powerguard.app

import android.content.ComponentName
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku 提供的远程进程中（以 shell / root 身份）。
 *
 * 重要：这个类的代码实际在 Shizuku 的进程里执行，不是在本 App 进程里。
 * 因此这里不能引用 App 的 Context、资源或任何依赖本 App 进程状态的东西。
 */
class ShellService : IShellService.Stub() {

    companion object {
        private const val TIMEOUT_SEC = 20L
        /** 并发读取 stdout/stderr，避免其中一路缓冲区写满导致死锁 */
        private val ioPool = Executors.newCachedThreadPool()
    }

    override fun exec(command: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", command).start()

            val outFuture: Future<String> = ioPool.submit<String> {
                process.inputStream.bufferedReader().use { it.readTextSafely() }
            }
            val errFuture: Future<String> = ioPool.submit<String> {
                process.errorStream.bufferedReader().use { it.readTextSafely() }
            }

            val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                "-2||命令超时(${TIMEOUT_SEC}s)"
            } else {
                val code = process.exitValue()
                val out = safeGet(outFuture)
                val err = safeGet(errFuture)
                // 用不可见分隔符避免命令输出里含 | 导致解析错误
                "$code\u0001$out\u0001$err"
            }
        } catch (e: Throwable) {
            "-3||${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { process?.destroy() } catch (_: Throwable) {}
        }
    }

    private fun safeGet(f: Future<String>): String = try {
        f.get(3, TimeUnit.SECONDS).trim()
    } catch (e: Throwable) {
        ""
    }

    private fun BufferedReader.readTextSafely(): String = try {
        readText()
    } catch (e: Throwable) {
        ""
    }
}

/**
 * UserService 参数构造。Shizuku 用它把 IShellService 派发到远程进程。
 */
object ShellServiceArgs {

    val args: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName("com.powerguard.app", ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(1)
}
