package com.example.handtrackerapp

import androidx.compose.ui.geometry.Offset
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.hypot

enum class GestureState {
    IDLE,
    POINTER_ACTIVE,
    DRAGGING,
    FIST_HOLD,
    BACK_HOLD
}

enum class DragEvent {
    NONE, START, MOVE, END
}

data class GestureOutput(
    val state: GestureState,
    val pointerPosition: Offset?,
    val isClicked: Boolean,
    val dragEvent: DragEvent = DragEvent.NONE,
    val isTabBarTriggered: Boolean = false,
    val isBackTriggered: Boolean = false,
    val isPositionLocked: Boolean = false
)

class OneEuroFilter2D(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 0.04f,
    private val dCutoff: Float = 1.0f
) {
    private var xPrev: Float? = null
    private var yPrev: Float? = null
    private var dxPrev = 0f
    private var dyPrev = 0f
    private var lastTimeMs = 0L

    fun reset() {
        xPrev = null
        yPrev = null
        dxPrev = 0f
        dyPrev = 0f
        lastTimeMs = 0L
    }

    fun filter(x: Float, y: Float, timestampMs: Long): Offset {
        val prevX = xPrev
        val prevY = yPrev
        if (prevX == null || prevY == null || lastTimeMs == 0L) {
            xPrev = x
            yPrev = y
            lastTimeMs = timestampMs
            return Offset(x, y)
        }

        val dt = ((timestampMs - lastTimeMs) / 1000f).coerceIn(0.001f, 0.2f)
        lastTimeMs = timestampMs

        val dx = (x - prevX) / dt
        val dy = (y - prevY) / dt

        val alphaD = smoothingFactor(dt, dCutoff)
        val dxHat = alphaD * dx + (1f - alphaD) * dxPrev
        val dyHat = alphaD * dy + (1f - alphaD) * dyPrev

        val speed = hypot(dxHat.toDouble(), dyHat.toDouble()).toFloat()
        val cutoff = minCutoff + beta * speed
        val alpha = smoothingFactor(dt, cutoff)

        val xHat = alpha * x + (1f - alpha) * prevX
        val yHat = alpha * y + (1f - alpha) * prevY

        xPrev = xHat
        yPrev = yHat
        dxPrev = dxHat
        dyPrev = dyHat

        return Offset(xHat, yHat)
    }

    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val r = 2f * PI.toFloat() * cutoff * dt
        return r / (r + 1f)
    }
}

class GestureProcessor {
    private var currentState = GestureState.IDLE
    private var lastFilteredHandPos: Offset? = null
    private var lastFrameTimeMs = 0L
    private var virtualCursorPos: Offset = Offset(0.5f, 0.5f)

    private val handFilter = OneEuroFilter2D(minCutoff = 1.1f, beta = 0.05f)

    private var wasIndexPinched = false
    private var isMiddleDragging = false
    private var lastDragEndTimeMs = 0L // ตัวจับเวลา Cooldown หลังจบการลาก กันเผลอลั่นคลิก

    private var fistStartTime = 0L
    private var lastFistTriggerTime = 0L
    private var peaceStartTime = 0L
    private var lastPeaceTriggerTime = 0L
    private val gestureHoldRequiredMs = 400L

    private val fistCurlRatio = 1.15f
    private val maxVelocityPerSec = 3.5f

