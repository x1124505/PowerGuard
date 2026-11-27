package com.powerguard.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 冻结管理页：列出可冻结的应用，逐个冻结/解冻。
 *
 * 设计要点：
 * - 默认只显示用户自己安装的应用（系统应用风险高，需手动切换）
 * - 系统关键组件直接标记为「不可操作」并禁用按钮
 * - 所有操作都走 Shizuku，无需 root
 */
class FreezeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var listContainer: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var showSystemLabel: TextView

    private var showSystem = false
    private var keyword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        loadAsync()
    }

    /** 首次加载放到后台线程，避免查询冻结状态时卡住界面 */
    private fun loadAsync() {
        listContainer.removeAllViews()
        listContainer.addView(TextView(this).apply {
            text = "正在读取应用列表…"
            textSize = 13f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, dp(20), 0, 0)
        })
        Thread {
            // 预热冻结状态（一次性批量）
            val cached = if (ShizukuShell.isReady()) ShizukuShell.listFrozen(this) else emptySet()
            frozenCache = cached
            runOnUiThread { refreshList() }
        }.start()
    }

    @Volatile
    private var frozenCache: Set<String> = emptySet()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(0))
        }

        root.addView(TextView(this).apply {
            text = "冻结管理"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1A1A1A.toInt())
        })
        root.addView(TextView(this).apply {
            text = "冻结后系统不再启动该应用，比反复结束进程更彻底。解冻即可恢复。"
            textSize = 12f
            setTextColor(0xFF6B6B6B.toInt())
            setPadding(0, dp(6), 0, dp(14))
        })

        // 搜索框
        searchBox = EditText(this).apply {
            hint = "搜索应用名或包名"
            textSize = 14f
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF5F5F5.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    keyword = s?.toString()?.trim()?.lowercase() ?: ""
                    refreshList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(searchBox)

        // 显示系统应用开关
        showSystemLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                showSystem = !showSystem
                updateSystemLabel()
                refreshList()
            }
        }
        root.addView(showSystemLabel)
        updateSystemLabel()

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        setContentView(root)
    }

    private fun updateSystemLabel() {
        showSystemLabel.text = if (showSystem) "✓ 显示系统应用（谨慎操作）" else "显示系统应用"
    }

    private fun refreshList() {
        if (!this::listContainer.isInitialized) return
        listContainer.removeAllViews()

        if (!ShizukuShell.isReady()) {
            listContainer.addView(TextView(this).apply {
                text = "Shizuku 未就绪，无法查询应用状态。\n请先返回首页完成授权。"
                textSize = 13f
                setTextColor(0xFFD32F2F.toInt())
                setPadding(0, dp(20), 0, 0)
            })
            return
        }

        val pm = packageManager
        var apps: List<ApplicationInfo> = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Throwable) {
            emptyList()
        }

        // 过滤
        apps = apps.filter { info ->
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !showSystem) return@filter false

            if (keyword.isNotEmpty()) {
                val label = try {
                    pm.getApplicationLabel(info).toString().lowercase()
                } catch (e: Throwable) { "" }
                if (!label.contains(keyword) && !info.packageName.lowercase().contains(keyword)) {
                    return@filter false
                }
            }
            true
        }

        // 使用缓存的冻结集合（由 loadAsync 或操作后刷新），避免逐条调 shell
        val frozenSet = frozenCache
        prefs.frozenPackages = frozenSet

        // 已冻结的排前面
        apps = apps.sortedWith(
            compareByDescending<ApplicationInfo> { frozenSet.contains(it.packageName) }
                .thenBy {
                    try { pm.getApplicationLabel(it).toString() } catch (e: Throwable) { "" }
                }
        )

        if (apps.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "没有匹配的应用"
                textSize = 13f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(0, dp(20), 0, 0)
            })
            return
        }

        // 批量构建，避免每个 item 里再调 shell
        for (info in apps) {
            listContainer.addView(buildRow(info, frozenSet.contains(info.packageName)))
        }
    }

    private fun buildRow(info: ApplicationInfo, frozen: Boolean): LinearLayout {
        val pm = packageManager
        val pkg = info.packageName
        val label = try {
            pm.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            pkg
        }

        val critical = ShizukuShell.isSystemCritical(this, pkg)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
        })
        textCol.addView(TextView(this).apply {
            text = if (critical) "$pkg · 系统关键组件，禁止冻结" else pkg
            textSize = 10.5f
            setTextColor(if (critical) 0xFFD32F2F.toInt() else 0xFF9E9E9E.toInt())
        })
        row.addView(textCol)

        val btn = TextView(this).apply {
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            when {
                critical -> {
                    text = "不可操作"
                    setTextColor(0xFFBDBDBD.toInt())
                    setBackgroundColor(0xFFF5F5F5.toInt())
                    isEnabled = false
                }
                frozen -> {
                    text = "解冻"
                    setTextColor(0xFF1976D2.toInt())
                    setBackgroundColor(0xFFE3F2FD.toInt())
                }
                else -> {
                    text = "冻结"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFFEF5350.toInt())
                }
            }
            if (!critical) {
                setOnClickListener {
                    if (frozen) doUnfreeze(pkg, label) else confirmFreeze(pkg, label)
                }
            }
        }
        row.addView(btn)

        return row
    }

    private fun confirmFreeze(pkg: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("冻结「$label」？")
            .setMessage(
                "冻结后该应用将完全停止运行，桌面图标会消失。\n\n" +
                    "如果它是输入法、桌面、通知类应用，冻结可能导致无法正常使用手机，" +
                    "请确认不是关键应用。\n\n解冻方式：回到本页面点「解冻」。"
            )
            .setPositiveButton("冻结") { _, _ -> doFreeze(pkg, label) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doFreeze(pkg: String, label: String) {
        if (ShizukuShell.isSystemCritical(this, pkg)) {
            toast("该系统组件禁止冻结")
            return
        }
        // shell 调用是阻塞的，放到后台线程，避免卡 UI
        Thread {
            val r = ShizukuShell.freeze(this, pkg)
            val latest = if (r.ok) ShizukuShell.listFrozen(this) else frozenCache
            runOnUiThread {
                if (r.ok) {
                    frozenCache = latest
                    prefs.markFrozen(pkg)
                    LogStore.append(this, "手动冻结 $label ($pkg)")
                    toast("已冻结 $label")
                } else {
                    val d = r.stderr.ifBlank { r.stdout }
                    toast("冻结失败：${d.ifBlank { "exitCode=${r.exitCode}" }}")
                }
                refreshList()
            }
        }.start()
    }

    private fun doUnfreeze(pkg: String, label: String) {
        Thread {
            val r = ShizukuShell.unfreeze(this, pkg)
            val latest = if (r.ok) ShizukuShell.listFrozen(this) else frozenCache
            runOnUiThread {
                if (r.ok) {
                    frozenCache = latest
                    prefs.markUnfrozen(pkg)
                    LogStore.append(this, "手动解冻 $label ($pkg)")
                    toast("已解冻 $label")
                } else {
                    val d = r.stderr.ifBlank { r.stdout }
                    toast("解冻失败：${d.ifBlank { "exitCode=${r.exitCode}" }}")
                }
                refreshList()
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
