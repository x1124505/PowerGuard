package com.powerguard.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储。全部走 SharedPreferences，无需数据库。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("powerguard", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_INTERVAL_MIN = 10
        const val MIN_INTERVAL_MIN = 5
        const val DEFAULT_PACKAGE = "com.miui.powerkeeper"

        const val ACTION_FORCE_STOP = 0
        const val ACTION_FREEZE = 1
        const val ACTION_UNFREEZE = 2

        /** 禁止冻结的包名 —— 冻结这些会导致系统异常 */
        val PROTECTED_PACKAGES = setOf(
            "com.miui.powerkeeper",      // 电量和系统：全系统后台管控，官方文档明确其职责
            "com.miui.securitycenter",   // 手机管家
            "com.android.systemui",      // 状态栏
            "android",                   // 系统框架
            "com.miui.home",             // 桌面
            "com.lbe.security.miui",     // 权限管理
            "com.xiaomi.xmsf",           // 小米推送/系统服务
            "miui.systemui.plugin"
        )
    }

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 间隔分钟数，10 表示每 10 分钟 */
    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MIN).coerceAtLeast(MIN_INTERVAL_MIN)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(MIN_INTERVAL_MIN)).apply()

    /** 目标包名，默认《电量和系统》 */
    var targetPackage: String
        get() = sp.getString(KEY_PACKAGE, DEFAULT_PACKAGE) ?: DEFAULT_PACKAGE
        set(v) = sp.edit().putString(KEY_PACKAGE, v.trim()).apply()

    /**
     * 是否同时清除数据。
     * 默认 false —— 对 com.miui.powerkeeper 清除数据会重置 HyperOS 的省电学习模型，
     * 前几天续航反而变差，因此默认只做 force-stop。
     */
    var clearData: Boolean
        get() = sp.getBoolean(KEY_CLEAR, false)
        set(v) = sp.edit().putBoolean(KEY_CLEAR, v).apply()

    /** 只在屏幕点亮时执行（避免灭屏时频繁唤醒，更省电） */
    var onlyWhenScreenOn: Boolean
        get() = sp.getBoolean(KEY_SCREEN_ON, false)
        set(v) = sp.edit().putBoolean(KEY_SCREEN_ON, v).apply()

    /**
     * 执行动作：
     * 0 = force-stop（结束运行）
     * 1 = freeze（冻结，pm disable-user）
     * 2 = unfreeze（解冻，pm enable）
     */
    var action: Int
        get() = sp.getInt(KEY_ACTION, ACTION_FORCE_STOP)
        set(v) = sp.edit().putInt(KEY_ACTION, v).apply()

    /** 被冻结的包名集合，用于「解冻全部」和防止误操作 */
    var frozenPackages: Set<String>
        get() = sp.getStringSet(KEY_FROZEN, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_FROZEN, v).apply()

    fun markFrozen(pkg: String) = frozenPackages.let { set -> frozenPackages = set + pkg }
    fun markUnfrozen(pkg: String) = frozenPackages.let { set -> frozenPackages = set - pkg }

    var runCount: Int
        get() = sp.getInt(KEY_RUN_COUNT, 0)
        set(v) = sp.edit().putInt(KEY_RUN_COUNT, v).apply()

    var lastRunTime: Long
        get() = sp.getLong(KEY_LAST_RUN, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_RUN, v).apply()

    var lastResult: String
        get() = sp.getString(KEY_LAST_RESULT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_RESULT, v).apply()


    private companion object Keys {
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL = "interval_min"
        const val KEY_PACKAGE = "target_package"
        const val KEY_CLEAR = "clear_data"
        const val KEY_SCREEN_ON = "only_screen_on"
        const val KEY_ACTION = "action"
        const val KEY_FROZEN = "frozen_packages"
        const val KEY_RUN_COUNT = "run_count"
        const val KEY_LAST_RUN = "last_run"
        const val KEY_LAST_RESULT = "last_result"
    }
}