    fun process(landmarks: List<NormalizedLandmark>?, currentTimeMs: Long): GestureOutput {
        if (landmarks == null || landmarks.size < 21) {
            val wasDragging = isMiddleDragging
            reset()
            return GestureOutput(
                state = GestureState.IDLE,
                pointerPosition = null,
                isClicked = false,
                dragEvent = if (wasDragging) DragEvent.END else DragEvent.NONE
            )
        }

        val currentSettings = SettingsManager.settings.value
        val pinchThreshold = currentSettings.pinchThreshold

        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val middleMcp = landmarks[9]
        val middleTip = landmarks[12]

        val palmSize = distance(wrist, middleMcp).coerceAtLeast(0.01f)

        // 1. ตรวจสอบท่ากำมือ (Tab Bar)
        if (!isMiddleDragging && checkClosedFist(landmarks, wrist, palmSize)) {
            peaceStartTime = 0L
            if (fistStartTime == 0L) fistStartTime = currentTimeMs
            if (currentTimeMs - fistStartTime >= gestureHoldRequiredMs && (currentTimeMs - lastFistTriggerTime > 1500L)) {
                lastFistTriggerTime = currentTimeMs
                fistStartTime = 0L
                wasIndexPinched = false
                lastFilteredHandPos = null
                return GestureOutput(GestureState.FIST_HOLD, virtualCursorPos, false, isTabBarTriggered = true)
            }
            lastFilteredHandPos = null
            return GestureOutput(GestureState.FIST_HOLD, virtualCursorPos, false, isPositionLocked = true)
        } else {
            fistStartTime = 0L
        }

        // 2. ตรวจสอบท่าชู 2 นิ้ว (Back)
        if (!isMiddleDragging && checkPeaceSign(landmarks, wrist, palmSize)) {
            if (peaceStartTime == 0L) peaceStartTime = currentTimeMs
            if (currentTimeMs - peaceStartTime >= gestureHoldRequiredMs && (currentTimeMs - lastPeaceTriggerTime > 1500L)) {
                lastPeaceTriggerTime = currentTimeMs
                peaceStartTime = 0L
                wasIndexPinched = false
                lastFilteredHandPos = null
                return GestureOutput(GestureState.BACK_HOLD, virtualCursorPos, false, isBackTriggered = true)
            }
            lastFilteredHandPos = null
            return GestureOutput(GestureState.BACK_HOLD, virtualCursorPos, false, isPositionLocked = true)
        } else {
            peaceStartTime = 0L
        }

        // 3. คำนวณตำแหน่งฝ่ามือ (#9) และระยะนิ้วทั้งสองคู่
        val rawHandX = 1f - middleMcp.x()
        val rawHandY = middleMcp.y()
        val filteredHand = handFilter.filter(rawHandX, rawHandY, currentTimeMs)

        val indexPinchDist = distance(thumbTip, indexTip) / palmSize
        val middlePinchDist = distance(thumbTip, middleTip) / palmSize

        if (currentState == GestureState.IDLE || currentState == GestureState.FIST_HOLD || currentState == GestureState.BACK_HOLD || lastFilteredHandPos == null) {
            currentState = GestureState.POINTER_ACTIVE
            lastFilteredHandPos = filteredHand
            lastFrameTimeMs = currentTimeMs
            return GestureOutput(currentState, virtualCursorPos, false)
        }

        // 4. คำนวณความเร็วของมือและอัปเดตตำแหน่ง Pointer
        val dtSec = ((currentTimeMs - lastFrameTimeMs) / 1000f).coerceIn(0.005f, 0.15f)
        val dx = filteredHand.x - lastFilteredHandPos!!.x
        val dy = filteredHand.y - lastFilteredHandPos!!.y

        val totalVelocity = hypot((dx / dtSec).toDouble(), (dy / dtSec).toDouble()).toFloat()

        if (totalVelocity < maxVelocityPerSec) {
            val dynamicGain = (0.65f + (totalVelocity * 1.4f)).coerceIn(0.65f, 2.2f)
            val finalSpeedX = currentSettings.cursorSpeed * dynamicGain
            val finalSpeedY = (currentSettings.cursorSpeed * 1.15f) * dynamicGain

            virtualCursorPos = Offset(
                x = (virtualCursorPos.x + dx * finalSpeedX).coerceIn(0f, 1f),
                y = (virtualCursorPos.y + dy * finalSpeedY).coerceIn(0f, 1f)
            )
        }

        lastFilteredHandPos = filteredHand
        lastFrameTimeMs = currentTimeMs

        // 5. จัดการคำสั่งแยกนิ้ว (ป้องกันการคลิกลั่นระหว่างเตรียมลากและหลังจบการลาก)
        var clicked = false
        var dragEvent = DragEvent.NONE

        val isMiddlePinchingNow = middlePinchDist < pinchThreshold
        // กำลังลดนิ้วกลางลงมาเตรียมลาก (กันนิ้วชี้ที่ห้อยตามลงมาเผลอลั่นคลิก)
        val isPreparingMiddleDrag = middlePinchDist < (pinchThreshold + 0.12f)
        val isIndexPinchingNow = indexPinchDist < pinchThreshold

        if (isMiddleDragging) {
            // เพิ่ม Hysteresis เป็น +0.12f เพื่อให้โหมดลากเกาะติดแน่น ไม่หลุดกลางคันจนกลายเป็นคลิก
            if (middlePinchDist > pinchThreshold + 0.12f) {
                isMiddleDragging = false
                lastDragEndTimeMs = currentTimeMs // บันทึกเวลาที่ปล่อยลาก เพื่อล็อกการคลิกชั่วคราว 250ms
                wasIndexPinched = false
                currentState = GestureState.POINTER_ACTIVE
                dragEvent = DragEvent.END
            } else {
                dragEvent = DragEvent.MOVE
            }
        } else {
            if (isMiddlePinchingNow) {
                // เข้าสู่โหมดลากทันที (ไม่ต้องรอให้ระยะน้อยกว่านิ้วชี้ เผื่อนิ้วชี้ห้อยตามลงมาด้วย)
                isMiddleDragging = true
                wasIndexPinched = false
                currentState = GestureState.DRAGGING
                dragEvent = DragEvent.START
            } else {
                currentState = GestureState.POINTER_ACTIVE

                // อนุญาตให้คลิกได้เฉพาะตอนที่:
                // 1) นิ้วกลางไม่ได้กำลังลดลงมาเตรียมลาก (!isPreparingMiddleDrag)
                // 2) พ้นระยะ Cooldown หลังปล่อยโหมดลากมาแล้วอย่างน้อย 250ms
                val isClickAllowed = !isPreparingMiddleDrag && (currentTimeMs - lastDragEndTimeMs > 250L)

                if (isClickAllowed && isIndexPinchingNow && !wasIndexPinched) {
                    clicked = true
                }
                wasIndexPinched = isIndexPinchingNow
            }
        }

        return GestureOutput(
            state = currentState,
            pointerPosition = virtualCursorPos,
            isClicked = clicked,
            dragEvent = dragEvent,
            isPositionLocked = isIndexPinchingNow && !isMiddleDragging && !isPreparingMiddleDrag
        )
    }

