package com.airgesture.app.mapping

import com.airgesture.app.core.ActionIntent
import com.airgesture.app.core.DynamicGesture
import com.airgesture.app.core.Hand
import com.airgesture.app.core.Landmark
import com.airgesture.app.core.PerceptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势映射测试（第一性原理版）：
 * 点赞、捏合单击/双击、四向滑动、冷却与静态后抑制。
 */
class GestureGateTest {

    private fun hand(gesture: String?, score: Float = 0.95f): Hand {
        val lm = MutableList(21) { Landmark(0.5f, 0.5f, 0f) }
        return Hand(lm, gesture, score)
    }

    private fun result(vararg hands: Hand): PerceptionResult =
        PerceptionResult(hands.toList(), emptyList())

    private fun dynamic(name: String): PerceptionResult =
        PerceptionResult(emptyList(), listOf(DynamicGesture(name)))

    private fun empty(): PerceptionResult =
        PerceptionResult(emptyList(), emptyList())

    @Test
    fun `竖大拇指保持 200ms 触发双击并进入冷却`() {
        val gate = GestureGate()
        assertTrue(gate.update(result(hand("Thumb_Up")), 0L).isEmpty())
        assertTrue(gate.update(result(hand("Thumb_Up")), 150L).isEmpty())
        val intents = gate.update(result(hand("Thumb_Up")), 220L)
        assertTrue(intents.single() is ActionIntent.DoubleTap)

        assertTrue(gate.update(result(hand("Thumb_Up")), 300L).isEmpty())
    }

    @Test
    fun `捏合一次在窗口结束后落成单击`() {
        val gate = GestureGate()
        assertTrue(gate.update(dynamic("pinch_tap"), 0L).isEmpty())
        assertTrue(gate.update(empty(), 200L).isEmpty())
        val intents = gate.update(empty(), 400L)
        assertTrue(intents.single() is ActionIntent.Tap)
    }

    @Test
    fun `窗口内快速捏合两次识别为双击而不是两次单击`() {
        val gate = GestureGate()
        assertTrue(gate.update(dynamic("pinch_tap"), 0L).isEmpty())
        val second = gate.update(dynamic("pinch_tap"), 200L)
        assertTrue(second.single() is ActionIntent.DoubleTap)
        assertTrue(gate.update(empty(), 500L).isEmpty())
    }

    @Test
    fun `捏合甩动映射到正确的四向滑动`() {
        val gate = GestureGate()
        val intents = listOf(
            gate.update(dynamic("pinch_swipe_up"), 0L).single(),
            gate.update(dynamic("pinch_swipe_down"), 600L).single(),
            gate.update(dynamic("pinch_swipe_left"), 1200L).single(),
            gate.update(dynamic("pinch_swipe_right"), 1800L).single(),
        )
        val swipes = intents.map { it as ActionIntent.Swipe }
        assertTrue(swipes[0].toY < swipes[0].fromY)
        assertTrue(swipes[1].toY > swipes[1].fromY)
        assertTrue(swipes[2].toX < swipes[2].fromX)
        assertTrue(swipes[3].toX > swipes[3].fromX)
    }

    @Test
    fun `动态滑动冷却 500ms`() {
        val gate = GestureGate()
        val first = gate.update(dynamic("pinch_swipe_up"), 0L)
        assertTrue(first.single() is ActionIntent.Swipe)
        assertTrue(gate.update(dynamic("pinch_swipe_down"), 200L).isEmpty())
        val second = gate.update(dynamic("pinch_swipe_down"), 600L)
        assertTrue(second.single() is ActionIntent.Swipe)
    }

    @Test
    fun `点赞后 400ms 内抑制动态手势`() {
        val gate = GestureGate()
        gate.update(result(hand("Thumb_Up")), 0L)
        val tap = gate.update(result(hand("Thumb_Up")), 220L)
        assertTrue(tap.single() is ActionIntent.DoubleTap)

        assertTrue(gate.update(dynamic("pinch_swipe_up"), 400L).isEmpty())
        val swipe = gate.update(dynamic("pinch_swipe_up"), 700L)
        assertTrue(swipe.single() is ActionIntent.Swipe)
    }

    @Test
    fun `发生滑动会取消尚未落地的单击`() {
        val gate = GestureGate()
        assertTrue(gate.update(dynamic("pinch_tap"), 0L).isEmpty())
        val swipe = gate.update(dynamic("pinch_swipe_up"), 100L)
        assertTrue(swipe.single() is ActionIntent.Swipe)
        assertTrue(gate.update(empty(), 500L).isEmpty())
    }

    @Test
    fun `重置会清掉挂起的单击`() {
        val gate = GestureGate()
        gate.update(dynamic("pinch_tap"), 0L)
        gate.reset()
        assertTrue(gate.update(empty(), 500L).isEmpty())
    }
}

