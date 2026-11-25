package com.airgesture.app.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.airgesture.app.core.ActionIntent

/**
 * 无障碍服务：系统级触摸注入（免 root，可操作任意 App）。
 * 只做 gesture 注入，不读取任何屏幕内容。
 */
class AirGestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AirGestureA11y"

        @Volatile
        var instance: AirGestureAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "accessibility destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只做注入，不监听内容
    }

    override fun onInterrupt() {
        // no-op
    }
}

/**
 * ActionIntent -> 系统动作。
 * 坐标换算使用当前显示像素（无障碍注入坐标系天然跟随屏幕旋转）。
 */
object AccessibilityExecutor {

    private const val TAG = "AirGestureExecutor"
    private val handler = Handler(Looper.getMainLooper())

    fun execute(intent: ActionIntent): Boolean {
        val service = AirGestureAccessibilityService.instance ?: return false
        return try {
            when (intent) {
                is ActionIntent.Swipe -> dispatchSwipe(service, intent)
                is ActionIntent.Tap -> dispatchTap(service, intent.x, intent.y, intent.durationMs)
                is ActionIntent.DoubleTap -> dispatchDoubleTap(service, intent.x, intent.y, intent.gapMs)
                is ActionIntent.SystemOp -> true // 只更新状态，不落屏
            }
        } catch (t: Throwable) {
            Log.w(TAG, "execute failed: $intent", t)
            false
        }
    }

    fun describe(intent: ActionIntent): String = when (intent) {
        is ActionIntent.Swipe -> {
            val vertical = kotlin.math.abs(intent.toY - intent.fromY) >=
                kotlin.math.abs(intent.toX - intent.fromX)
            if (vertical) {
                if (intent.toY < intent.fromY) "滑动↑" else "滑动↓"
            } else {
                if (intent.toX > intent.fromX) "滑动→" else "滑动←"
            }
        }
        is ActionIntent.Tap -> "点按"
        is ActionIntent.DoubleTap -> "双击"
        is ActionIntent.SystemOp -> "系统:${intent.op}"
    }

    private fun screenSize(service: AirGestureAccessibilityService): Pair<Int, Int> {
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun px(value: Float, max: Int): Float = (value.coerceIn(0f, 1f)) * (max - 1)

    private fun dispatchSwipe(service: AirGestureAccessibilityService, intent: ActionIntent.Swipe): Boolean {
        val (w, h) = screenSize(service)
        val path = Path().apply {
            moveTo(px(intent.fromX, w), px(intent.fromY, h))
            lineTo(px(intent.toX, w), px(intent.toY, h))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, intent.durationMs.coerceAtLeast(20L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun dispatchTap(service: AirGestureAccessibilityService, x: Float, y: Float, durationMs: Long): Boolean {
        val (w, h) = screenSize(service)
        val path = Path().apply { moveTo(px(x, w), px(y, h)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(40L))
        return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchDoubleTap(service: AirGestureAccessibilityService, x: Float, y: Float, gapMs: Long): Boolean {
        val (w, h) = screenSize(service)
        fun makePath() = Path().apply { moveTo(px(x, w), px(y, h)) }
        val tapDuration = 40L
        val secondStart = tapDuration + gapMs.coerceAtLeast(40L)
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(makePath(), 0L, tapDuration))
            .addStroke(GestureDescription.StrokeDescription(makePath(), secondStart, tapDuration))
        return service.dispatchGesture(builder.build(), null, null)
    }
}
