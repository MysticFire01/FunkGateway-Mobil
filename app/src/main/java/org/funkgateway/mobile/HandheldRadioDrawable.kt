package org.funkgateway.mobile

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

class HandheldRadioDrawable(
    private val radioColor: Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 64f, bounds.height() / 96f)

        fun rectangle(
            left: Float, top: Float,
            right: Float, bottom: Float,
            radius: Float, color: Int
        ) {
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                RectF(left, top, right, bottom),
                radius, radius, paint
            )
        }

        rectangle(16f, 2f, 22f, 38f, 3f, radioColor)
        rectangle(23f, 21f, 42f, 28f, 2f, radioColor)
        rectangle(12f, 28f, 52f, 92f, 7f, radioColor)
        rectangle(18f, 38f, 46f, 57f, 3f, Color.rgb(23, 33, 43))

        paint.color = Color.rgb(23, 33, 43)
        canvas.drawCircle(32f, 68f, 5f, paint)

        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(22f, 80f, 42f, 80f, paint)
        canvas.drawLine(22f, 84f, 42f, 84f, paint)

        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(
        colorFilter: android.graphics.ColorFilter?
    ) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
