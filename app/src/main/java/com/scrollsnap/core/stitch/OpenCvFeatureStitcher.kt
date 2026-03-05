package com.scrollsnap.core.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Rect as CvRect
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OpenCvFeatureStitcher(
    private val fallbackStitcher: NativeFeatureStitcher = NativeFeatureStitcher(),
    private val tuningProvider: () -> StitchTuning = { StitchTuning() }
) {

    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        initialized = OpenCVLoader.initDebug()
        return initialized
    }

    fun stitchSequence(frames: List<Bitmap>): StitchResult {
        if (frames.size < 2) {
            return StitchResult(false, 0, null, "Need at least 2 frames.")
        }
        if (!ensureInitialized()) {
            return StitchResult(false, 0, null, "OpenCV initialization failed.")
        }

        var current = frames.first()
        var totalOverlap = 0
        for (i in 1 until frames.size) {
            val next = frames[i]
            if (current.width != next.width) {
                return StitchResult(false, totalOverlap, null, "Frame widths are inconsistent.")
            }

            val centerOverlap = estimateOverlapByCenterCorrelation(current, next)
            val orbOverlap = estimateOverlapByOrb(current, next)
            val nativeOverlap = fallbackStitcher.estimateVerticalOverlap(current, next, 4, 4)
            val overlap = chooseSafeOverlap(
                top = current,
                bottom = next,
                candidates = listOfNotNull(centerOverlap, orbOverlap, nativeOverlap)
            )
            if (overlap <= 0 || overlap >= min(current.height, next.height)) {
                return StitchResult(false, totalOverlap, null, "Invalid overlap at frame index $i.")
            }
            val conservativeOverlap = adjustOverlapConservatively(current, overlap)
            val seam = estimateBestSeam(current, next, conservativeOverlap)
            current = mergeByOverlapWithSeam(current, next, conservativeOverlap, seam)
            totalOverlap += conservativeOverlap
        }

        return StitchResult(
            success = true,
            overlapPx = totalOverlap,
            mergedBitmap = current,
            message = "OpenCV stitch success for ${frames.size} frames."
        )
    }

    private fun mergeByOverlapWithSeam(top: Bitmap, bottom: Bitmap, overlap: Int, seam: Int): Bitmap {
        val topKeepHeight = top.height - overlap + seam
        val bottomStart = seam
        val mergedHeight = topKeepHeight + (bottom.height - bottomStart)
        val out = Bitmap.createBitmap(top.width, mergedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(top, 0f, 0f, null)
        val src = AndroidRect(0, bottomStart, bottom.width, bottom.height)
        val dst = AndroidRect(0, topKeepHeight, bottom.width, mergedHeight)
        canvas.drawBitmap(bottom, src, dst, null)
        return out
    }

    private fun estimateBestSeam(top: Bitmap, bottom: Bitmap, overlap: Int): Int {
        val width = top.width
        val topStartY = top.height - overlap
        val topPixels = IntArray(top.width * top.height)
        val bottomPixels = IntArray(bottom.width * bottom.height)
        top.getPixels(topPixels, 0, top.width, 0, 0, top.width, top.height)
        bottom.getPixels(bottomPixels, 0, bottom.width, 0, 0, bottom.width, bottom.height)

        val rowSampleStep = 2
        val colSampleStep = 4
        val window = 8
        val minSeam = max(4, overlap / 12)
        val maxSeam = min(overlap - 4, overlap - overlap / 12)

        var bestScore = Double.MAX_VALUE
        var bestSeam = overlap / 2

        var seam = minSeam
        while (seam <= maxSeam) {
            val from = max(0, seam - window)
            val to = min(overlap - 1, seam + window)
            var sum = 0.0
            var count = 0

            var y = from
            while (y <= to) {
                val topY = topStartY + y
                val bottomY = y
                var x = 0
                while (x < width) {
                    val c1 = topPixels[topY * width + x]
                    val c2 = bottomPixels[bottomY * width + x]
                    val dr = ((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)
                    val dg = ((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)
                    val db = (c1 and 0xFF) - (c2 and 0xFF)
                    sum += kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
                    count++
                    x += colSampleStep
                }
                y += rowSampleStep
            }

            if (count > 0) {
                val mean = sum / count
                val centerPenalty = kotlin.math.abs(seam - overlap / 2) * 0.15
                val score = mean + centerPenalty
                if (score < bestScore) {
                    bestScore = score
                    bestSeam = seam
                }
            }

            seam += 1
        }

        return bestSeam.coerceIn(1, overlap - 1)
    }

    private fun estimateOverlapByCenterCorrelation(top: Bitmap, bottom: Bitmap): Int? {
        if (top.width != bottom.width || top.height != bottom.height) return null
        val w = top.width
        val h = top.height
        if (h < 120 || w < 120) return null

        val topPixels = IntArray(w * h)
        val bottomPixels = IntArray(w * h)
        top.getPixels(topPixels, 0, w, 0, 0, w, h)
        bottom.getPixels(bottomPixels, 0, w, 0, 0, w, h)

        val centerStartX = (w * 0.30f).toInt()
        val centerEndX = (w * 0.70f).toInt()
        val colStep = 3

        fun rowDiffAtSameY(y: Int): Double {
            var sum = 0.0
            var c = 0
            var x = centerStartX
            while (x < centerEndX) {
                val p1 = topPixels[y * w + x]
                val p2 = bottomPixels[y * w + x]
                val d = kotlin.math.abs((p1 and 0xFF) - (p2 and 0xFF)) +
                    kotlin.math.abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)) +
                    kotlin.math.abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
                sum += d
                c++
                x += colStep
            }
            return if (c == 0) 9999.0 else sum / c
        }

        val sameYThreshold = 8.0
        var staticTop = 0
        while (staticTop < h / 4 && rowDiffAtSameY(staticTop) < sameYThreshold) staticTop++
        var staticBottom = 0
        while (staticBottom < h / 4 && rowDiffAtSameY(h - 1 - staticBottom) < sameYThreshold) staticBottom++

        val topIgnore = staticTop.coerceAtMost(h / 4)
        val bottomIgnore = staticBottom.coerceAtMost(h / 4)
        val usableTop = topIgnore
        val usableBottom = h - bottomIgnore
        if (usableBottom - usableTop < h / 2) return null

        val topSig = DoubleArray(usableBottom - usableTop)
        val bottomSig = DoubleArray(usableBottom - usableTop)

        for (idx in topSig.indices) {
            val y = usableTop + idx
            topSig[idx] = rowSignature(topPixels, w, y, centerStartX, centerEndX, colStep)
            bottomSig[idx] = rowSignature(bottomPixels, w, y, centerStartX, centerEndX, colStep)
        }

        val minOverlap = max(48, topSig.size / 8)
        val maxOverlap = min(topSig.size - 2, (topSig.size * 0.92f).toInt())
        if (minOverlap >= maxOverlap) return null

        var best = minOverlap
        var bestScore = Double.MAX_VALUE
        for (ov in minOverlap..maxOverlap) {
            val margin = max(2, ov / 12)
            val compareCount = ov - 2 * margin
            if (compareCount < 20) continue

            var sum = 0.0
            for (i in 0 until compareCount) {
                val a = topSig[topSig.size - ov + margin + i]
                val b = bottomSig[margin + i]
                sum += kotlin.math.abs(a - b)
            }
            val mean = sum / compareCount
            val centerPenalty = kotlin.math.abs(ov - topSig.size / 2) * 0.08
            val score = mean + centerPenalty
            if (score < bestScore) {
                bestScore = score
                best = ov
            }
        }

        return best
    }

    private fun chooseSafeOverlap(top: Bitmap, bottom: Bitmap, candidates: List<Int>): Int {
        val h = min(top.height, bottom.height)
        val validCandidates = candidates
            .distinct()
            .filter { it in 1 until h }
        if (validCandidates.isEmpty()) return max(1, h / 2)

        val minCand = validCandidates.minOrNull() ?: (h / 3)
        val maxCand = validCandidates.maxOrNull() ?: (h / 2)
        val from = (minCand - 40).coerceIn(1, h - 2)
        val to = (maxCand + 40).coerceIn(2, h - 1)

        val topPixels = IntArray(top.width * top.height)
        val bottomPixels = IntArray(bottom.width * bottom.height)
        top.getPixels(topPixels, 0, top.width, 0, 0, top.width, top.height)
        bottom.getPixels(bottomPixels, 0, bottom.width, 0, 0, bottom.width, bottom.height)

        var bestScore = Double.MAX_VALUE
        val scored = mutableListOf<Pair<Int, Double>>()
        for (ov in from..to) {
            val score = overlapScore(top, bottom, topPixels, bottomPixels, ov)
            if (score.isFinite()) {
                scored += ov to score
                if (score < bestScore) bestScore = score
            }
        }
        if (scored.isEmpty()) return minCand

        // Anti-cut strategy: choose a lower-quantile overlap (not absolute minimum)
        // to reduce duplicate seams while still being conservative against content loss.
        val tuning = tuningProvider()
        val tolerance = bestScore * tuning.toleranceMultiplier
        val nearBest = scored.filter { it.second <= tolerance }.map { it.first }.sorted()
        if (nearBest.isNotEmpty()) {
            val idx = ((nearBest.size - 1) * tuning.overlapQuantile).toInt().coerceIn(0, nearBest.size - 1)
            return nearBest[idx]
        }
        return scored.minByOrNull { it.second }!!.first
    }

    private fun overlapScore(
        top: Bitmap,
        bottom: Bitmap,
        topPixels: IntArray,
        bottomPixels: IntArray,
        overlap: Int
    ): Double {
        val w = min(top.width, bottom.width)
        val h = min(top.height, bottom.height)
        if (overlap !in 1 until h) return Double.POSITIVE_INFINITY

        val x0 = (w * 0.28f).toInt()
        val x1 = (w * 0.72f).toInt()
        val rowStep = 3
        val colStep = 6
        val topStartY = top.height - overlap

        var sum = 0.0
        var count = 0
        var y = 0
        while (y < overlap) {
            val ty = topStartY + y
            val by = y
            var x = x0
            while (x < x1) {
                val p1 = topPixels[ty * top.width + x]
                val p2 = bottomPixels[by * bottom.width + x]
                val dr = ((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)
                val dg = ((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)
                val db = (p1 and 0xFF) - (p2 and 0xFF)
                sum += kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
                count++
                x += colStep
            }
            y += rowStep
        }
        return if (count == 0) Double.POSITIVE_INFINITY else sum / count
    }

    private fun rowSignature(
        pixels: IntArray,
        w: Int,
        y: Int,
        x0: Int,
        x1: Int,
        step: Int
    ): Double {
        var sum = 0.0
        var count = 0
        var x = x0
        while (x < x1) {
            val p = pixels[y * w + x]
            val gray = ((p and 0xFF) * 0.114) + (((p shr 8) and 0xFF) * 0.587) + (((p shr 16) and 0xFF) * 0.299)
            sum += gray
            count++
            x += step
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun estimateOverlapByOrb(top: Bitmap, bottom: Bitmap): Int? {
        if (top.config != Bitmap.Config.ARGB_8888 || bottom.config != Bitmap.Config.ARGB_8888) return null

        val topMat = Mat()
        val bottomMat = Mat()
        var topRoi: Mat? = null
        var bottomRoi: Mat? = null
        var topGray: Mat? = null
        var bottomGray: Mat? = null
        var kp1: MatOfKeyPoint? = null
        var kp2: MatOfKeyPoint? = null
        var desc1: Mat? = null
        var desc2: Mat? = null
        var matches: MatOfDMatch? = null
        Utils.bitmapToMat(top, topMat)
        Utils.bitmapToMat(bottom, bottomMat)

        try {
            val roiHeight = min(top.height, bottom.height) / 2
            if (roiHeight < 80) return null

            val topRoiStart = top.height - roiHeight
            topRoi = Mat(topMat, CvRect(0, topRoiStart, top.width, roiHeight))
            bottomRoi = Mat(bottomMat, CvRect(0, 0, bottom.width, roiHeight))

            topGray = Mat()
            bottomGray = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(topRoi!!, topGray!!, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            org.opencv.imgproc.Imgproc.cvtColor(bottomRoi!!, bottomGray!!, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)

            val orb = ORB.create(1500)
            kp1 = MatOfKeyPoint()
            kp2 = MatOfKeyPoint()
            desc1 = Mat()
            desc2 = Mat()
            orb.detectAndCompute(topGray!!, Mat(), kp1!!, desc1!!)
            orb.detectAndCompute(bottomGray!!, Mat(), kp2!!, desc2!!)

            if (desc1!!.empty() || desc2!!.empty()) return null
            if (desc1.type() != CvType.CV_8U || desc2.type() != CvType.CV_8U) return null

            val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
            matches = MatOfDMatch()
            matcher.match(desc1, desc2, matches!!)

            val good = matches!!.toArray()
                .sortedBy { it.distance }
                .take(120)
            if (good.size < 10) return null

            val keypoints1 = kp1!!.toArray()
            val keypoints2 = kp2!!.toArray()
            val overlaps = mutableListOf<Int>()
            for (m in good) {
                val overlap = overlapFromMatch(m, keypoints1, keypoints2, top.height, topRoiStart)
                val maxOverlap = min(top.height, bottom.height) - 1
                if (overlap in 1 until maxOverlap) overlaps += overlap
            }
            if (overlaps.size < 8) return null

            val robust = robustMedian(overlaps)
            return robust
        } finally {
            matches?.release()
            desc1?.release()
            desc2?.release()
            kp1?.release()
            kp2?.release()
            topGray?.release()
            bottomGray?.release()
            topRoi?.release()
            bottomRoi?.release()
            topMat.release()
            bottomMat.release()
        }
    }

    private fun overlapFromMatch(
        match: DMatch,
        topKeypoints: Array<KeyPoint>,
        bottomKeypoints: Array<KeyPoint>,
        topHeight: Int,
        topRoiStart: Int
    ): Int {
        val ptTop = topKeypoints[match.queryIdx].pt
        val ptBottom = bottomKeypoints[match.trainIdx].pt
        val globalTopY = topRoiStart + ptTop.y
        val globalBottomY = ptBottom.y
        val yOffset = globalTopY - globalBottomY
        return (topHeight - yOffset).roundToInt()
    }

    private fun robustMedian(values: List<Int>): Int {
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val filtered = sorted.filter { abs(it - median) <= 80 }
        if (filtered.isEmpty()) return median
        val s2 = filtered.sorted()
        return s2[s2.size / 2]
    }

    private fun adjustOverlapConservatively(top: Bitmap, overlap: Int): Int {
        // Prefer slight duplication over dropping real content.
        val tuning = tuningProvider()
        val safety = max(tuning.minSafetyPx, (top.height * tuning.safetyRatio).roundToInt())
        return (overlap - safety).coerceIn(1, top.height - 1)
    }
}
