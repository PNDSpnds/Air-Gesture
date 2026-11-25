package com.airgesture.app.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.airgesture.app.AppState
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * CameraX 本机前置摄像头采集。
 * 用 ImageAnalysis 输出 RGBA_8888，640x480，KEEP_ONLY_LATEST 保证低延迟。
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (ImageProxy) -> Unit,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        onFrame(image)
                    } catch (t: Throwable) {
                        Log.w(TAG, "frame error", t)
                    } finally {
                        image.close()
                    }
                }
                imageAnalysis = analysis
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                Log.i(TAG, "camera started (front, 640x480)")
            } catch (t: Throwable) {
                AppState.lastError = "相机启动失败: ${t.message}"
                Log.e(TAG, "camera start failed", t)
            }
        }, mainExecutor)
    }

    fun stop() {
        try {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "camera stop error", t)
        } finally {
            analysisExecutor.shutdown()
        }
    }

    private companion object {
        const val TAG = "AirGestureCamera"
    }
}
