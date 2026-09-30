package com.screenpin.live

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ScreenTracker {
    @Volatile var frozen: Boolean = false
    private var previous: Quad? = null

    fun detect(rgba: Mat): Quad? {
        if (frozen) return previous
        if (rgba.empty()) return previous

        val scale = min(1.0, 560.0 / rgba.cols().toDouble())
        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        } else {
            rgba.copyTo(small)
        }

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 45.0, 130.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val frameArea = small.cols().toDouble() * small.rows().toDouble()
        var best: Array<Point>? = null
        var bestScore = 0.0

        for (contour in contours) {
            val area = abs(Imgproc.contourArea(contour))
            if (area < frameArea * 0.08) {
                contour.release()
                continue
            }
            val c2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, peri * 0.025, true)
            val pts = approx.toArray()
            if (pts.size == 4) {
                val poly = MatOfPoint(*pts)
                if (Imgproc.isContourConvex(poly)) {
                    val ordered = order(pts)
                    val geometry = rectangleQuality(ordered)
                    val areaScore = min(1.0, area / (frameArea * 0.65))
                    val score = areaScore * 0.65 + geometry * 0.35
                    if (score > bestScore) {
                        bestScore = score
                        best = ordered
                    }
                }
                poly.release()
            }
            approx.release()
            c2f.release()
            contour.release()
        }

        hierarchy.release()
        kernel.release()
        edges.release()
        gray.release()

        val result = best?.let { pts ->
            val w = small.cols().toFloat()
            val h = small.rows().toFloat()
            Quad(
                NPoint((pts[0].x / w).toFloat(), (pts[0].y / h).toFloat()),
                NPoint((pts[1].x / w).toFloat(), (pts[1].y / h).toFloat()),
                NPoint((pts[2].x / w).toFloat(), (pts[2].y / h).toFloat()),
                NPoint((pts[3].x / w).toFloat(), (pts[3].y / h).toFloat())
            )
        }
        small.release()

        if (result != null && bestScore > 0.30) {
            previous = smooth(previous, result, 0.34f)
        }
        return previous
    }

    fun clear() { previous = null }

    private fun smooth(old: Quad?, fresh: Quad, a: Float): Quad {
        if (old == null) return fresh
        fun mix(p: NPoint, q: NPoint) = NPoint(
            p.x * (1f - a) + q.x * a,
            p.y * (1f - a) + q.y * a
        )
        return Quad(mix(old.tl, fresh.tl), mix(old.tr, fresh.tr), mix(old.br, fresh.br), mix(old.bl, fresh.bl))
    }

    private fun order(pts: Array<Point>): Array<Point> {
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.maxBy { it.x - it.y }
        val bl = pts.minBy { it.x - it.y }
        return arrayOf(tl, tr, br, bl)
    }

    private fun rectangleQuality(p: Array<Point>): Double {
        fun angleQuality(a: Point, b: Point, c: Point): Double {
            val ux = a.x - b.x
            val uy = a.y - b.y
            val vx = c.x - b.x
            val vy = c.y - b.y
            val den = hypot(ux, uy) * hypot(vx, vy)
            if (den < 1e-6) return 0.0
            return 1.0 - min(1.0, abs((ux * vx + uy * vy) / den))
        }
        var q = 0.0
        for (i in 0..3) q += angleQuality(p[(i + 3) and 3], p[i], p[(i + 1) and 3])
        q /= 4.0

        fun d(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val top = d(p[0], p[1]); val bottom = d(p[3], p[2])
        val left = d(p[0], p[3]); val right = d(p[1], p[2])
        val opp = min(top, bottom) / max(top, bottom).coerceAtLeast(1.0) *
                  min(left, right) / max(left, right).coerceAtLeast(1.0)
        return q * 0.75 + opp * 0.25
    }
}
