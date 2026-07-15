package com.yang.testapp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 声纹采集音量动画，柱形高度和流动频率随实时声音能量变化。
 */
class VoiceLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private companion object {
        private const val BAR_COUNT = 32
        private const val MIN_BAR_RATIO = 0.08f
        private const val MAX_BAR_RATIO = 0.86f
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private var targetLevel = 0f
    private var currentLevel = 0f
    private var phase = 0f
    private var recording = false

    init {
        barPaint.color = 0xFF1677FF.toInt()
        centerPaint.color = 0xFFE2E8F0.toInt()
    }

    /**
     * 更新实时音量目标值，取值范围为 0 到 1。
     */
    fun setAudioLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        if (recording) {
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    /**
     * 更新录音状态，停止时让波形自然回落。
     */
    fun setRecording(recording: Boolean) {
        if (this.recording == recording) {
            return
        }
        this.recording = recording
        if (!recording) {
            targetLevel = 0f
        }
        postInvalidateOnAnimation()
    }

    /**
     * 绘制随声音能量起伏和加速的柱形动画。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return
        }

        currentLevel += (targetLevel - currentLevel) * 0.22f
        phase += 0.08f + currentLevel * 0.52f

        val centerY = viewHeight / 2f
        val baselineHeight = 4f
        val horizontalPadding = viewWidth * 0.08f
        centerPaint.alpha = 190
        barRect.set(horizontalPadding, centerY - baselineHeight / 2f, viewWidth - horizontalPadding, centerY + baselineHeight / 2f)
        canvas.drawRoundRect(barRect, baselineHeight, baselineHeight, centerPaint)

        val availableWidth = viewWidth - horizontalPadding * 2f
        val slotWidth = availableWidth / BAR_COUNT
        val barWidth = (slotWidth * 0.42f).coerceAtLeast(4f)
        val maxBarHeight = viewHeight * MAX_BAR_RATIO
        val minBarHeight = viewHeight * MIN_BAR_RATIO
        for (index in 0 until BAR_COUNT) {
            val wave = ((sin(phase + index * 0.58f) + 1f) / 2f).coerceIn(0f, 1f)
            val energy = (0.18f + currentLevel * 0.82f).coerceIn(0f, 1f)
            val heightRatio = (0.35f + wave * 0.65f) * energy
            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * heightRatio
            val left = horizontalPadding + index * slotWidth + (slotWidth - barWidth) / 2f
            val top = centerY - barHeight / 2f
            val right = left + barWidth
            val bottom = centerY + barHeight / 2f
            barPaint.alpha = (95 + 160 * heightRatio).toInt().coerceIn(95, 255)
            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }

        if (recording || currentLevel > 0.01f) {
            postInvalidateOnAnimation()
        }
    }
}
