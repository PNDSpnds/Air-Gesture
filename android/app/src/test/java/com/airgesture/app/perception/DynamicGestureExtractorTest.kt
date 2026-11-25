package com.airgesture.app.perception

import com.airgesture.app.core.Hand
import com.airgesture.app.core.Landmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 捏合连续甩动状态机的 JVM 单元测试（不依赖 Android 运行时）。
 *
 * 时间轴约定：t 为帧时间戳（ms），每个用例都从 0 开始。
 */
class DynamicGestureExtractorTest {

    /** 构造一只虚拟手：palm 位置 + 捏合/张开。 */
    private fun hand(palmX: Float, palmY: Float, pinchClosed: Boolean): Hand {
        val lm = MutableList(21) { Landmark(palmX, palmY, 0f) }
        lm[0] = Landmark(palmX, palmY + 0.08f)          // 腕
        lm[5] = Landmark(palmX - 0.10f, palmY)          // 食指根
        lm[9] = Landmark(palmX, palmY)
        lm[13] = Landmark(palmX, palmY)
        lm[17] = Landmark(palmX + 0.10f, palmY)         // 小指根
        lm[4] = Landmark(palmX - 0.01f, palmY - 0.01f)  // 拇指尖
        lm[8] = if (pinchClosed) {
            Landmark(palmX + 0.01f, palmY - 0.01f)      // 与拇指贴近
        } else {
            Landmark(palmX + 0.25f, palmY - 0.15f)      // 张开
        }
        return Hand(lm, gesture = null, score = 0.9f)
    }

    private val openHand = hand(0.5f, 0.5f, false)

    @Test
    fun `捏合需要连续两帧确认`() {
        val ex = DynamicGestureExtractor()
        assertTrue(ex.update(hand(0.5f, 0.5f, false), 0L).isEmpty())
        // 只闭合一帧不触发
        assertTrue(ex.update(hand(0.5f, 0.5f, true), 33L).isEmpty())
        // 第二帧确认，产生 pinch
        val events = ex.update(hand(0.5f, 0.5f, true), 66L)
        assertEquals(listOf("pinch"), events.map { it.name })
    }

    @Test
    fun `保护窗口内小幅移动不触发方向，窗口结束后触发`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        val pinch = ex.update(hand(0.5f, 0.5f, true), 66L)
        assertEquals(listOf("pinch"), pinch.map { it.name })

        // guard 至 66+180=246ms；移动 0.12 在放大阈值 0.18 以下，不触发
        assertTrue(ex.update(hand(0.5f, 0.38f, true), 99L).isEmpty())
        assertTrue(ex.update(hand(0.5f, 0.50f, true), 132L).isEmpty())

        // guard 结束后同样的位移触发向上甩动
        val events = ex.update(hand(0.5f, 0.38f, true), 280L)
        assertEquals(listOf("pinch_swipe_up"), events.map { it.name })
    }

    @Test
    fun `甩完回到起点附近可以不用松手连续甩`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        ex.update(hand(0.5f, 0.5f, true), 66L)
        assertEquals(
            listOf("pinch_swipe_up"),
            ex.update(hand(0.5f, 0.38f, true), 280L).map { it.name },
        )
        // 回到起点 0.05 以内 -> 重新武装（不允许立刻触发，有 100ms 冷却）
        assertTrue(ex.update(hand(0.5f, 0.49f, true), 313L).isEmpty())
        // 冷却后再甩一次
        val second = ex.update(hand(0.5f, 0.35f, true), 430L)
        assertEquals(listOf("pinch_swipe_up"), second.map { it.name })
    }

    @Test
    fun `水平甩动识别为左右`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        ex.update(hand(0.5f, 0.5f, true), 66L)
        val right = ex.update(hand(0.68f, 0.5f, true), 300L)
        assertEquals(listOf("pinch_swipe_right"), right.map { it.name })
    }

    @Test
    fun `松开需要连续两帧张开，再捏合会重新产生 pinch`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        assertEquals(listOf("pinch"), ex.update(hand(0.5f, 0.5f, true), 66L).map { it.name })

        // 张开第一帧还不算松开
        assertTrue(ex.update(hand(0.5f, 0.5f, false), 99L).isEmpty())
        // 第二帧松开：短促、未甩动 -> 产生一次 pinch_tap
        assertEquals(listOf("pinch_tap"), ex.update(hand(0.5f, 0.5f, false), 132L).map { it.name })
        // 再捏合两帧，重新产生 pinch
        assertTrue(ex.update(hand(0.5f, 0.5f, true), 165L).isEmpty())
        assertEquals(listOf("pinch"), ex.update(hand(0.5f, 0.5f, true), 198L).map { it.name })
    }

    @Test
    fun `短促捏合松手产生 pinch_tap`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        ex.update(hand(0.5f, 0.5f, true), 66L)
        assertTrue(ex.update(hand(0.5f, 0.5f, false), 99L).isEmpty())
        assertEquals(listOf("pinch_tap"), ex.update(hand(0.5f, 0.5f, false), 132L).map { it.name })
    }

    @Test
    fun `捏合超过 500ms 松手不产生 pinch_tap`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        ex.update(hand(0.5f, 0.5f, true), 66L)
        ex.update(hand(0.5f, 0.5f, true), 700L)
        assertTrue(ex.update(hand(0.5f, 0.5f, false), 733L).isEmpty())
        assertTrue(ex.update(hand(0.5f, 0.5f, false), 766L).isEmpty())
    }

    @Test
    fun `甩动后松手不再产生 pinch_tap`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        ex.update(hand(0.5f, 0.5f, true), 66L)
        assertEquals(
            listOf("pinch_swipe_up"),
            ex.update(hand(0.5f, 0.38f, true), 280L).map { it.name },
        )
        assertTrue(ex.update(hand(0.5f, 0.38f, false), 313L).isEmpty())
        assertTrue(ex.update(hand(0.5f, 0.38f, false), 346L).isEmpty())
    }

    @Test
    fun `没有手时状态清零`() {
        val ex = DynamicGestureExtractor()
        ex.update(hand(0.5f, 0.5f, false), 0L)
        ex.update(hand(0.5f, 0.5f, true), 33L)
        ex.update(hand(0.5f, 0.5f, true), 66L)
        // 手消失
        assertTrue(ex.update(null, 100L).isEmpty())
        // 重新出现：需要重新确认捏合
        assertTrue(ex.update(hand(0.5f, 0.5f, true), 133L).isEmpty())
        assertEquals(listOf("pinch"), ex.update(hand(0.5f, 0.5f, true), 166L).map { it.name })
    }
}

