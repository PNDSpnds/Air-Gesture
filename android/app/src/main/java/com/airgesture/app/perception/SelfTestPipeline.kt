package com.airgesture.app.perception

import android.content.Context
import com.airgesture.app.core.ActionIntent
import com.airgesture.app.core.Hand
import com.airgesture.app.core.Landmark
import com.airgesture.app.core.PerceptionResult
import com.airgesture.app.mapping.GestureGate

/**
 * 整链自检：用内置手势图跑一次真实 MediaPipe 识别，取到真实的 21 个手部关键点后，
 * 基于这组真实关键点合成「捏合保持 + 向上甩动」的帧序列，喂给 App 里同一套
 * DynamicGestureExtractor + GestureGate，验证整条链路能产出「向上滑动」动作。
 *
 * 覆盖：MediaPipe(Android 端) -> Hand 坐标转换 -> 捏合状态机 -> 门控 -> ActionIntent。
 * 注意：不真正执行注入，避免影响前台界面。
 */
object SelfTestPipeline {

    fun run(context: Context): String {
        val detection = SelfTest.detect(context)
        val base = detection.hands.firstOrNull()?.landmarks ?: return "失败：内置图未检测到手"
        if (base.size < 21) return "失败：关键点不足 (${base.size})"

        val extractor = DynamicGestureExtractor()
        val gate = GestureGate()
        val events = ArrayList<String>()
        val intents = ArrayList<ActionIntent>()

        fun frame(ts: Long, shiftY: Float, closed: Boolean) {
            val lm = base.map { Landmark(it.x, it.y + shiftY, it.z) }.toMutableList()
            val thumb = lm[4]
            // 明确合成张开/捏合：张开时食指尖远离拇指，捏合时贴近
            lm[8] = if (closed) {
                Landmark(thumb.x + 0.01f, thumb.y, thumb.z)
            } else {
                Landmark(thumb.x + 0.25f, thumb.y - 0.15f, thumb.z)
            }
            val hand = Hand(lm, gesture = null, score = 0.9f)
            val dynamic = extractor.update(hand, ts)
            events.addAll(dynamic.map { it.name })
            val result = PerceptionResult(listOf(hand), dynamic)
            intents.addAll(gate.update(result, ts))
        }

        // 张开两帧 -> 捏合两帧确认 -> 保护窗口内小位移 -> 窗口结束后向上甩
        frame(0L, 0f, false)
        frame(33L, 0f, false)
        frame(66L, 0f, true)
        frame(99L, 0f, true)
        frame(132L, -0.02f, true)
        frame(320L, -0.15f, true)

        val swipe = intents.filterIsInstance<ActionIntent.Swipe>().firstOrNull()
            ?: return "失败：未产生滑动动作 (events=$events)"
        if (swipe.toY >= swipe.fromY) return "失败：方向不是向上 (from=${swipe.fromY}, to=${swipe.toY})"
        return "通过：捏合甩动→向上滑动（识别帧点=${base.size}, events=$events）"
    }
}


