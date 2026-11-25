package com.airgesture.app.perception

import com.airgesture.app.AppConfig
import com.airgesture.app.core.DynamicGesture
import com.airgesture.app.core.Hand
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 捏合手势状态机：
 *   - pinch              捏合确认；
 *   - pinch_swipe_*      捏住期间甩动；
 *   - pinch_tap          短促捏合后松手（没甩动），供上层区分单击/双击。
 *
 * 设计：
 *   - 捏合确认后 180ms 内阈值放大，过滤刚捏住时的误触；
 *   - 甩完回到起点附近即重新武装，可连续甩；
 *   - 松开需要连续 2 帧张开。
 */
class DynamicGestureExtractor {

    private var pinchClosed = false
    private var pinchFrames = 0
    private var pinchReleaseFrames = 0

    private var pinchOriginX = 0f
    private var pinchOriginY = 0f
    private var pinchOriginTs = 0L
    private var pinchTriggered = false
    private var pinchGuardUntil = 0L
    private var pinchNextAllowed = 0L

    fun reset() {
        pinchClosed = false
        pinchFrames = 0
        pinchReleaseFrames = 0
        pinchOriginX = 0f
        pinchOriginY = 0f
        pinchOriginTs = 0L
        pinchTriggered = false
        pinchGuardUntil = 0L
        pinchNextAllowed = 0L
    }

    fun update(hand: Hand?, timestampMs: Long): List<DynamicGesture> {
        val events = ArrayList<DynamicGesture>(2)
        if (hand == null) {
            reset()
            return events
        }

        val cx = hand.palmCenterX()
        val cy = hand.palmCenterY()
        val ratio = pinchRatio(hand)

        updatePinchState(ratio, cx, cy, timestampMs, events)
        if (pinchClosed) {
            updatePinchDrag(cx, cy, timestampMs, events)
        }
        return events
    }

    private fun updatePinchState(
        ratio: Float,
        cx: Float,
        cy: Float,
        ts: Long,
        events: MutableList<DynamicGesture>,
    ) {
        if (pinchClosed) {
            if (ratio >= AppConfig.PINCH_EXIT_RATIO) {
                pinchReleaseFrames++
                if (pinchReleaseFrames >= AppConfig.PINCH_RELEASE_FRAMES) {
                    // 没甩动、捏合时间短、位置基本没动 -> 一次“点按”
                    val heldMs = ts - pinchOriginTs
                    val moved = hypot(cx - pinchOriginX, cy - pinchOriginY)
                    if (!pinchTriggered &&
                        heldMs <= AppConfig.PINCH_TAP_MAX_MS &&
                        moved <= AppConfig.PINCH_TAP_MAX_DISPLACEMENT
                    ) {
                        events.add(DynamicGesture("pinch_tap"))
                    }
                    pinchClosed = false
                    pinchFrames = 0
                    pinchReleaseFrames = 0
                    pinchTriggered = false
                }
            } else {
                pinchReleaseFrames = 0
            }
        } else {
            if (ratio <= AppConfig.PINCH_ENTER_RATIO) {
                pinchFrames++
                if (pinchFrames >= AppConfig.PINCH_CONFIRM_FRAMES) {
                    pinchClosed = true
                    pinchFrames = 0
                    pinchReleaseFrames = 0
                    pinchOriginX = cx
                    pinchOriginY = cy
                    pinchOriginTs = ts
                    pinchTriggered = false
                    pinchGuardUntil = ts + AppConfig.PINCH_GUARD_MS
                    pinchNextAllowed = ts
                    events.add(DynamicGesture("pinch"))
                }
            } else {
                pinchFrames = 0
            }
        }
    }

    private fun updatePinchDrag(
        cx: Float,
        cy: Float,
        ts: Long,
        events: MutableList<DynamicGesture>,
    ) {
        val dx = cx - pinchOriginX
        val dy = cy - pinchOriginY
        val displacement = hypot(dx, dy)

        if (!pinchTriggered) {
            val guardActive = ts < pinchGuardUntil
            val threshold = AppConfig.PINCH_DRAG_THRESHOLD *
                if (guardActive) AppConfig.PINCH_GUARD_THRESHOLD_RATIO else 1f
            val dtMs = (ts - pinchOriginTs).coerceAtLeast(1L)
            val speed = displacement / (dtMs / 1000f)
            if (ts >= pinchNextAllowed &&
                displacement >= threshold &&
                speed >= AppConfig.PINCH_DRAG_MIN_SPEED
            ) {
                val direction = if (abs(dx) >= abs(dy)) {
                    if (dx > 0f) "right" else "left"
                } else {
                    if (dy > 0f) "down" else "up"
                }
                pinchTriggered = true
                events.add(DynamicGesture("pinch_swipe_$direction"))
            }
        } else if (displacement <= AppConfig.PINCH_REPEAT_RETURN) {
            // 回到起点附近：重新武装，允许连续甩动（不用松开手指）
            pinchTriggered = false
            pinchOriginX = cx
            pinchOriginY = cy
            pinchOriginTs = ts
            pinchNextAllowed = ts + AppConfig.PINCH_REPEAT_COOLDOWN_MS
        }
    }

    private fun pinchRatio(hand: Hand): Float {
        if (hand.landmarks.size < 21) return 1f
        val span = hypot(
            hand.landmarks[5].x - hand.landmarks[17].x,
            hand.landmarks[5].y - hand.landmarks[17].y,
        ).coerceAtLeast(1e-4f)
        val gap = hypot(
            hand.landmarks[4].x - hand.landmarks[8].x,
            hand.landmarks[4].y - hand.landmarks[8].y,
        )
        return gap / span
    }
}
