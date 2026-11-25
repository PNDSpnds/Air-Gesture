package com.airgesture.app.mapping

import com.airgesture.app.AppConfig
import com.airgesture.app.core.ActionIntent
import com.airgesture.app.core.DynamicGesture
import com.airgesture.app.core.PerceptionResult

/**
 * 手势映射（第一性原理版）：
 *   - 竖大拇指保持 200ms -> 点赞（双击屏幕中心）；
 *   - 捏合一次 -> 单击（延迟一个窗口确认不是双击）；
 *   - 快速捏合两次 -> 双击；
 *   - 捏住甩动 -> 上/下/左/右滑。
 *
 * 不再有激活/待机/急停状态：服务启动即生效，停止服务由通知或主界面完成。
 */
class GestureGate {

    private class StaticRule(
        val holdMs: Long,
        val cooldownMs: Long,
        val build: () -> ActionIntent,
    )

    private val staticRules: Map<String, StaticRule> = mapOf(
        "Thumb_Up" to StaticRule(AppConfig.THUMB_UP_HOLD_MS, AppConfig.THUMB_UP_COOLDOWN_MS) {
            ActionIntent.DoubleTap(0.5f, 0.5f, AppConfig.DOUBLE_TAP_GAP_MS)
        },
    )

    // 静态手势去抖/锁存
    private var candidate: String? = null
    private var candidateSince = 0L
    private var consumed: String? = null
    private var consumedReleaseSince: Long? = null
    private var cooldownUntil = 0L
    private var lastStaticActionMs = Long.MIN_VALUE / 4

    // 动态手势冷却
    private var dynamicCooldownUntil = 0L

    // 单击等待窗口（用于区分双击）
    private var pendingTapAt = 0L
    private var tapWindowUntil = 0L

    fun reset() {
        candidate = null
        candidateSince = 0L
        consumed = null
        consumedReleaseSince = null
        cooldownUntil = 0L
        dynamicCooldownUntil = 0L
        lastStaticActionMs = Long.MIN_VALUE / 4
        pendingTapAt = 0L
        tapWindowUntil = 0L
    }

    fun update(result: PerceptionResult, now: Long): List<ActionIntent> {
        val intents = ArrayList<ActionIntent>(2)

        // 1) 单击窗口到期：真正落一次单击
        if (pendingTapAt > 0L && now >= pendingTapAt) {
            pendingTapAt = 0L
            tapWindowUntil = 0L
            intents.add(ActionIntent.Tap(0.5f, 0.5f, AppConfig.TAP_DURATION_MS))
        }

        // 2) 静态手势（点赞）
        val observed = observe(result)
        handleStatic(observed, now)?.let { intents.add(it) }

        // 3) 动态手势（点按/滑动）
        if (now - lastStaticActionMs < AppConfig.STATIC_SUPPRESS_DYNAMIC_MS) {
            return intents
        }
        for (event in result.dynamic) {
            when (event.name) {
                "pinch_tap" -> intents.addAll(handlePinchTap(now))
                else -> handleSwipe(event, now)?.let {
                    // 一旦发生滑动，取消尚未落地的单击
                    pendingTapAt = 0L
                    tapWindowUntil = 0L
                    intents.add(it)
                }
            }
        }
        return intents
    }

    // ------------------------------------------------------------------
    private fun observe(result: PerceptionResult): String? {
        var name: String? = null
        var best = 0f
        for (hand in result.hands) {
            val gesture = hand.gesture ?: continue
            if (hand.score >= AppConfig.MIN_GESTURE_CONFIDENCE && hand.score > best) {
                name = gesture
                best = hand.score
            }
        }
        return name
    }

    private fun handleStatic(current: String?, now: Long): ActionIntent? {
        expireConsumed(current, now)
        if (current == null) {
            candidate = null
            return null
        }
        if (consumed == current) return null
        val rule = staticRules[current]
        if (rule == null) {
            candidate = null
            return null
        }
        if (candidate != current) {
            candidate = current
            candidateSince = now
        }
        if (now - candidateSince < rule.holdMs) return null
        if (now < cooldownUntil) return null

        cooldownUntil = now + rule.cooldownMs
        consumed = current
        consumedReleaseSince = null
        candidate = null
        lastStaticActionMs = now
        return rule.build()
    }

    private fun handlePinchTap(now: Long): List<ActionIntent> {
        if (tapWindowUntil > 0L && now <= tapWindowUntil) {
            // 窗口内第二下：双击，不再落单击
            pendingTapAt = 0L
            tapWindowUntil = 0L
            if (now < dynamicCooldownUntil) return emptyList()
            dynamicCooldownUntil = now + AppConfig.DYNAMIC_COOLDOWN_MS
            return listOf(ActionIntent.DoubleTap(0.5f, 0.5f, AppConfig.DOUBLE_TAP_GAP_MS))
        }
        // 第一下：挂起，等窗口结束仍是单下再落单击
        pendingTapAt = now + AppConfig.TAP_WINDOW_MS
        tapWindowUntil = pendingTapAt
        return emptyList()
    }

    private fun handleSwipe(event: DynamicGesture, now: Long): ActionIntent? {
        if (now < dynamicCooldownUntil) return null
        val intent = dynamicIntent(event.name) ?: return null
        dynamicCooldownUntil = now + AppConfig.DYNAMIC_COOLDOWN_MS
        return intent
    }

    private fun dynamicIntent(name: String): ActionIntent? = when (name) {
        "pinch_swipe_up" -> ActionIntent.Swipe(
            0.5f, 0.72f, 0.5f, 0.30f, AppConfig.SWIPE_DURATION_MS
        )
        "pinch_swipe_down" -> ActionIntent.Swipe(
            0.5f, 0.30f, 0.5f, 0.72f, AppConfig.SWIPE_DURATION_MS
        )
        "pinch_swipe_left" -> ActionIntent.Swipe(
            0.8f, 0.5f, 0.2f, 0.5f, AppConfig.SWIPE_DURATION_MS
        )
        "pinch_swipe_right" -> ActionIntent.Swipe(
            0.2f, 0.5f, 0.8f, 0.5f, AppConfig.SWIPE_DURATION_MS
        )
        else -> null
    }

    private fun expireConsumed(current: String?, now: Long) {
        val consumedGesture = consumed ?: return
        if (current == consumedGesture) {
            consumedReleaseSince = null
            return
        }
        val since = consumedReleaseSince
        if (since == null) {
            consumedReleaseSince = now
        } else if (now - since >= RELEASE_MS) {
            consumed = null
            consumedReleaseSince = null
        }
    }

    private companion object {
        const val RELEASE_MS = 200L
    }
}
