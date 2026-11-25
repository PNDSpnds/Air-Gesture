package com.airgesture.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** CameraX ImageProxy(RGBA_8888) -> 正向、可选镜像的 Bitmap。 */
object ImageConverter {

    fun toUprightBitmap(image: ImageProxy, mirror: Boolean): Bitmap {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (rowStride == pixelStride * width) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // 某些设备行间有 padding：重排成连续 buffer
            val out = ByteBuffer.allocateDirect(width * height * pixelStride)
            val rowBytes = width * pixelStride
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowBytes)
                out.put(row)
            }
            out.rewind()
            bitmap.copyPixelsFromBuffer(out)
        }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 && !mirror) return bitmap

        // 一次矩阵变换完成「旋转 + 前置镜像」，少一次 Bitmap 拷贝，降低跟手延迟
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }
}
