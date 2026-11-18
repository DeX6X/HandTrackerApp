package com.example.handtrackerapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HandControlAccessibilityService : AccessibilityService(), LifecycleOwner {

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _isTrackingEnabled = MutableStateFlow(false)
        val isTrackingEnabled = _isTrackingEnabled.asStateFlow()

        var instance: HandControlAccessibilityService? = null

        fun setTrackingEnabled(enabled: Boolean) {
            _isTrackingEnabled.value = enabled
            instance?.applyTrackingState(enabled)
        }
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: PointerOverlayView
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private val gestureProcessor = GestureProcessor()
    private var activeDragStroke: GestureDescription.StrokeDescription? = null
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var lastDragTimeMs = 0L

    // ตัวแปรสำหรับระบบประหยัดแบตเตอรี่ (Adaptive FPS)
    private var lastHandDetectedTimeMs = 0L
    private var lastProcessedFrameTimeMs = 0L

    // ตัวตรวจจับการหมุนหน้าจอ (แนวตั้ง / แนวนอน)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val rotation = windowManager.defaultDisplay.rotation
                imageAnalyzer?.targetRotation = rotation
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(this)
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        setupOverlayWindow()
        setupHandLandmarker()

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        applyTrackingState(_isTrackingEnabled.value)
    }

    private fun applyTrackingState(enabled: Boolean) {
        if (!::overlayView.isInitialized) return
        if (enabled) {
            overlayView.visibility = View.VISIBLE
            lastHandDetectedTimeMs = SystemClock.uptimeMillis()
            startCamera()
        } else {
            overlayView.visibility = View.GONE
            cameraProvider?.unbindAll()
        }
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = PointerOverlayView(this).apply { visibility = View.GONE }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(overlayView, params)
    }

    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ ->
                if (!_isTrackingEnabled.value) return@setResultListener

                val now = SystemClock.uptimeMillis()
                val landmarks = result.landmarks().firstOrNull()
                if (landmarks != null) {
                    lastHandDetectedTimeMs = now
                }

                val output = gestureProcessor.process(landmarks, now)
                overlayView.updateState(output)

                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                val targetX = (output.pointerPosition?.x ?: 0.5f) * screenWidth
                val targetY = (output.pointerPosition?.y ?: 0.5f) * screenHeight

                // กำมือ -> เปิด Tab Bar
                if (output.isTabBarTriggered) {
                    vibrateFeedback(35L)
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }

                // ชู 2 นิ้ว -> กดปุ่ม Back
                if (output.isBackTriggered) {
                    vibrateFeedback(25L)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }

                // จีบนิ้ว -> คลิก
                if (output.isClicked) {
                    vibrateFeedback(15L)
                    performTap(targetX, targetY)
                }

                // จีบนิ้วค้าง -> ลาก
                handleDragEvent(output.dragEvent, targetX, targetY, now)
            }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun vibrateFeedback(durationMs: Long) {
        if (!SettingsManager.settings.value.hapticEnabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun handleDragEvent(event: DragEvent, x: Float, y: Float, now: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            when (event) {
                DragEvent.START -> {
                    vibrateFeedback(20L)
                    val path = Path().apply {
                        moveTo(x, y)
                        // ขยับเส้นเล็กน้อย 1px ตั้งแต่วินาทีแรก เพื่อให้ Android รู้ว่านี่คือการ "ลาก" ไม่ใช่ "แตะอยู่กับที่"
                        lineTo(x + 1f, y + 1f)
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, 40L, true)
                    activeDragStroke = stroke
                    lastDragX = x + 1f
                    lastDragY = y + 1f
                    lastDragTimeMs = now
                    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                }
                DragEvent.MOVE -> {
                    val currentStroke = activeDragStroke ?: return
                    val dt = (now - lastDragTimeMs).coerceIn(20L, 100L)

                    // หากมืออยู่นิ่งมาก ให้ขยับพิกัดปลายทางไป 0.5px เพื่อเลี้ยงสถานะการกดค้างไว้ไม่ให้หลุด
                    val targetX = if (kotlin.math.abs(x - lastDragX) < 0.5f) lastDragX + 0.5f else x
                    val targetY = if (kotlin.math.abs(y - lastDragY) < 0.5f) lastDragY + 0.5f else y

                    val path = Path().apply {
                        moveTo(lastDragX, lastDragY)
                        lineTo(targetX, targetY)
                    }
                    val nextStroke = currentStroke.continueStroke(path, 0L, dt, true)
                    activeDragStroke = nextStroke
                    lastDragX = targetX
                    lastDragY = targetY
                    lastDragTimeMs = now
                    dispatchGesture(GestureDescription.Builder().addStroke(nextStroke).build(), null, null)
                }
                DragEvent.END -> {
                    val currentStroke = activeDragStroke ?: return
                    val dt = (now - lastDragTimeMs).coerceIn(20L, 100L)
                    val path = Path().apply {
                        moveTo(lastDragX, lastDragY)
                        lineTo(x, y)
                    }
                    val endStroke = currentStroke.continueStroke(path, 0L, dt, false)
                    activeDragStroke = null
                    dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
                }
                DragEvent.NONE -> {}
            }
        } catch (e: Exception) {
            // ป้องกันแอปเด้งหากระบบส่ง Stroke ซ้อนกันเร็วเกินไป
            activeDragStroke = null
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val now = SystemClock.uptimeMillis()

                        // ระบบประหยัดแบต (Adaptive FPS): ถ้าไม่เห็นมือเกิน 3 วิ ให้ลดเหลือ 5 FPS (ทุก 200ms)
                        val isIdleSleep = (now - lastHandDetectedTimeMs) > 3000L
                        val minIntervalMs = if (isIdleSleep) 200L else 0L

                        if (now - lastProcessedFrameTimeMs >= minIntervalMs) {
                            lastProcessedFrameTimeMs = now
                            val bitmap = imageProxy.toBitmap()
                            val matrix = Matrix().apply {
                                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                            }
                            val rotatedBitmap = Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                            )
                            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                            handLandmarker?.detectAsync(mpImage, now)
                        }
                        imageProxy.close()
                    }
                }

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                imageAnalyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceRunning.value = false
        _isTrackingEnabled.value = false
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.unregisterDisplayListener(displayListener)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        handLandmarker?.close()
        cameraExecutor.shutdown()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}