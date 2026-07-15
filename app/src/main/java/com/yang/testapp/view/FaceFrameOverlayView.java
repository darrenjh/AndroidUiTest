package com.yang.testapp.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 人脸采集取景遮罩，圆形透明区域对应最终图片裁剪范围。
 */
public class FaceFrameOverlayView extends View {
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frameRect = new RectF();
    private final Path maskPath = new Path();
    private boolean faceInside;

    /**
     * 创建代码构造的取景框。
     */
    public FaceFrameOverlayView(Context context) {
        super(context);
        init();
    }

    /**
     * 创建 XML 构造的取景框。
     */
    public FaceFrameOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * 创建带样式的取景框。
     */
    public FaceFrameOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 初始化绘制参数，蓝色代表可采集，红色代表仍需调整。
     */
    private void init() {
        setWillNotDraw(false);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(4));
        borderPaint.setStrokeCap(Paint.Cap.ROUND);
        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(0xFFFFFFFF);
        hintPaint.setStyle(Paint.Style.STROKE);
        hintPaint.setStrokeWidth(dp(10));
        hintPaint.setColor(0x33FF3B30);
    }

    /**
     * 更新人脸是否在取景框内，并触发边框颜色刷新。
     */
    public void setFaceInside(boolean faceInside) {
        if (this.faceInside == faceInside) {
            return;
        }
        this.faceInside = faceInside;
        invalidate();
    }

    /**
     * 返回当前圆形取景框的外接正方形，采集图片按这个区域裁剪。
     */
    public RectF getFrameRect() {
        updateFrameRect();
        return new RectF(frameRect);
    }

    /**
     * 绘制遮罩和圆形取景框。
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateFrameRect();
        maskPath.reset();
        maskPath.setFillType(Path.FillType.EVEN_ODD);
        maskPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        maskPath.addOval(frameRect, Path.Direction.CW);
        canvas.drawPath(maskPath, maskPaint);
        canvas.drawOval(frameRect, hintPaint);
        borderPaint.setColor(faceInside ? 0xFF1677FF : 0xFFFF3B30);
        canvas.drawOval(frameRect, borderPaint);
    }

    /**
     * 取景框随屏幕尺寸自适应，并略微上移以贴近人脸采集的自然视线位置。
     */
    private void updateFrameRect() {
        float horizontalPadding = dp(42);
        float verticalPadding = dp(48);
        float maxWidth = Math.max(0, getWidth() - horizontalPadding * 2);
        float maxHeight = Math.max(0, getHeight() - verticalPadding * 2);
        float preferredSize = Math.min(getWidth() * 0.66f, getHeight() * 0.46f);
        float maxAlipayLikeSize = dp(248);
        float size = Math.min(
                Math.min(preferredSize, maxAlipayLikeSize),
                Math.min(maxWidth, maxHeight)
        );
        if (size <= 0) {
            size = Math.min(getWidth(), getHeight());
        }
        float left = (getWidth() - size) / 2f;
        float centerTop = (getHeight() - size) / 2f;
        float shiftedTop = centerTop - getHeight() * 0.18f;
        float maxTop = Math.max(verticalPadding, getHeight() - verticalPadding - size);
        float top = Math.max(verticalPadding, Math.min(shiftedTop, maxTop));
        frameRect.set(left, top, left + size, top + size);
    }

    /**
     * 将 dp 转为像素，保持边框在不同屏幕密度下观感一致。
     */
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
