package com.powerguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusCard: TextView
    private lateinit var statusDetail: TextView
    private lateinit var switchEnabled: Switch
    private lateinit var switchClear: Switch
    private lateinit var switchScreenOn: Switch
    private lateinit var intervalValue: TextView
    private lateinit var packageValue: TextView
    private lateinit var actionValue: TextView
    private lateinit var statsValue: TextView
    private lateinit var resultValue: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Shizuku 权限请求回调
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ_CODE) {
                runOnUiThread {
                    refreshStatus()
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        toast("已获得 Shizuku 授权")
                    } else {
                        toast("授权被拒绝，功能无法工作")
                    }
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 1001
        private const val NOTIFY_REQ_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        buildUi()
        requestNotificationPermissionIfNeeded()
        refreshStatus()

        // 每秒刷新一次状态，便于观察 Shizuku 变化
        handler.post(object : Runnable {
            override fun run() {
                refreshStatus()
                handler.postDelayed(this, 2000)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ UI 构建

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)

        // 标题
        root.addView(TextView(this).apply {
            text = "PowerGuard"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1A1A1A.toInt())
        })
        root.addView(TextView(this).apply {
            text = "通过 Shizuku 定时结束《电量和系统》的运行"
            textSize = 13f
            setTextColor(0xFF6B6B6B.toInt())
            setPadding(0, dp(4), 0, dp(20))
        })

        // ---- 状态卡
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusCard = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusDetail = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B6B6B.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        statusBox.addView(statusCard)
        statusBox.addView(statusDetail)
        root.addView(statusBox)

        // Shizuku 未就绪时显示的引导按钮
        root.addView(button("检查 / 授权 Shizuku") { handleShizukuTap() })
        root.addView(button("打开 Shizuku 应用") {
            openApp("moe.shizuku.privileged.api")
        })

        // ---- 开关区
        root.addView(sectionTitle("运行设置"))

        switchEnabled = addSwitch(root, "启用定时清理", prefs.enabled) { checked ->
            prefs.enabled = checked
            if (checked) {
                CleanWorker.scheduleNext(this, prefs.intervalMinutes)
                toast("已启用，${prefs.intervalMinutes} 分钟后首次执行")
            } else {
                CleanWorker.cancelAll(this)
                toast("已停止")
            }
            refreshStatus()
        }

        // 间隔
        intervalValue = addValueRow(root, "执行间隔", "${prefs.intervalMinutes} 分钟") {
            showIntervalDialog()
        }

        // 目标应用
        packageValue = addValueRow(root, "目标应用", prefs.targetPackage) {
            showPackageDialog()
        }

        // 定时动作
        actionValue = addValueRow(root, "定时动作", actionLabel(prefs.action)) {
            showActionDialog()
        }

        // 用可被 handleClearToggle 复用的稳定监听器
        val clearSwitch = addSwitch(root, "同时清除数据（不推荐）", prefs.clearData) { checked ->
            handleClearToggle(checked)
        }
        switchClear = clearSwitch

        switchScreenOn = addSwitch(root, "仅在屏幕点亮时执行", prefs.onlyWhenScreenOn) { checked ->
            prefs.onlyWhenScreenOn = checked
            refreshStatus()
        }

        // ---- 操作区
        root.addView(sectionTitle("操作"))
        root.addView(button("冻结管理（推荐）") {
            startActivity(Intent(this, FreezeActivity::class.java))
        })
        root.addView(button("立即执行一次") {
            if (!ShizukuShell.isReady()) {
                toast("请先完成 Shizuku 授权")
            } else {
                CleanWorker.runNow(this)
                toast("已触发，3 秒后查看结果")
                handler.postDelayed({ refreshStatus() }, 3000)
            }
        })
        root.addView(button("查看日志") { showLogDialog() })

        // ---- 统计
        root.addView(sectionTitle("统计"))
        statsValue = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        resultValue = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(statsValue)
        root.addView(resultValue)

        // ---- 提示
        root.addView(TextView(this).apply {
            text = "提示：手机重启后 Shizuku 会停止，需要重新启动 Shizuku，" +
                "否则定时任务会跳过执行（日志中会记录）。" +
                "建议在系统设置中把本应用与 Shizuku 都设为「无限制」后台。"
            textSize = 11f
            setTextColor(0xFF9E9E9E.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        setContentView(scroll)
    }

    // ------------------------------------------------------------------ 状态刷新

    /** 清除数据开关的状态变更处理（供取消时的回退复用） */
    private fun handleClearToggle(checked: Boolean) {
        if (checked) {
            AlertDialog.Builder(this)
                .setTitle("确认开启？")
                .setMessage(
                    "清除《电量和系统》的数据会让 HyperOS 重新学习你的使用习惯，" +
                        "接下来几天续航可能更差、后台管控变混乱。\n\n" +
                        "如果目的是解决卡顿或异常耗电，只做「结束运行」通常就够了。\n\n" +
                        "另外：该系统应用通常禁止第三方清除数据，开启后很可能不生效。"
                )
                .setPositiveButton("仍然开启") { _, _ ->
                    prefs.clearData = true
                    refreshStatus()
                }
                .setNegativeButton("取消") { _, _ ->
                    resetClearSwitch()
                }
                .setOnCancelListener { resetClearSwitch() }
                .show()
        } else {
            prefs.clearData = false
            refreshStatus()
        }
    }

    /** 把开关复位到 false，且不触发回调 */
    private fun resetClearSwitch() {
        if (!this::switchClear.isInitialized) return
        switchClear.setOnCheckedChangeListener(null)
        switchClear.isChecked = false
        switchClear.setOnCheckedChangeListener { _, v -> handleClearToggle(v) }
    }

    private fun refreshStatus() {
        val binderAlive = ShizukuShell.isBinderAlive()
        val granted = try {
            binderAlive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

        when {
            !binderAlive -> {
                statusCard.text = "● Shizuku 未运行"
                statusCard.setTextColor(0xFFD32F2F.toInt())
                statusDetail.text = "请先启动 Shizuku（无线调试方式），再回到本页授权"
            }
            !granted -> {
                statusCard.text = "● Shizuku 已运行，未授权"
                statusCard.setTextColor(0xFFF57C00.toInt())
                statusDetail.text = "点击下方按钮发起授权请求"
            }
            prefs.enabled -> {
                statusCard.text = "● 运行中"
                statusCard.setTextColor(0xFF2E7D32.toInt())
                statusDetail.text =
                    "每 ${prefs.intervalMinutes} 分钟对 ${prefs.targetPackage} 执行「${actionLabel(prefs.action)}」"
            }
            else -> {
                statusCard.text = "● 就绪，未启用"
                statusCard.setTextColor(0xFF2E7D32.toInt())
                statusDetail.text = "权限正常，打开上方开关即可开始"
            }
        }

        // 开关状态回填（避免用户直接改 prefs 后 UI 不同步）
        // 注意：用 ::xxx.isInitialized 保护，refreshStatus 可能在 buildUi 之前被调用
        if (this::switchEnabled.isInitialized && switchEnabled.isChecked != prefs.enabled) {
            switchEnabled.isChecked = prefs.enabled
        }
        if (this::switchClear.isInitialized && switchClear.isChecked != prefs.clearData) {
            switchClear.isChecked = prefs.clearData
        }
        if (this::switchScreenOn.isInitialized &&
            switchScreenOn.isChecked != prefs.onlyWhenScreenOn
        ) {
            switchScreenOn.isChecked = prefs.onlyWhenScreenOn
        }

        intervalValue.text = "${prefs.intervalMinutes} 分钟"
        packageValue.text = prefs.targetPackage
        if (this::actionValue.isInitialized) {
            actionValue.text = actionLabel(prefs.action)
        }

        val last = prefs.lastRunTime
        val timeStr = if (last == 0L) "从未执行" else {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(last))
        }
        statsValue.text = "累计执行 ${prefs.runCount} 次 · 上次 $timeStr"

        // 已冻结应用数量（本地记录，列表页会同步真实状态）
        val frozenCount = prefs.frozenPackages.size
        if (frozenCount > 0) {
            statsValue.append("\n当前已冻结 $frozenCount 个应用")
        }

        val r = prefs.lastResult
        resultValue.text = if (r.isBlank()) "" else "上次结果：$r"
        resultValue.visibility = if (r.isBlank()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ Shizuku 授权

    private fun handleShizukuTap() {
        if (!ShizukuShell.isBinderAlive()) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku 未运行")
                .setMessage(
                    "请按以下步骤启动 Shizuku：\n\n" +
                        "1. 系统设置 → 我的设备 → 全部参数 → 连点「OS 版本」开启开发者选项\n" +
                        "2. 开发者选项 → 开启「USB 调试」\n" +
                        "   ⚠️ 小米/HyperOS 必须同时开启「USB 调试（安全设置）」，" +
                        "否则 Shizuku 权限会受限\n" +
                        "3. 开发者选项 → 开启「无线调试」\n" +
                        "4. 打开 Shizuku → 通过无线调试启动 → 按提示配对\n" +
                        "5. 启动成功后回到本页点「检查 / 授权」\n\n" +
                        "另外建议在系统设置的「通知管理」中，把通知样式改为「原生样式」，" +
                        "否则填写配对码可能失败。"
                )
                .setPositiveButton("打开 Shizuku") { _, _ ->
                    openApp("moe.shizuku.privileged.api")
                }
                .setNeutralButton("关闭", null)
                .show()
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

        if (granted) {
            toast("已授权，权限正常")
            refreshStatus()
            return
        }

        try {
            Shizuku.requestPermission(SHIZUKU_REQ_CODE)
        } catch (e: Throwable) {
            toast("请求授权失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------ 对话框

    private fun actionLabel(action: Int): String = when (action) {
        Prefs.ACTION_FREEZE -> "冻结应用"
        Prefs.ACTION_UNFREEZE -> "解冻应用"
        else -> "结束运行"
    }

    private fun showActionDialog() {
        val options = arrayOf(
            "结束运行（force-stop，系统可能重新拉起）",
            "冻结应用（彻底停止，需手动解冻）",
            "解冻应用（恢复冻结的指定应用）"
        )
        AlertDialog.Builder(this)
            .setTitle("定时动作")
            .setSingleChoiceItems(options, prefs.action) { dialog, which ->
                prefs.action = which
                if (which == Prefs.ACTION_FREEZE &&
                    Prefs.PROTECTED_PACKAGES.contains(prefs.targetPackage)
                ) {
                    AlertDialog.Builder(this)
                        .setTitle("不建议冻结该系统应用")
                        .setMessage(
                            "「${prefs.targetPackage}」是系统关键组件。\n\n" +
                                "以《电量和系统》(com.miui.powerkeeper) 为例，它的职责是管控所有应用的后台行为。" +
                                "冻结它等于关闭全系统的省电管控，会导致整体耗电上升、省电设置异常。\n\n" +
                                "建议改用「冻结管理」，冻结那个真正在后台乱唤醒的第三方应用。"
                        )
                        .setPositiveButton("我知道了", null)
                        .show()
                }
                refreshStatus()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showIntervalDialog() {
        val options = arrayOf("5 分钟", "10 分钟", "15 分钟", "30 分钟", "60 分钟")
        val values = intArrayOf(5, 10, 15, 30, 60)
        val idx = values.indexOf(prefs.intervalMinutes).let { if (it < 0) 1 else it }

        AlertDialog.Builder(this)
            .setTitle("执行间隔")
            .setSingleChoiceItems(options, idx) { dialog, which ->
                prefs.intervalMinutes = values[which]
                if (prefs.enabled) {
                    CleanWorker.scheduleNext(this, prefs.intervalMinutes)
                }
                refreshStatus()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPackageDialog() {
        val input = EditText(this).apply {
            setText(prefs.targetPackage)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("目标应用包名")
            .setMessage("《电量和系统》为 com.miui.powerkeeper")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isBlank()) {
                    toast("包名不能为空")
                } else {
                    prefs.targetPackage = v
                    refreshStatus()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLogDialog() {
        val log = LogStore.readAll(this).ifBlank { "暂无日志" }
        val tv = TextView(this).apply {
            text = log
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val sv = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                LogStore.clear(this)
                toast("已清空")
            }
            .show()
    }

    // ------------------------------------------------------------------ 小工具

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xFF9E9E9E.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun addValueRow(
        parent: LinearLayout,
        label: String,
        value: String,
        onClick: () -> Unit
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val v = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(0xFF1976D2.toInt())
        }
        row.addView(v)
        parent.addView(row)
        return v
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun openApp(pkg: String) {
        try {
            startActivity(packageManager.getLaunchIntentForPackage(pkg))
        } catch (e: Throwable) {
            toast("未安装该应用")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFY_REQ_CODE
                )
            }
        }
    }
}
