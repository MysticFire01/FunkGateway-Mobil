package org.funkgateway.mobile

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class FrnClientTypeDrawable(
    private val clientType: Int,
    private val iconColor: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val checkpoint = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 64f, b.height() / 96f)
        paint.color = iconColor

        if (clientType == 0) {
            // Zwei sich kreuzende Pfeile
            canvas.drawLine(8f, 25f, 56f, 71f, paint)
            canvas.drawLine(8f, 71f, 56f, 25f, paint)

            for (y in listOf(25f, 71f)) {
                val direction = if (y == 25f) 1f else -1f
                val path = Path().apply {
                    moveTo(43f, y)
                    lineTo(56f, y)
                    lineTo(56f, y + 13f * direction)
                }
                canvas.drawPath(path, paint)
            }
        } else {
            // Headset für PC-Only
            canvas.drawArc(
                10f, 19f, 54f, 69f,
                180f, 180f, false, paint
            )
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(6f, 48f, 18f, 72f, 4f, 4f, paint)
            canvas.drawRoundRect(46f, 48f, 58f, 72f, 4f, 4f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawArc(
                25f, 62f, 54f, 83f,
                0f, 100f, false, paint
            )
            paint.style = Paint.Style.FILL
            canvas.drawCircle(32f, 82f, 4f, paint)
            paint.style = Paint.Style.STROKE
        }

        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
