package com.example.meeqat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

object MoonRenderer {

    /**
     * Draws the moon for a given elongation (0 = new, 180 = full).
     * In the northern hemisphere a waxing moon is lit on the right and a waning moon on the left.
     */
    fun render(elongationDeg: Double, northern: Boolean, size: Int = 120): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = size * 0.44f

        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14151F.toInt() }
        val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E6CF.toInt() }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.02f
            color = 0xFF3A3C55.toInt()
        }
        c.drawCircle(cx, cx, r, dark)

        val waxing = elongationDeg < 180.0
        val a = (r * cos(Math.toRadians(elongationDeg))).toFloat() // terminator half-width, signed

        val path = Path()
        path.arcTo(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 180f, true) // lit limb (right side)
        val aw = max(abs(a), 0.5f)
        path.arcTo(RectF(cx - aw, cx - r, cx + aw, cx + r), 90f, if (a > 0) -180f else 180f, false)
        path.close()

        c.save()
        if (waxing != northern) c.scale(-1f, 1f, cx, cx)
        c.drawPath(path, lit)
        c.restore()

        c.drawCircle(cx, cx, r, rim)
        return bmp
    }
}
