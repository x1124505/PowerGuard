package com.powerguard.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简易滚动日志，只保留最近 N 条，避免长期运行把存储写满。
 */
object LogStore {

    private const val FILE_NAME = "powerguard.log"
    private const val MAX_LINES = 300

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun append(context: Context, message: String) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            val line = "${fmt.format(Date())}  $message"
            f.appendText(line + "\n")

            // 超限时裁剪
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {
            // 日志失败不影响主流程
        }
    }

    @Synchronized
    fun readAll(context: Context): String {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) "" else f.readText()
        } catch (_: Throwable) {
            ""
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
