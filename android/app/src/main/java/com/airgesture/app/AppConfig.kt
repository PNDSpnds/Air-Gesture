package com.airgesture.app

/**
 * 手势参数。
 *
 * 第一性原理下的最小手势集：
 *   - 捏合一次 = 单击；
 *   - 快速捏合两次 = 双击；
 *   - 捏住并甩动 = 上/下/左/右滑；
 *   - 竖大拇指 = 点赞（双击）；
 * 不再有激活/待机、握拳急停、张开手掌、遮镜头急停等状态与手势。
 */
object AppConfig {
    // ---- MediaPipe ----
    const val NUM_HANDS = 1
    const val MIN_HAND_DETECTION = 0.5f
    const val MIN_HAND_PRESENCE = 0.5f
    const val MIN_TRACKING = 0.5f

    // ---- 捏合拖拽（连续甩动）----
    const val PINCH_ENTER_RATIO = 0.35f
    const val PINCH_EXIT_RATIO = 0.5f
    const val PINCH_CONFIRM_FRAMES = 2
    const val PINCH_DRAG_THRESHOLD = 0.10f
    const val PINCH_DRAG_MIN_SPEED = 0.20f
    const val PINCH_GUARD_MS = 180L
    const val PINCH_GUARD_THRESHOLD_RATIO = 1.8f
    const val PINCH_REPEAT_RETURN = 0.05f
    const val PINCH_REPEAT_COOLDOWN_MS = 100L
    const val PINCH_RELEASE_FRAMES = 2

    // ---- 单击 / 双击 ----
    /** 捏合持续时间不超过该值、且期间没有甩动，才算一次“点按”。 */
    const val PINCH_TAP_MAX_MS = 500L
    /** 点按时手掌位移上限（归一化），超过视为拖动而非点按。 */
    const val PINCH_TAP_MAX_DISPLACEMENT = 0.06f
    /** 第一次点按后等待第二下的窗口；超时后落成单击。 */
    const val TAP_WINDOW_MS = 350L

    // ---- 静态手势 ----
    const val MIN_GESTURE_CONFIDENCE = 0.6f
    const val STATIC_SUPPRESS_DYNAMIC_MS = 400L
    const val THUMB_UP_HOLD_MS = 200L
    const val THUMB_UP_COOLDOWN_MS = 1500L

    // ---- 动态手势 ----
    const val DYNAMIC_COOLDOWN_MS = 500L

    // ---- 动作参数（归一化）----
    const val SWIPE_DURATION_MS = 180L
    const val TAP_DURATION_MS = 50L
    const val DOUBLE_TAP_GAP_MS = 90L
}

