package com.example.handtrackerapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

class PointerOverlayView(context: Context) : View(context) {
    private var output: GestureOutput? = null
    private var renderX = -1f
    private var renderY = -1f

    // ปรับเป็น 0.82f เพื่อไม่ให้หน่วงซ้ำซ้อนกับ One Euro Filter (ติดนิ้วทันที)
    private val renderLerpFactor = 0.82f

    private var rippleRadius = 0f
    private var rippleAlpha = 0
    private var rippleX = 0f
    private var rippleY = 0f

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 4f, Color.argb(110, 0, 0, 0))
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    fun updateState(newOutput: GestureOutput) {
        output = newOutput
        if (newOutput.isClicked && renderX >= 0f) {
            rippleX = renderX
            rippleY = renderY
            rippleRadius = 15f
            rippleAlpha = 255
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(rippleX, rippleY, rippleRadius, ripplePaint)
            rippleRadius += 6f
            rippleAlpha = (rippleAlpha - 18).coerceAtLeast(0)
            postInvalidateOnAnimation()
        }

        val data = output ?: return
        val targetPos = data.pointerPosition ?: run {
            renderX = -1f
            renderY = -1f
            return
        }

        if (data.state == GestureState.IDLE) return

        val targetX = targetPos.x * width
        val targetY = targetPos.y * height

        if (renderX < 0f || renderY < 0f) {
            renderX = targetX
            renderY = targetY
        } else {
            renderX += (targetX - renderX) * renderLerpFactor
            renderY += (targetY - renderY) * renderLerpFactor
        }

        val baseRadius = SettingsManager.settings.value.pointerRadius
        canvas.drawCircle(renderX, renderY, baseRadius + 7f, outerPaint)

        innerPaint.color = when {
            data.state == GestureState.FIST_HOLD -> Color.RED
            data.state == GestureState.BACK_HOLD -> Color.GREEN
            data.state == GestureState.DRAGGING -> Color.MAGENTA
            data.isPositionLocked -> Color.parseColor("#FF9100")
            else -> Color.parseColor("#00E5FF")
        }
        canvas.drawCircle(renderX, renderY, baseRadius, innerPaint)

        if (abs(targetX - renderX) > 0.5f || abs(targetY - renderY) > 0.5f) {
            postInvalidateOnAnimation()
        }
    }
}