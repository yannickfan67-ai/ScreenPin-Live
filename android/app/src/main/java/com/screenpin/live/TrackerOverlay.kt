package com.screenpin.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class TrackerOverlay(context: Context) : View(context) {
    @Volatile var quad: Quad? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(0, 255, 120)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 229, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        val p = q.asList().map { it.x * width to it.y * height }
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) and 3]
            canvas.drawLine(a.first, a.second, b.first, b.second, line)
            canvas.drawCircle(a.first, a.second, 9f, dot)
        }
    }
}
