package com.intplatinum.mv.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 支持缩放和拖动的ImageView
 * 功能：
 * - 双指缩放
 * - 单指拖动
 * - 双击缩放
 * - 边界检测
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr), View.OnTouchListener {

    companion object {
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 5.0f
        private const val DOUBLE_TAP_SCALE = 2.0f
    }

    // 变换矩阵
    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    // 触摸模式
    private enum class Mode {
        NONE, DRAG, ZOOM
    }

    private var mode = Mode.NONE

    // 触摸点
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f
    private var oldRotation = 0f

    // 缩放相关
    private var currentScale = 1.0f
    private var minScale = MIN_SCALE
    private var maxScale = MAX_SCALE

    // 手势检测器
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    // 图片尺寸
    private var imageWidth = 0f
    private var imageHeight = 0f
    private var viewWidth = 0f
    private var viewHeight = 0f

    init {
        scaleType = ScaleType.MATRIX
        setOnTouchListener(this)

        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetImageMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
        resetImageMatrix()
    }

    /**
     * 重置图片矩阵到初始状态
     */
    private fun resetImageMatrix() {
        val drawable = drawable ?: return
        
        imageWidth = drawable.intrinsicWidth.toFloat()
        imageHeight = drawable.intrinsicHeight.toFloat()
        
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        // 计算初始缩放比例，使图片适应屏幕
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        val initialScale = min(scaleX, scaleY)
        
        minScale = initialScale
        currentScale = initialScale
        
        matrix.reset()
        matrix.setScale(initialScale, initialScale)
        
        // 居中显示
        val dx = (viewWidth - imageWidth * initialScale) / 2
        val dy = (viewHeight - imageHeight * initialScale) / 2
        matrix.postTranslate(dx, dy)
        
        imageMatrix = matrix
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(curr)
                mode = Mode.DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = Mode.ZOOM
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAG -> {
                        if (!scaleDetector.isInProgress) {
                            matrix.set(savedMatrix)
                            val dx = curr.x - start.x
                            val dy = curr.y - start.y
                            matrix.postTranslate(dx, dy)
                            limitTranslation()
                        }
                    }
                    Mode.ZOOM -> {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMatrix)
                            val scale = newDist / oldDist
                            matrix.postScale(scale, scale, mid.x, mid.y)
                            limitScale()
                        }
                    }
                    else -> {}
                }
            }
        }

        imageMatrix = matrix
        return true
    }

    /**
     * 计算两点间距离
     */
    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    /**
     * 计算两点中点
     */
    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    /**
     * 限制缩放范围
     */
    private fun limitScale() {
        val values = FloatArray(9)
        matrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        val scale = kotlin.math.sqrt(scaleX * scaleX + scaleY * scaleY)
        
        currentScale = scale
        
        if (scale < minScale) {
            val ratio = minScale / scale
            matrix.postScale(ratio, ratio, viewWidth / 2, viewHeight / 2)
            currentScale = minScale
        } else if (scale > maxScale) {
            val ratio = maxScale / scale
            matrix.postScale(ratio, ratio, viewWidth / 2, viewHeight / 2)
            currentScale = maxScale
        }
        
        limitTranslation()
    }

    /**
     * 限制平移范围
     */
    private fun limitTranslation() {
        val values = FloatArray(9)
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        
        val scaledImageWidth = imageWidth * scaleX
        val scaledImageHeight = imageHeight * scaleY
        
        var deltaX = 0f
        var deltaY = 0f
        
        // 水平边界检测
        if (scaledImageWidth <= viewWidth) {
            // 图片宽度小于视图宽度，居中显示
            deltaX = (viewWidth - scaledImageWidth) / 2 - transX
        } else {
            // 图片宽度大于视图宽度，限制边界
            if (transX > 0) {
                deltaX = -transX
            } else if (transX < viewWidth - scaledImageWidth) {
                deltaX = viewWidth - scaledImageWidth - transX
            }
        }
        
        // 垂直边界检测
        if (scaledImageHeight <= viewHeight) {
            // 图片高度小于视图高度，居中显示
            deltaY = (viewHeight - scaledImageHeight) / 2 - transY
        } else {
            // 图片高度大于视图高度，限制边界
            if (transY > 0) {
                deltaY = -transY
            } else if (transY < viewHeight - scaledImageHeight) {
                deltaY = viewHeight - scaledImageHeight - transY
            }
        }
        
        matrix.postTranslate(deltaX, deltaY)
    }

    /**
     * 缩放手势监听器
     */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            limitScale()
            imageMatrix = matrix
            return true
        }
    }

    /**
     * 手势监听器（处理双击等）
     */
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (currentScale > minScale * 1.5f) {
                minScale
            } else {
                min(DOUBLE_TAP_SCALE, maxScale)
            }
            
            val scaleFactor = targetScale / currentScale
            matrix.postScale(scaleFactor, scaleFactor, e.x, e.y)
            limitScale()
            imageMatrix = matrix
            return true
        }
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 单击事件，可以用于隐藏/显示UI
            performClick()
            return true
        }
    }
}