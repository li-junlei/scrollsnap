package com.scrollsnap.core.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.scrollsnap.core.shizuku.ShizukuShellExecutor
import com.scrollsnap.core.shizuku.ShellResult
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SwipeSpec(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Int = 280
)

data class CaptureStepResult(
    val swipe: ShellResult,
    val screenshot: ShellResult,
    val screenshotPath: String
) {
    val isSuccess: Boolean get() = swipe.isSuccess && screenshot.isSuccess
}

class ScrollCaptureController(
    private val shellExecutor: ShizukuShellExecutor
) {

    suspend fun swipe(spec: SwipeSpec): ShellResult {
        val cmd = "input swipe ${spec.startX} ${spec.startY} ${spec.endX} ${spec.endY} ${spec.durationMs}"
        return shellExecutor.exec(cmd)
    }

    suspend fun screenshot(path: String): ShellResult {
        return shellExecutor.exec("screencap -p $path")
    }

    suspend fun screenshotToBitmap(): Result<Bitmap> {
        val result = shellExecutor.execBinary("screencap -p")
        if (!result.isSuccess) {
            return Result.failure(IllegalStateException("screencap failed: ${result.stderr}"))
        }
        val bitmap = BitmapFactory.decodeByteArray(result.stdoutBytes, 0, result.stdoutBytes.size)
            ?: return Result.failure(IllegalStateException("Failed to decode screencap PNG stream."))
        return Result.success(bitmap)
    }

    suspend fun runSingleStep(spec: SwipeSpec): CaptureStepResult {
        val swipeResult = swipe(spec)
        if (!swipeResult.isSuccess) {
            return CaptureStepResult(
                swipe = swipeResult,
                screenshot = ShellResult("screencap -p", -1, "", "Skip screenshot because swipe failed."),
                screenshotPath = ""
            )
        }

        val path = buildScreenshotPath()
        val screenshotResult = screenshot(path)
        return CaptureStepResult(
            swipe = swipeResult,
            screenshot = screenshotResult,
            screenshotPath = path
        )
    }

    suspend fun runSequence(
        spec: SwipeSpec,
        steps: Int,
        pauseAfterSwipeMs: Long = 450L
    ): List<CaptureStepResult> {
        val safeSteps = steps.coerceAtLeast(1)
        val results = mutableListOf<CaptureStepResult>()
        repeat(safeSteps) {
            val result = runSingleStep(spec)
            results += result
            if (!result.isSuccess) return results
            delay(pauseAfterSwipeMs)
        }
        return results
    }

    suspend fun captureFramesForStitch(
        spec: SwipeSpec,
        frameCount: Int,
        pauseAfterSwipeMs: Long = 500L
    ): Result<List<Bitmap>> {
        val count = frameCount.coerceAtLeast(2)
        val bitmaps = mutableListOf<Bitmap>()

        val first = screenshotToBitmap().getOrElse { return Result.failure(it) }
        bitmaps += first

        repeat(count - 1) {
            val swipeResult = swipe(spec)
            if (!swipeResult.isSuccess) {
                return Result.failure(
                    IllegalStateException("Swipe failed during frame ${it + 2}: ${swipeResult.stderr}")
                )
            }
            delay(pauseAfterSwipeMs)
            val frame = screenshotToBitmap().getOrElse { return Result.failure(it) }
            bitmaps += frame
        }

        return Result.success(bitmaps)
    }

    suspend fun captureFramesUntilStop(
        spec: SwipeSpec,
        stopRequested: () -> Boolean,
        maxFrames: Int = 32,
        pauseAfterSwipeMs: Long = 520L
    ): Result<List<Bitmap>> {
        val bitmaps = mutableListOf<Bitmap>()
        val first = screenshotToBitmap().getOrElse { return Result.failure(it) }
        bitmaps += first

        while (bitmaps.size < maxFrames) {
            val swipeResult = swipe(spec)
            if (!swipeResult.isSuccess) {
                return Result.failure(IllegalStateException("Swipe failed: ${swipeResult.stderr}"))
            }
            delay(pauseAfterSwipeMs)
            val frame = screenshotToBitmap().getOrElse { return Result.failure(it) }
            bitmaps += frame

            // Stop after current scroll+capture step when user requested.
            if (stopRequested() && bitmaps.size >= 3) break
        }

        if (bitmaps.size < 2) {
            return Result.failure(IllegalStateException("Not enough frames to stitch."))
        }
        return Result.success(bitmaps)
    }

    private fun buildScreenshotPath(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "/sdcard/Download/scrollsnap_$stamp.png"
    }
}
