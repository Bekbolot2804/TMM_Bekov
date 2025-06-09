package com.example.occlusions.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class HeadAxesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintYaw = Paint().apply {
        color = Color.BLUE
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintPitch = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintRoll = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 48f
        isAntiAlias = true
    }

    // Центр головы и углы
    private var centerX = 0f
    private var centerY = 0f
    private var yaw = 0f   // В градусах
    private var pitch = 0f // В градусах
    private var roll = 0f  // В градусах

    fun updateAxes(centerX: Float, centerY: Float, yaw: Float, pitch: Float, roll: Float) {
        this.centerX = centerX
        this.centerY = centerY
        this.yaw = yaw
        this.pitch = pitch
        this.roll = roll
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Длина осей
        val axisLength = 200f

        // Переводим углы в радианы
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val rollRad = Math.toRadians(roll.toDouble())

        // Yaw — вертикальная ось (синий)
        val yawX = centerX
        val yawY = centerY - axisLength * cos(yawRad).toFloat()
        canvas.drawLine(centerX, centerY, yawX, yawY, paintYaw)
        canvas.drawText("Yaw", yawX + 10, yawY - 10, textPaint)

        // Pitch — горизонтальная ось (зелёный)
        val pitchX = centerX + axisLength * cos(pitchRad).toFloat()
        val pitchY = centerY
        canvas.drawLine(centerX, centerY, pitchX, pitchY, paintPitch)
        canvas.drawText("Pitch", pitchX + 10, pitchY + 10, textPaint)

        // Roll — ось вперёд-назад (красный)
        val rollX = centerX
        val rollY = centerY + axisLength * sin(rollRad).toFloat()
        canvas.drawLine(centerX, centerY, rollX, rollY, paintRoll)
        canvas.drawText("Roll", rollX + 10, rollY + 10, textPaint)
    }
} 