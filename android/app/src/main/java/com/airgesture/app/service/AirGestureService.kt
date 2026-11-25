package com.airgesture.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.airgesture.app.AppState
import com.airgesture.app.MainActivity
import com.airgesture.app.R
import com.airgesture.app.action.AccessibilityExecutor
import com.airgesture.app.camera.CameraController
import com.airgesture.app.camera.ImageConverter
import com.airgesture.app.core.ActionIntent
import com.airgesture.app.core.Hand
import com.airgesture.app.core.PerceptionResult
import com.airgesture.app.mapping.GestureGate
import com.airgesture.app.perception.DynamicGestureExtractor
import com.airgesture.app.perception.HandPerceiver

/**
 * 前台服务：CameraX 采集 -> MediaPipe 端内识别 -> 手势映射 -> 无障碍注入。
 * 服务启动即生效，没有激活/待机/急停状态；停止由主界面完成。
 */
class AirGestureService : LifecycleService() {

    companion object {
        const val ACTION_TEST_SWIPE = "com.airgesture.app.TEST_SWIPE"
        const val ACTION_TEST_TAP = "com.airgesture.app.TEST_TAP"
        private const val CHANNEL_ID = "airgesture_camera"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AirGestureService"
        private const val NOTIFY_INTERVAL_MS = 700L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var perceiver: HandPerceiver? = null
    private var cameraController: CameraController? = null
    private val extractor = DynamicGestureExtractor()
    private val gate = GestureGate()

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastNotifyMs = 0L
    private var receiverRegistered = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TEST_SWIPE -> handleIntents(
                    listOf(ActionIntent.Swipe(0.5f, 0.72f, 0.5f, 0.30f, 180L))
                )
                ACTION_TEST_TAP -> handleIntents(listOf(ActionIntent.Tap(0.5f, 0.5f)))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
        AppState.reset()
        AppState.serviceRunning = true
        registerCommandReceiver()
        perceiver = HandPerceiver(this) { hands, ts -> onHands(hands, ts) }
        cameraController = CameraController(this, this) { image -> onFrame(image) }
        cameraController?.start()

        fpsWindowStart = SystemClock.elapsedRealtime()
        Log.i(TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): android.os.IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "service stopping")
        if (receiverRegistered) {
            try {
                unregisterReceiver(commandReceiver)
            } catch (t: Throwable) {
                Log.w(TAG, "unregister failed", t)
            }
            receiverRegistered = false
        }
        cameraController?.stop()
        perceiver?.stop()
        perceiver?.close()
        cameraController = null
        perceiver = null
        AppState.serviceRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 相机帧
    private fun onFrame(image: androidx.camera.core.ImageProxy) {
        val ts = SystemClock.elapsedRealtime()
        try {
            val bitmap = ImageConverter.toUprightBitmap(image, mirror = true)
            frameCount++
            val elapsed = ts - fpsWindowStart
            if (elapsed >= 1000L) {
                AppState.fps = frameCount * 1000f / elapsed
                frameCount = 0
                fpsWindowStart = ts
            }
            perceiver?.recognize(bitmap, ts)
        } catch (t: Throwable) {
            AppState.lastError = "帧处理失败: ${t.message}"
            Log.w(TAG, "frame processing failed", t)
        }
    }

    // ------------------------------------------------------------------ 识别结果
    private fun onHands(hands: List<Hand>, ts: Long) {
        val primary = hands.maxByOrNull { it.score }
        AppState.lastGesture = primary?.gesture ?: "-"

        val dynamic = extractor.update(primary, ts)
        val result = PerceptionResult(hands, dynamic)
        val intents = gate.update(result, ts)
        handleIntents(intents)
        maybeNotify()
    }

    private fun handleIntents(intents: List<ActionIntent>) {
        if (intents.isEmpty()) return
        for (intent in intents) {
            if (intent is ActionIntent.SystemOp) continue
            val ok = AccessibilityExecutor.execute(intent)
            AppState.lastAction = AccessibilityExecutor.describe(intent) +
                if (ok) "" else "（无障碍未开启）"
            Log.i(TAG, "intent: ${AccessibilityExecutor.describe(intent)} ok=$ok")
        }
        notifyNow()
    }

    // ------------------------------------------------------------------ 通知
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手势识别",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "隔空手控前台手势识别服务"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("隔空手控")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun maybeNotify() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs < NOTIFY_INTERVAL_MS) return
        lastNotifyMs = now
        notifyNow()
    }

    private fun notifyNow() {
        val text = "${AppState.lastGesture} | ${AppState.lastAction}"
        Log.d(TAG, "gesture=${AppState.lastGesture} action=${AppState.lastAction} fps=${"%.1f".format(AppState.fps)}")
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    // ------------------------------------------------------------------ 调试命令
    private fun registerCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_TEST_SWIPE)
            addAction(ACTION_TEST_TAP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
        receiverRegistered = true
    }
}



