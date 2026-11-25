package com.airgesture.app.core

/**
 * 一帧的感知结果与动作契约。
 *
 * 坐标全部归一化 0~1（左上角原点）；执行端负责换成当前屏幕像素。
 */

data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

data class Hand(
    val landmarks: List<Landmark>,
    val gesture: String? = null,
    val score: Float = 0f,
) {
    /** 掌心近似：腕点 + 四指根（0/5/9/13/17）的均值。 */
    fun palmCenterX(): Float {
        if (landmarks.isEmpty()) return 0.5f
        val idx = intArrayOf(0, 5, 9, 13, 17).filter { it < landmarks.size }
        if (idx.isEmpty()) return 0.5f
        return idx.sumOf { landmarks[it].x.toDouble() }.toFloat() / idx.size
    }

    fun palmCenterY(): Float {
        if (landmarks.isEmpty()) return 0.5f
        val idx = intArrayOf(0, 5, 9, 13, 17).filter { it < landmarks.size }
        if (idx.isEmpty()) return 0.5f
        return idx.sumOf { landmarks[it].y.toDouble() }.toFloat() / idx.size
    }
}

data class DynamicGesture(val name: String, val confidence: Float = 1f)

data class PerceptionResult(
    val hands: List<Hand> = emptyList(),
    val dynamic: List<DynamicGesture> = emptyList(),
)

/** 动作契约：把手势映射成系统级触摸动作。 */
sealed class ActionIntent {
    data class Swipe(
        val fromX: Float, val fromY: Float,
        val toX: Float, val toY: Float,
        val durationMs: Long = 180L,
    ) : ActionIntent()

    data class Tap(val x: Float, val y: Float, val durationMs: Long = 50L) : ActionIntent()

    data class DoubleTap(val x: Float, val y: Float, val gapMs: Long = 90L) : ActionIntent()

    /** 门控/状态变化，不落到屏幕上。 */
    data class SystemOp(val op: String) : ActionIntent()
}
