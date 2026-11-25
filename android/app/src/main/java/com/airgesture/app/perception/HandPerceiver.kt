package com.airgesture.app.perception

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.airgesture.app.AppConfig
import com.airgesture.app.AppState
import com.airgesture.app.core.Hand
import com.airgesture.app.core.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * MediaPipe GestureRecognizer（LIVE_STREAM，端内推理）。
 * 模型来自 app/src/main/assets/gesture_recognizer.task（与原型同一个模型）。
 *
 * 说明：回调时间直接用 elapsedRealtime，避免 LIVE_STREAM 丢帧时
 * 自维护时间戳队列与回调错位。
 */
class HandPerceiver(
    context: Context,
    private val onResult: (hands: List<Hand>, timestampMs: Long) -> Unit,
) {
    private val recognizer: GestureRecognizer
    private val lock = Any()
    private var lastTimestamp = 0L

    @Volatile
    private var running = true

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(AppConfig.NUM_HANDS)
            .setMinHandDetectionConfidence(AppConfig.MIN_HAND_DETECTION)
            .setMinHandPresenceConfidence(AppConfig.MIN_HAND_PRESENCE)
            .setMinTrackingConfidence(AppConfig.MIN_TRACKING)
            .setResultListener { result, _ ->
                onResult(convert(result), SystemClock.elapsedRealtime())
            }
            .setErrorListener { error ->
                AppState.lastError = "识别错误: ${error.message}"
                Log.w(TAG, "mediapipe error", error)
            }
            .build()
        recognizer = GestureRecognizer.createFromOptions(context, options)
        Log.i(TAG, "GestureRecognizer ready")
    }

    fun recognize(bitmap: Bitmap, timestampMs: Long) {
        // 服务停止后 CameraX 可能还有最后一两帧进来，关闭中的 MediaPipe 会抛
        // "task graph hasn't been successfully started"，这里直接丢弃。
        if (!running) return
        val ts = synchronized(lock) {
            lastTimestamp = maxOf(timestampMs, lastTimestamp + 1)
            lastTimestamp
        }
        try {
            recognizer.recognizeAsync(BitmapImageBuilder(bitmap).build(), ts)
        } catch (t: Throwable) {
            if (running) {
                AppState.lastError = "识别调用失败: ${t.message}"
                Log.w(TAG, "recognizeAsync failed", t)
            }
        }
    }

    /** 先停再关：让调用方在关闭识别器前不再提交新帧。 */
    fun stop() {
        running = false
    }

    fun close() {
        running = false
        try {
            recognizer.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
    }

    private fun convert(result: GestureRecognizerResult): List<Hand> {
        val hands = ArrayList<Hand>()
        // MediaPipe Tasks 0.10.14: GestureRecognizerResult.landmarks() / gestures()
        val landmarksList = result.landmarks()
        val gesturesList = result.gestures()
        for (i in landmarksList.indices) {
            val points = landmarksList[i].map { Landmark(it.x(), it.y(), it.z()) }
            val top = gesturesList.getOrNull(i)?.maxByOrNull { it.score() }
            val name = top?.categoryName()?.takeIf { it.isNotEmpty() && it != "None" }
            hands.add(Hand(points, name, top?.score() ?: 0f))
        }
        return hands
    }

    private companion object {
        const val TAG = "AirGestureMediaPipe"
    }
}
