package com.airgesture.app.perception

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.airgesture.app.core.Hand
import com.airgesture.app.core.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * 内置手势图自检：用 IMAGE 模式在同款 MediaPipe 模型上跑一张内置手部照片，
 * 验证 Android 端能真正输出手部 21 个关键点与手势分类（不依赖真人把手伸到镜头前）。
 *
 * 自检图：assets/selftest_hand.jpg（CC BY 4.0，见 SELFTEST_IMAGE_LICENSE.txt）。
 */
object SelfTest {

    private const val TAG = "AirGestureSelfTest"
    private const val ASSET_IMAGE = "selftest_hand.jpg"

    /** 自检结果：summary 给 UI 显示，hands 供整链自检复用真实检测结果。 */
    data class Detection(val summary: String, val hands: List<Hand>)

    fun run(context: Context): String = detect(context).summary

    fun detect(context: Context): Detection {
        val bytes = try {
            context.assets.open(ASSET_IMAGE).use { it.readBytes() }
        } catch (t: Throwable) {
            return Detection("失败：读取内置图片失败 (${t.message})", emptyList())
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return Detection("失败：内置图片解码失败", emptyList())

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .build()

        val recognizer = try {
            GestureRecognizer.createFromOptions(context, options)
        } catch (t: Throwable) {
            Log.w(TAG, "create recognizer failed", t)
            return Detection("失败：模型加载失败 (${t.message})", emptyList())
        }

        return try {
            val image = BitmapImageBuilder(bitmap).build()
            val result = recognizer.recognize(image)
            val hands = ArrayList<Hand>()
            val landmarkLists = result.landmarks()
            val gestureLists = result.gestures()
            for (i in landmarkLists.indices) {
                val points = landmarkLists[i].map { Landmark(it.x(), it.y(), it.z()) }
                val top = gestureLists.getOrNull(i)?.maxByOrNull { it.score() }
                hands.add(
                    Hand(
                        points,
                        top?.categoryName()?.takeIf { it.isNotEmpty() && it != "None" },
                        top?.score() ?: 0f,
                    )
                )
            }
            if (hands.isEmpty()) {
                Detection("失败：未检测到手", emptyList())
            } else {
                val points = hands.first().landmarks.size
                val gesture = hands.first().gesture
                val score = hands.first().score
                val text = if (gesture != null) {
                    "$gesture ${"%.2f".format(score)}"
                } else {
                    "未分类"
                }
                Detection("通过：手 ${hands.size} 只 / 关键点 $points / 手势 $text", hands)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "selftest inference failed", t)
            Detection("失败：识别异常 (${t.message})", emptyList())
        } finally {
            try {
                recognizer.close()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }
}
