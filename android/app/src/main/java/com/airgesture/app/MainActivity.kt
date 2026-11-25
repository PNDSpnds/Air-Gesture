package com.airgesture.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.airgesture.app.action.AirGestureAccessibilityService
import com.airgesture.app.service.AirGestureService
import android.view.animation.DecelerateInterpolator

/** 主界面：深色简约风，状态卡 + 大启动按钮 + 侧边栏「权限」，调试区只保留实时测试。 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 400L)
        }
    }

    private lateinit var serviceValue: TextView
    private lateinit var accessibilityValue: TextView
    private lateinit var gateValue: TextView
    private lateinit var gestureValue: TextView
    private lateinit var actionValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var bigButton: TextView
    private lateinit var hintView: TextView

    private lateinit var scrim: View
    private lateinit var sidebar: LinearLayout
    private var sidebarOpen = false
    private var sidebarWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        requestRuntimePermissions()
        handler.post(refreshRunnable)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 界面
    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(40))
        }
        content.addView(buildHeader())
        content.addView(space(22))
        content.addView(buildStatusCard())
        content.addView(space(20))
        content.addView(buildStartButton())
        hintView = text("首次使用：点右上角「权限」完成相机与无障碍设置", 12f, SUB)
            .apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
        content.addView(hintView)
        content.addView(space(28))
        content.addView(sectionLabel("调试命令"))
        content.addView(space(10))
        content.addView(buildDebugRow())
        content.addView(space(24))
        content.addView(buildPrivacyCard())
        content.addView(space(24))
        content.addView(
            text("捏合一次 = 单击 · 快速捏合两次 = 双击\n捏住甩动 = 上 / 下 / 左 / 右滑 · 点赞 = 双击", 11f, SUB)
                .apply { gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f) }
        )
        scroll.addView(content)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 侧边栏遮罩 + 面板
        scrim = View(this).apply {
            setBackgroundColor(0xAA000000.toInt())
            alpha = 0f
            visibility = View.INVISIBLE
            setOnClickListener { closeSidebar() }
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        sidebarWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        sidebar = buildSidebar()
        root.addView(
            sidebar,
            FrameLayout.LayoutParams(sidebarWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END),
        )
        sidebar.translationX = sidebarWidth.toFloat()
        sidebar.visibility = View.INVISIBLE
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("隔空手控", 30f, TITLE).apply { typeface = Typeface.DEFAULT_BOLD })
        titles.addView(
            text("隔空手势控制 · v1.0.0", 12f, SUB).apply { setPadding(0, dp(4), 0, 0) }
        )
        row.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val menu = text("☰   权限", 13f, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(0x1A4DE1FF, 20)
            setOnClickListener { openSidebar() }
        }
        row.addView(menu)
        return row
    }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(0xFF10222C.toInt(), 22, 0x1F4DE1FF, 1)
        }
        serviceValue = statusRow(card, "服务")
        accessibilityValue = statusRow(card, "无障碍")
        gateValue = statusRow(card, "状态")
        gestureValue = statusRow(card, "手势")
        actionValue = statusRow(card, "动作")
        fpsValue = statusRow(card, "相机帧率")
        return card
    }

    private fun statusRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(text(label, 13f, SUB), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = text("—", 14f, TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        row.addView(value)
        parent.addView(row)
        return value
    }

    private fun buildStartButton(): View {
        bigButton = text("启动手势服务", 20f, 0xFF061820.toInt()).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(22))
            background = accentGradient()
            setOnClickListener { toggleService() }
        }
        return bigButton
    }

    private fun buildPrivacyCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFF0D1C26.toInt(), 18, 0x1F4DE1FF, 1)
        }
        card.addView(text("隐私说明", 12f, ACCENT).apply { typeface = Typeface.DEFAULT_BOLD })
        card.addView(space(8))
        card.addView(
            text(
                "本应用不联网；摄像头画面只在本机内存中实时处理，不显示、不录制、不保存、不上传任何图像数据。",
                12f,
                SUB,
            ).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        )
        return card
    }

    private fun buildDebugRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFF10222C.toInt(), 18, 0x1F4DE1FF, 1)
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, TestActivity::class.java)) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("实时测试", 16f, TEXT).apply { typeface = Typeface.DEFAULT_BOLD })
        texts.addView(
            text("手部骨架 · 中文动作反馈 · 不显示画面", 11f, SUB).apply { setPadding(0, dp(4), 0, 0) }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 26f, ACCENT))
        return row
    }

    private fun buildSidebar(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(24))
            background = sideDrawable()
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            text("权限与设置", 22f, TITLE).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(text("✕", 18f, SUB).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { closeSidebar() }
        })
        panel.addView(head)
        panel.addView(space(18))
        panel.addView(divider())
        panel.addView(space(10))

        panel.addView(sideItem("相", "相机 / 通知权限", "授予相机、通知权限") {
            requestRuntimePermissions()
        })
        panel.addView(sideItem("无", "无障碍服务", "开启「隔空手控」无障碍") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        panel.addView(sideItem("限", "允许受限设置", "无障碍开关是灰色时使用") {
            openAppDetails()
        })
        panel.addView(sideItem("电", "电池优化白名单", "允许后台持续运行") {
            requestBatteryWhitelist()
        })

        panel.addView(
            View(this),
            LinearLayout.LayoutParams(1, 0, 1f),
        )
        panel.addView(divider())
        panel.addView(
            text("隔空手控 v1.0.0", 11f, SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            }
        )
        return panel
    }

    private fun sideItem(
        mark: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(10), dp(14))
            background = rounded(0x14FFFFFF, 16)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            text(mark, 14f, ACCENT).apply {
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = rounded(0x1A4DE1FF, 22)
                setPadding(dp(11), dp(11), dp(11), dp(11))
            }
        )
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(text(title, 15f, TEXT).apply { typeface = Typeface.DEFAULT_BOLD })
        texts.addView(text(subtitle, 11f, SUB).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 22f, SUB))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = dp(10)
        row.layoutParams = lp
        return row
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (t: Throwable) {
            AppState.lastError = "无法打开应用信息: ${t.message}"
        }
    }

    private fun requestBatteryWhitelist() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ 侧边栏动画
    private fun openSidebar() {
        if (sidebarOpen) return
        sidebarOpen = true
        sidebar.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        sidebar.translationX = sidebarWidth.toFloat()
        sidebar.animate()
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
        scrim.animate().alpha(1f).setDuration(260).start()
    }

    private fun closeSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        sidebar.animate()
            .translationX(sidebarWidth.toFloat())
            .setDuration(220)
            .withEndAction { sidebar.visibility = View.INVISIBLE }
            .start()
        scrim.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { scrim.visibility = View.INVISIBLE }
            .start()
    }

    override fun onBackPressed() {
        if (sidebarOpen) closeSidebar() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ 服务
    private fun toggleService() {
        if (AppState.serviceRunning) {
            stopService(Intent(this, AirGestureService::class.java))
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            return
        }
        val intent = Intent(this, AirGestureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ------------------------------------------------------------------ 状态
    private fun refreshStatus() {
        val accessibilityOn = isAccessibilityEnabled()
        val running = AppState.serviceRunning

        serviceValue.text = if (running) "● 运行中" else "● 已停止"
        serviceValue.setTextColor(if (running) 0xFF4DE1B5.toInt() else 0xFF8FA6B3.toInt())
        accessibilityValue.text = if (accessibilityOn) "已开启" else "未开启"
        accessibilityValue.setTextColor(if (accessibilityOn) 0xFF4DE1B5.toInt() else 0xFFFF8FA3.toInt())
        gateValue.text = if (running) "运行中" else "—"
        gestureValue.text = AppState.lastGesture
        actionValue.text = AppState.lastAction
        fpsValue.text = "%.1f fps".format(AppState.fps)

        if (running) {
            bigButton.text = "停止手势服务"
            bigButton.background = dangerGradient()
            bigButton.setTextColor(Color.WHITE)
        } else {
            bigButton.text = "启动手势服务"
            bigButton.background = accentGradient()
            bigButton.setTextColor(0xFF061820.toInt())
        }

        val cameraOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hintView.visibility = if (accessibilityOn && cameraOk) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceClass = AirGestureAccessibilityService::class.java.name
        val full = "$packageName/$serviceClass"
        val short = "$packageName/.${serviceClass.removePrefix("$packageName.")}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
    }

    // ------------------------------------------------------------------ 权限
    private fun requestRuntimePermissions() {
        val permissions = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    // ------------------------------------------------------------------ 小工具
    private fun text(content: String, sizeSp: Float, color: Int): TextView =
        TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
        }

    private fun sectionLabel(content: String): TextView =
        text(content, 12f, SUB).apply { letterSpacing = 0.12f }

    private fun space(valueDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(valueDp))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidthDp: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
        }

    private fun sideDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0xFF0E1E28.toInt())
        cornerRadii = floatArrayOf(
            dp(28).toFloat(), dp(28).toFloat(),  // top-left
            0f, 0f,                              // top-right
            0f, 0f,                              // bottom-right
            dp(28).toFloat(), dp(28).toFloat(),  // bottom-left
        )
        setStroke(dp(1), 0x1F4DE1FF)
    }

    private fun accentGradient() = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFF6BEFFF.toInt(), 0xFF3AA8FF.toInt()),
    ).apply {
        cornerRadius = dp(22).toFloat()
    }

    private fun dangerGradient() = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFFFF6E8A.toInt(), 0xFFFF3D66.toInt()),
    ).apply {
        cornerRadius = dp(22).toFloat()
    }

    private companion object {
        const val REQUEST_CODE_PERMISSIONS = 100
        val BG = 0xFF0A1620.toInt()
        val TEXT = 0xFFEAF2F5.toInt()
        val SUB = 0xFF8FA6B3.toInt()
        val ACCENT = 0xFF55E6FF.toInt()
        val TITLE = 0xFFF2EFE6.toInt()
    }
}





