package com.scrollsnap.core.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

data class StitchResult(
    val success: Boolean,
    val overlapPx: Int,
    val mergedBitmap: Bitmap?,
    val message: String
)

class NativeFeatureStitcher {

    external fun estimateVerticalOverlap(
        topBitmap: Bitmap,
        bottomBitmap: Bitmap,
        rowStep: Int,
        colStep: Int
    ): Int

    fun stitch(top: Bitmap, bottom: Bitmap): StitchResult {
        if (top.config != Bitmap.Config.ARGB_8888 || bottom.config != Bitmap.Config.ARGB_8888) {
            return StitchResult(
                success = false,
                overlapPx = 0,
                mergedBitmap = null,
                message = "Only ARGB_8888 bitmap is supported."
            )
        }
        if (top.width != bottom.width) {
            return StitchResult(
                success = false,
                overlapPx = 0,
                mergedBitmap = null,
                message = "Bitmap widths must be the same."
            )
        }

        val overlap = estimateVerticalOverlap(top, bottom, rowStep = 4, colStep = 4)
        val clampedOverlap = overlap.coerceIn(1, min(top.height, bottom.height) - 1)
        val mergedHeight = top.height + bottom.height - clampedOverlap

        val out = Bitmap.createBitmap(top.width, mergedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(top, 0f, 0f, null)
        canvas.drawBitmap(bottom, 0f, (top.height - clampedOverlap).toFloat(), null)

        return StitchResult(
            success = true,
            overlapPx = clampedOverlap,
            mergedBitmap = out,
            message = "Stitch success"
        )
    }

    fun runSyntheticSelfTest(): String {
        val width = 720
        val frameHeight = 1200
        val overlap = 320
        val sourceHeight = frameHeight * 2

        val source = Bitmap.createBitmap(width, sourceHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()

        // Build a non-periodic synthetic scene to avoid ambiguous overlaps.
        for (y in 0 until source.height) {
            val r = (y * 37 + 19) and 0xFF
            val g = (y * 73 + 47) and 0xFF
            val b = (y * 29 + 91) and 0xFF
            paint.color = Color.rgb(r, g, b)
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
        }
        for (i in 0 until 24) {
            val y = (i * 97 + 53) % source.height
            paint.color = Color.rgb((i * 41) and 0xFF, (255 - i * 17) and 0xFF, (i * 23) and 0xFF)
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 6).toFloat(), paint)
        }

        val top = Bitmap.createBitmap(source, 0, 0, width, frameHeight)
        val bottomStart = frameHeight - overlap
        val bottom = Bitmap.createBitmap(source, 0, bottomStart, width, frameHeight)

        val estimated = estimateVerticalOverlap(top, bottom, rowStep = 4, colStep = 4)
        val error = max(0, kotlin.math.abs(estimated - overlap))
        return "Synthetic overlap expected=$overlap, estimated=$estimated, error=$error px"
    }

    companion object {
        init {
            System.loadLibrary("scrollsnap_native")
        }
    }
}
