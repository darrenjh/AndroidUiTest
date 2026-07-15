package com.yang.testapp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Kotlin 版人脸采集取景框，负责白色遮罩和圆形边框绘制。
 */
class FaceCollectFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frameRect = RectF()
    private val maskPath = Path()
    private var faceInside = false

    init {
        setWillNotDraw(false)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = dp(4f)
        borderPaint.strokeCap = Paint.Cap.ROUND
        maskPaint.style = Paint.Style.FILL
        maskPaint.color = 0xFFFFFFFF.toInt()
        hintPaint.style = Paint.Style.STROKE
        hintPaint.strokeWidth = dp(10f)
        hintPaint.color = 0x33FF3B30
    }

    /**
     * 更新人脸是否处于取景框内，用边框颜色反馈采集状态。
     */
    fun setFaceInside(faceInside: Boolean) {
        if (this.faceInside == faceInside) {
            return
        }
        this.faceInside = faceInside
        invalidate()
    }

    /**
     * 返回当前圆形取景框外接矩形，用于相机层按视觉区域裁剪。
     */
    fun getFrameRect(): RectF {
        updateFrameRect()
        return RectF(frameRect)
    }

    /**
     * 绘制白色遮罩和圆形取景框。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateFrameRect()
        maskPath.reset()
        maskPath.fillType = Path.FillType.EVEN_ODD
        maskPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        maskPath.addOval(frameRect, Path.Direction.CW)
        canvas.drawPath(maskPath, maskPaint)
        canvas.drawOval(frameRect, hintPaint)
        borderPaint.color = if (faceInside) 0xFF1677FF.toInt() else 0xFFFF3B30.toInt()
        canvas.drawOval(frameRect, borderPaint)
    }

    /**
     * 按支付宝式中等尺寸取景框计算圆形区域，并保持整体偏上。
     */
    private fun updateFrameRect() {
        val horizontalPadding = dp(42f)
        val verticalPadding = dp(48f)
        val maxWidth = (width - horizontalPadding * 2).coerceAtLeast(0f)
        val maxHeight = (height - verticalPadding * 2).coerceAtLeast(0f)
        val preferredSize = minOf(width * 0.66f, height * 0.46f)
        val maxAlipayLikeSize = dp(248f)
        var size = minOf(preferredSize, maxAlipayLikeSize, maxWidth, maxHeight)
        if (size <= 0f) {
            size = minOf(width, height).toFloat()
        }
        val left = (width - size) / 2f
        val centerTop = (height - size) / 2f
        val shiftedTop = centerTop - height * 0.18f
        val maxTop = maxOf(verticalPadding, height - verticalPadding - size)
        val top = shiftedTop.coerceIn(verticalPadding, maxTop)
        frameRect.set(left, top, left + size, top + size)
    }

    /**
     * 将 dp 转为像素，保证不同屏幕密度下取景框尺寸稳定。
     */
    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
