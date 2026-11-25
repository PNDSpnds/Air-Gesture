package com.airgesture.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.airgesture.app.camera.ImageConverter
import com.airgesture.app.core.ActionIntent
import com.airgesture.app.core.Hand
import com.airgesture.app.core.Landmark
import com.airgesture.app.core.PerceptionResult
import com.airgesture.app.mapping.GestureGate
import com.airgesture.app.perception.DynamicGestureExtractor
import com.airgesture.app.perception.HandPerceiver
import com.airgesture.app.service.AirGestureService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * 实时测试页（对应 M0 阶段的 HUD）：
 *   - 不显示相机画面，只叠加 21 点手部骨架（隐私）；
 *   - 实时显示识别到的手势、触发的动作（中文：上滑/下滑/左滑/右滑/双击…）、状态、fps。
 *
 * 只做「识别 + 显示」，不执行无障碍注入，避免测试时误操作。
 * 进入本页会自动停止后台手势服务以释放摄像头。
 */
class TestActivity : Activity(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private lateinit var overlay: OverlayView

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var perceiver: HandPerceiver? = null

    private val extractor = DynamicGestureExtractor()
    private val gate = GestureGate()

    @Volatile private var imageW = 480
    @Volatile private var imageH = 640
    @Volatile private var fps = 0f

    private var frameCount = 0
    private var fpsWindowStart = 0L

    private var lastHandCount = 0
    private var lastGesture = "—"
    private var lastAction = "—"
    private var lastEvent = "—"
    private var startError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registry.currentState = Lifecycle.State.CREATED

        // 测试页自己用相机；先停掉后台服务，避免相机被占用
        if (AppState.serviceRunning) {
            stopService(android.content.Intent(this, AirGestureService::class.java))
        }

        overlay = OverlayView(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(overlay, matchParent())
        val back = TextView(this).apply {
            text = "返回"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(48, 22, 48, 22)
            setOnClickListener { finish() }
        }
        root.addView(
            back,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 64 },
        )
        setContentView(root)
        overlay.updateStatus(buildLines())
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
        mainHandler.postDelayed({ startCamera() }, 400L)
    }

    override fun onStop() {
        stopCamera()
        registry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onDestroy() {
        stopCamera()
        perceiver?.stop()
        perceiver?.close()
        perceiver = null
        analysisExecutor.shutdown()
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 相机（仅分析，不显示画面）
    private fun startCamera() {
        if (isFinishing || isDestroyed) return
        try {
            perceiver = HandPerceiver(this) { hands, ts -> onHands(hands, ts) }
        } catch (t: Throwable) {
            startError = "MediaPipe 初始化失败：${t.message}"
            overlay.updateStatus(buildLines())
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                imageAnalysis.setAnalyzer(analysisExecutor) { image -> analyzeFrame(image) }
                analysis = imageAnalysis
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis,
                )
                fpsWindowStart = SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                startError = "相机启动失败：${t.message}"
                overlay.updateStatus(buildLines())
            }
        }, mainExecutor)
    }

    private fun stopCamera() {
        try {
            analysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
            // ignore
        }
        analysis = null
        cameraProvider = null
    }

    // ------------------------------------------------------------------ 帧
    private fun analyzeFrame(image: ImageProxy) {
        val ts = SystemClock.elapsedRealtime()
        try {
            val bitmap = ImageConverter.toUprightBitmap(image, mirror = true)
            imageW = bitmap.width
            imageH = bitmap.height
            frameCount++
            val elapsed = ts - fpsWindowStart
            if (elapsed >= 1000L) {
                fps = frameCount * 1000f / elapsed
                frameCount = 0
                fpsWindowStart = ts
            }
            perceiver?.recognize(bitmap, ts)
        } catch (_: Throwable) {
            // 单帧失败忽略
        } finally {
            // CameraX 要求每个 ImageProxy 必须关闭，否则只会收到一帧、分析流停住
            image.close()
        }
    }

    // ------------------------------------------------------------------ 结果
    private fun onHands(hands: List<Hand>, ts: Long) {
        val primary = hands.maxByOrNull { it.score }
        val dynamic = extractor.update(primary, ts)
        if (dynamic.isNotEmpty()) {
            lastEvent = dynamic.joinToString("、") { eventCn(it.name) }
        }
        // 测试页不注入，用固定亮度避免每帧采样
        val result = PerceptionResult(hands, dynamic)
        val intents = gate.update(result, ts)
        intents.mapNotNull { actionCn(it) }.lastOrNull()?.let { lastAction = it }

        lastHandCount = hands.size
        lastGesture = gestureCn(primary?.gesture)
        val landmarks = hands.map { it.landmarks }
        val w = imageW
        val h = imageH
        val lines = buildLines()
        // 每个识别结果都立刻刷新（不再节流），骨架更跟手
        mainHandler.post {
            overlay.updateSkeleton(landmarks, w, h)
            overlay.updateStatus(lines)
        }
    }

    private fun buildLines(): List<String> = buildList {
        add("隔空手控 · 实时测试")
        add("手部：$lastHandCount 只    相机：${"%.0f".format(fps)} fps")
        add("手势：$lastGesture")
        add("动作：$lastAction")
        add("事件：$lastEvent")
        add("状态：运行中")
        startError?.let { add("提示：$it") }
        AppState.lastError?.takeIf { it.isNotBlank() }?.let { add("错误：$it") }
        add("隐私：画面不显示 / 不保存 / 不上传，仅本机识别")
    }

    private fun gestureCn(name: String?): String = when (name) {
        null -> "—"
        "Open_Palm" -> "张开手掌"
        "Closed_Fist" -> "握拳"
        "Thumb_Up" -> "点赞"
        "Thumb_Down" -> "点踩"
        "Victory" -> "V 字"
        "Pointing_Up" -> "食指指向"
        "ILoveYou" -> "爱你"
        "None" -> "—"
        else -> name
    }

    private fun actionCn(intent: ActionIntent): String? = when (intent) {
        is ActionIntent.Swipe -> {
            val dx = intent.toX - intent.fromX
            val dy = intent.toY - intent.fromY
            if (abs(dy) >= abs(dx)) {
                if (dy < 0) "上滑" else "下滑"
            } else {
                if (dx > 0) "右滑" else "左滑"
            }
        }
        is ActionIntent.DoubleTap -> "双击"
        is ActionIntent.Tap -> "点击"
        is ActionIntent.SystemOp -> when (intent.op) {
            "arm" -> "激活"
            "disarm" -> "待机"
            "emergency_stop" -> "急停"
            else -> intent.op
        }
    }

    private fun eventCn(name: String): String = when (name) {
        "pinch" -> "捏合"
        "pinch_swipe_up" -> "捏合上甩"
        "pinch_swipe_down" -> "捏合下甩"
        "pinch_swipe_left" -> "捏合左甩"
        "pinch_swipe_right" -> "捏合右甩"
        "swipe_up" -> "上滑"
        "swipe_down" -> "下滑"
        "swipe_left" -> "左滑"
        "swipe_right" -> "右滑"
        else -> name
    }

    // ------------------------------------------------------------------ 骨架叠加层
    private class OverlayView(context: Context) : View(context) {

        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4DE1FF.toInt()
            strokeWidth = 8f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val paintPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val paintPointEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0B2A3A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val paintPanel = Paint().apply { color = 0xB8000000.toInt() }
        private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            textSize = 28f
        }

        private var hands: List<List<Landmark>> = emptyList()
        private var imgW = 480
        private var imgH = 640
        private var lines: List<String> = emptyList()

        fun updateSkeleton(newHands: List<List<Landmark>>, imageWidth: Int, imageHeight: Int) {
            hands = newHands
            imgW = imageWidth
            imgH = imageHeight
            invalidate()
        }

        fun updateStatus(newLines: List<String>) {
            lines = newLines
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width.toFloat() / imgW, height.toFloat() / imgH)
            val offX = (width - imgW * scale) / 2f
            val offY = (height - imgH * scale) / 2f

            for (hand in hands) {
                for ((a, b) in CONNECTIONS) {
                    if (a >= hand.size || b >= hand.size) continue
                    canvas.drawLine(
                        offX + hand[a].x * imgW * scale,
                        offY + hand[a].y * imgH * scale,
                        offX + hand[b].x * imgW * scale,
                        offY + hand[b].y * imgH * scale,
                        paintLine,
                    )
                }
                for (lm in hand) {
                    val cx = offX + lm.x * imgW * scale
                    val cy = offY + lm.y * imgH * scale
                    canvas.drawCircle(cx, cy, 12f, paintPoint)
                    canvas.drawCircle(cx, cy, 12f, paintPointEdge)
                }
            }

            val pad = 28f
            val lineH = 50f
            val panelHeight = pad * 2 + lineH * lines.size
            canvas.drawRoundRect(
                RectF(pad, pad, width - pad, pad + panelHeight),
                24f,
                24f,
                paintPanel,
            )
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, pad * 2, pad * 2 + lineH * i + 16f, paintText)
            }

            val hint = "画面不上传 · 仅本地骨骼识别 · 按「返回」退出"
            canvas.drawText(
                hint,
                (width - paintHint.measureText(hint)) / 2f,
                height - 170f,
                paintHint,
            )
        }

        private companion object {
            val CONNECTIONS = arrayOf(
                intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
                intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
                intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
                intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
                intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
                intArrayOf(0, 17),
            )
        }
    }
}




