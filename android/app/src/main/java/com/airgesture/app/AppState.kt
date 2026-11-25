package com.airgesture.app

/** Service 更新、MainActivity 定时读取的轻量共享状态。 */
object AppState {
    @Volatile var serviceRunning: Boolean = false
    @Volatile var lastGesture: String = "-"
    @Volatile var lastAction: String = "-"
    @Volatile var fps: Float = 0f
    @Volatile var lastError: String? = null

    fun reset() {
        lastGesture = "-"
        lastAction = "-"
        fps = 0f
        lastError = null
    }
}