    private fun checkClosedFist(landmarks: List<NormalizedLandmark>, wrist: NormalizedLandmark, palmSize: Float): Boolean {
        val tips = listOf(8, 12, 16, 20)
        return tips.all { distance(landmarks[it], wrist) / palmSize < fistCurlRatio }
    }

    private fun checkPeaceSign(landmarks: List<NormalizedLandmark>, wrist: NormalizedLandmark, palmSize: Float): Boolean {
        val indexExtended = distance(landmarks[8], wrist) / palmSize > 1.55f
        val middleExtended = distance(landmarks[12], wrist) / palmSize > 1.55f
        val ringCurled = distance(landmarks[16], wrist) / palmSize < 1.15f
        val pinkyCurled = distance(landmarks[20], wrist) / palmSize < 1.15f
        val fingersApart = distance(landmarks[8], landmarks[12]) / palmSize > 0.25f
        return indexExtended && middleExtended && ringCurled && pinkyCurled && fingersApart
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        return hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble()).toFloat()
    }

    private fun reset() {
        currentState = GestureState.IDLE
        lastFilteredHandPos = null
        lastFrameTimeMs = 0L
        wasIndexPinched = false
        isMiddleDragging = false
        fistStartTime = 0L
        peaceStartTime = 0L
        handFilter.reset()
    }
}