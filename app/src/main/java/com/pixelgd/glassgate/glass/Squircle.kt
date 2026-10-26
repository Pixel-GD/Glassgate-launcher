package com.pixelgd.glassgate.glass

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * iOS shapes use continuous-curvature corners instead of circular arcs. These paths
 * approximate them so icons and glass panels read as "Apple" rather than "Material".
 */
object Squircle {

    /** A superellipse filling [w] x [h]; exponent 5 matches the iOS app icon mask closely. */
    fun icon(w: Float, h: Float, out: Path = Path(), n: Double = 5.0, left: Float = 0f, top: Float = 0f): Path {
        out.reset()
        val cx = left + w / 2f
        val cy = top + h / 2f
        val rx = w / 2f
        val ry = h / 2f
        val steps = 96
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = cx + rx * (abs(c).pow(2.0 / n) * Math.signum(c)).toFloat()
            val y = cy + ry * (abs(s).pow(2.0 / n) * Math.signum(s)).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }

    /**
     * A rounded rect whose corners blend smoothly into the edges (Figma "corner smoothing"
     * ~60%, the iOS look). [radius] is the visual corner radius, as you would pass to RoundRect.
     */
    fun roundRect(rect: RectF, radius: Float, out: Path = Path()): Path {
        out.reset()
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        if (r <= 0f) {
            out.addRect(rect, Path.Direction.CW)
            return out
        }
        // The smoothed corner starts further along each edge than a circular one would.
        val reach = min(r * 1.528665f, min(rect.width(), rect.height()) / 2f)
        val k = reach / (r * 1.528665f) // shrink proportionally if the rect is too small
        val a = 1.528665f * r * k
        val b = 1.08849f * r * k
        val c = 0.868407f * r * k
        val d = 0.631494f * r * k
        val e = 0.372824f * r * k
        val f = 0.169060f * r * k
        val g = 0.074911f * r * k
        val l = rect.left
        val t = rect.top
        val rr = rect.right
        val bb = rect.bottom

        out.moveTo(l + a, t)
        out.lineTo(rr - a, t)
        // top-right
        out.cubicTo(rr - b, t, rr - c, t, rr - d, t + g)
        out.cubicTo(rr - e, t + f, rr - f, t + e, rr - g, t + d)
        out.cubicTo(rr, t + c, rr, t + b, rr, t + a)
        out.lineTo(rr, bb - a)
        // bottom-right
        out.cubicTo(rr, bb - b, rr, bb - c, rr - g, bb - d)
        out.cubicTo(rr - f, bb - e, rr - e, bb - f, rr - d, bb - g)
        out.cubicTo(rr - c, bb, rr - b, bb, rr - a, bb)
        out.lineTo(l + a, bb)
        // bottom-left
        out.cubicTo(l + b, bb, l + c, bb, l + d, bb - g)
        out.cubicTo(l + e, bb - f, l + f, bb - e, l + g, bb - d)
        out.cubicTo(l, bb - c, l, bb - b, l, bb - a)
        out.lineTo(l, t + a)
        // top-left
        out.cubicTo(l, t + b, l, t + c, l + g, t + d)
        out.cubicTo(l + f, t + e, l + e, t + f, l + d, t + g)
        out.cubicTo(l + c, t, l + b, t, l + a, t)
        out.close()
        return out
    }
}
