package org.funkgateway.mobile.frn

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FrnChatBubble {
    fun create(context: Context, entry: FrnChatStore.Entry): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val outgoing = entry.outgoing
        val bubbleColor = if (outgoing) {
            Color.rgb(18, 103, 98)
        } else {
            Color.rgb(48, 64, 80)
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(bubbleColor)
                cornerRadii = if (outgoing) {
                    floatArrayOf(
                        dp(14).toFloat(), dp(14).toFloat(),
                        dp(14).toFloat(), dp(14).toFloat(),
                        dp(4).toFloat(), dp(4).toFloat(),
                        dp(14).toFloat(), dp(14).toFloat()
                    )
                } else {
                    floatArrayOf(
                        dp(14).toFloat(), dp(14).toFloat(),
                        dp(14).toFloat(), dp(14).toFloat(),
                        dp(14).toFloat(), dp(14).toFloat(),
                        dp(4).toFloat(), dp(4).toFloat()
                    )
                }
            }
        }

        val time = SimpleDateFormat(
            "HH:mm", Locale.GERMANY
        ).format(Date(entry.time))

        bubble.addView(TextView(context).apply {
            text = "${entry.sender} · $time"
            textSize = 12f
            setTextColor(
                if (outgoing) Color.rgb(186, 250, 245)
                else Color.rgb(195, 212, 228)
            )
            setTypeface(null, Typeface.BOLD)
        })

        bubble.addView(TextView(context).apply {
            text = entry.message
            textSize = 16f
            setTextColor(Color.WHITE)
            if (outgoing) {
                setTypeface(null, Typeface.BOLD)
            }
            setPadding(0, dp(5), 0, 0)
        })

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (outgoing) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(
                bubble,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0.85f
                ).apply {
                    width = LinearLayout.LayoutParams.WRAP_CONTENT
                }
            )
        }
    }
}
