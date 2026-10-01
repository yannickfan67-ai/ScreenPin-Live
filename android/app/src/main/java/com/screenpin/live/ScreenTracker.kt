package com.screenpin.live

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ScreenTracker {
    @Volatile var frozen: Boolean = false

    private var previous: Quad? = null
    private var previousGray: Mat? = null
    private var previousPoints: MatOfPoint2f? = null
    private var frameIndex = 0
    private var misses = 0

    fun detect(rgba: Mat): Quad? {
        if (rgba.empty()) return previous
        if (frozen) return previous

        val scale = min(1.0, 640.0 / rgba.cols().toDouble())
        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        } else {
            rgba.copyTo(small)
        }

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        frameIndex++

        var candidate: Quad? = null
        var trackedPoints: MatOfPoint2f? = null

        val oldQuad = previous
        val oldGray = previousGray
        val oldPoints = previousPoints
        if (oldQuad != null && oldGray != null && oldPoints != null &&
            oldGray.cols() == gray.cols() && oldGray.rows() == gray.rows() &&
            oldPoints.total() >= 8
        ) {
            val tracked = track(oldGray, gray, oldPoints, oldQuad)
            candidate = tracked.first
            trackedPoints = tracked.second
        }

        // Re-detect only when tracking is unavailable, or occasionally to remove drift.
        val shouldDetect = candidate == null || frameIndex % 18 == 0
        val detected = if (shouldDetect) detectRectangle(gray) else null

        if (candidate == null && detected != null) {
            candidate = detected
        } else if (candidate != null && detected != null && cornerDistance(candidate, detected) < 0.11f) {
            // Optical flow controls motion; contour detection only gently corrects accumulated drift.
            candidate = smooth(candidate, detected, 0.14f)
        }

        if (candidate != null && isValid(candidate)) {
            misses = 0
            previous = if (oldQuad == null) candidate else smooth(oldQuad, candidate, 0.72f)

            previousGray?.release()
            previousGray = gray.clone()

            previousPoints?.release()
            previousPoints = if (trackedPoints != null && trackedPoints.total() >= 28 && frameIndex % 18 != 0) {
                trackedPoints
            } else {
                trackedPoints?.release()
                seedFeatures(gray, previous!!)
            }
        } else {
            trackedPoints?.release()
            misses++
            // Keep the overlay briefly instead of making it blink if one frame is bad.
            if (misses > 4) {
                previousPoints?.release()
                previousPoints = null
                previousGray?.release()
                previousGray = null
            }
        }

        gray.release()
        small.release()
        return previous
    }

    fun clear() {
        previous = null
        previousGray?.release()
        previousGray = null
        previousPoints?.release()
        previousPoints = null
        misses = 0
        frameIndex = 0
    }

    private fun track(
        oldGray: Mat,
        gray: Mat,
        oldPoints: MatOfPoint2f,
        oldQuad: Quad
    ): Pair<Quad?, MatOfPoint2f?> {
        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val error = MatOfFloat()

        try {
            Video.calcOpticalFlowPyrLK(
                oldGray,
                gray,
                oldPoints,
                nextPoints,
                status,
                error,
                Size(21.0, 21.0),
                3,
                TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 30, 0.01),
                0,
                0.001
            )

            val oldArray = oldPoints.toArray()
            val nextArray = nextPoints.toArray()
            val statusArray = status.toArray()
            val errorArray = error.toArray()

            val goodOld = ArrayList<Point>()
            val goodNew = ArrayList<Point>()
            val w = gray.cols().toDouble()
            val h = gray.rows().toDouble()

            for (i in oldArray.indices) {
                if (i >= statusArray.size || i >= nextArray.size) break
                val ok = statusArray[i].toInt() != 0
                val e = if (i < errorArray.size) errorArray[i] else 0f
                val p = nextArray[i]
                if (ok && e < 45f && p.x >= -8 && p.y >= -8 && p.x <= w + 8 && p.y <= h + 8) {
                    goodOld += oldArray[i]
                    goodNew += p
                }
            }

            if (goodOld.size < 8) return null to null

            val src = MatOfPoint2f(*goodOld.toTypedArray())
            val dst = MatOfPoint2f(*goodNew.toTypedArray())
            val homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0)
            src.release()
            if (homography.empty()) {
                dst.release()
                homography.release()
                return null to null
            }

            val cornersIn = MatOfPoint2f(*quadToPixels(oldQuad, gray.cols(), gray.rows()))
            val cornersOut = MatOfPoint2f()
            Core.perspectiveTransform(cornersIn, cornersOut, homography)
            val transformed = pixelsToQuad(cornersOut.toArray(), gray.cols(), gray.rows())

            cornersIn.release()
            cornersOut.release()
            homography.release()

            if (!isValid(transformed) || cornerDistance(oldQuad, transformed) > 0.35f) {
                dst.release()
                return null to null
            }
            return transformed to dst
        } catch (_: Throwable) {
            return null to null
        } finally {
            nextPoints.release()
            status.release()
            error.release()
        }
    }

    private fun seedFeatures(gray: Mat, quad: Quad): MatOfPoint2f {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val polygon = MatOfPoint(*quadToPixels(quad, gray.cols(), gray.rows()))
        Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0))

        val features = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
            gray,
            features,
            140,
            0.008,
            7.0,
            mask,
            5,
            false,
            0.04
        )

        val result = MatOfPoint2f(*features.toArray())
        features.release()
        polygon.release()
        mask.release()
        return result
    }

    private fun detectRectangle(gray: Mat): Quad? {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 45.0, 130.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val frameArea = gray.cols().toDouble() * gray.rows().toDouble()
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
        blurred.release()

        if (bestScore <= 0.30) return null
        return best?.let { pixelsToQuad(it, gray.cols(), gray.rows()) }
    }

    private fun quadToPixels(q: Quad, w: Int, h: Int): Array<Point> = arrayOf(
        Point(q.tl.x * w, q.tl.y * h),
        Point(q.tr.x * w, q.tr.y * h),
        Point(q.br.x * w, q.br.y * h),
        Point(q.bl.x * w, q.bl.y * h)
    )

    private fun pixelsToQuad(p: Array<Point>, w: Int, h: Int): Quad {
        if (p.size < 4) return Quad.FULL
        return Quad(
            NPoint((p[0].x / w).toFloat(), (p[0].y / h).toFloat()),
            NPoint((p[1].x / w).toFloat(), (p[1].y / h).toFloat()),
            NPoint((p[2].x / w).toFloat(), (p[2].y / h).toFloat()),
            NPoint((p[3].x / w).toFloat(), (p[3].y / h).toFloat())
        )
    }

    private fun isValid(q: Quad): Boolean {
        val p = q.asList()
        if (p.any { it.x < -0.08f || it.x > 1.08f || it.y < -0.08f || it.y > 1.08f }) return false

        var twiceArea = 0f
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) and 3]
            twiceArea += a.x * b.y - b.x * a.y
        }
        if (abs(twiceArea) * 0.5f < 0.035f) return false

        var sign = 0
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) and 3]
            val c = p[(i + 2) and 3]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun cornerDistance(a: Quad, b: Quad): Float {
        val ap = a.asList()
        val bp = b.asList()
        var sum = 0.0
        for (i in 0..3) sum += hypot((ap[i].x - bp[i].x).toDouble(), (ap[i].y - bp[i].y).toDouble())
        return (sum / 4.0).toFloat()
    }

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
        val top = d(p[0], p[1])
        val bottom = d(p[3], p[2])
        val left = d(p[0], p[3])
        val right = d(p[1], p[2])
        val opp = min(top, bottom) / max(top, bottom).coerceAtLeast(1.0) *
            min(left, right) / max(left, right).coerceAtLeast(1.0)
        return q * 0.75 + opp * 0.25
    }
}
