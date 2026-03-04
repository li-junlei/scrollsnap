package com.scrollsnap.core.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import com.scrollsnap.core.shizuku.ShizukuManager
import com.scrollsnap.core.shizuku.ShizukuShellExecutor
import com.scrollsnap.core.stitch.NativeFeatureStitcher
import com.scrollsnap.core.stitch.OpenCvFeatureStitcher
import com.scrollsnap.core.stitch.StitchSettingsStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PipelineResult(
    val success: Boolean,
    val message: String,
    val outputPath: String? = null,
    val outputUri: String? = null
) {
    val outputDisplay: String
        get() = outputPath ?: outputUri ?: "unknown"
}

class CapturePipeline(
    private val context: Context,
    private val shizukuManager: ShizukuManager
) {
    private val shellExecutor = ShizukuShellExecutor(shizukuManager)
    private val captureController = ScrollCaptureController(shellExecutor)
    private val stitchSettingsStore = StitchSettingsStore(context.applicationContext)
    private val stitcher = OpenCvFeatureStitcher(
        fallbackStitcher = NativeFeatureStitcher(),
        tuningProvider = { stitchSettingsStore.getTuning() }
    )

    suspend fun run(frameCount: Int = 4): PipelineResult {
        shizukuManager.refreshStatus()
        val state = shizukuManager.status.value
        if (!state.isBinderAvailable) {
            return PipelineResult(false, "Shizuku binder unavailable: ${state.message}")
        }
        if (!state.isPermissionGranted) {
            return PipelineResult(false, "Shizuku permission not granted.")
        }

        val attempt1 = runAttempt(
            frameCount = frameCount.coerceAtLeast(2),
            swipeSpec = defaultSwipeSpec(context)
        )
        if (attempt1.success) return attempt1

        // Retry with shorter swipe distance to increase overlap.
        val attempt2 = runAttempt(
            frameCount = (frameCount + 1).coerceAtLeast(3),
            swipeSpec = shortSwipeSpec(context)
        )
        if (attempt2.success) {
            return attempt2.copy(message = "${attempt2.message} (retry: shorter swipe)")
        }

        return PipelineResult(
            success = false,
            message = "Both attempts failed. First: ${attempt1.message}; Retry: ${attempt2.message}"
        )
    }

    suspend fun runUntilStopped(stopRequested: () -> Boolean): PipelineResult {
        shizukuManager.refreshStatus()
        val state = shizukuManager.status.value
        if (!state.isBinderAvailable) {
            return PipelineResult(false, "Shizuku binder unavailable: ${state.message}")
        }
        if (!state.isPermissionGranted) {
            return PipelineResult(false, "Shizuku permission not granted.")
        }

        val frames = captureController.captureFramesUntilStop(
            spec = shortSwipeSpec(context),
            stopRequested = stopRequested,
            maxFrames = 36,
            pauseAfterSwipeMs = 520L
        ).getOrElse { e ->
            return PipelineResult(false, "Capture failed: ${e.message}")
        }

        val stitchResult = stitcher.stitchSequence(frames)
        if (!stitchResult.success || stitchResult.mergedBitmap == null) {
            return PipelineResult(false, "Stitch failed: ${stitchResult.message}")
        }

        val saved = saveBitmapToGallery(context, stitchResult.mergedBitmap)
        return PipelineResult(
            success = true,
            message = "Pipeline success (${frames.size} frames, stop by user)",
            outputPath = saved.path,
            outputUri = saved.uri?.toString()
        )
    }

    private suspend fun runAttempt(
        frameCount: Int,
        swipeSpec: SwipeSpec
    ): PipelineResult {
        val frames = captureController.captureFramesForStitch(
            spec = swipeSpec,
            frameCount = frameCount.coerceAtLeast(2),
            pauseAfterSwipeMs = 520L
        ).getOrElse { e ->
            return PipelineResult(false, "Capture failed: ${e.message}")
        }

        val stitchResult = stitcher.stitchSequence(frames)
        if (!stitchResult.success || stitchResult.mergedBitmap == null) {
            return PipelineResult(false, "Stitch failed: ${stitchResult.message}")
        }

        val saved = saveBitmapToGallery(context, stitchResult.mergedBitmap)
        return PipelineResult(
            success = true,
            message = "Pipeline success (${frames.size} frames)",
            outputPath = saved.path,
            outputUri = saved.uri?.toString()
        )
    }
}

fun defaultSwipeSpec(context: Context): SwipeSpec {
    val dm: DisplayMetrics = context.resources.displayMetrics
    val centerX = dm.widthPixels / 2
    val startY = (dm.heightPixels * 0.74f).toInt()
    val endY = (dm.heightPixels * 0.47f).toInt()
    return SwipeSpec(
        startX = centerX,
        startY = startY,
        endX = centerX,
        endY = endY,
        durationMs = 280
    )
}

fun shortSwipeSpec(context: Context): SwipeSpec {
    val dm: DisplayMetrics = context.resources.displayMetrics
    val centerX = dm.widthPixels / 2
    val startY = (dm.heightPixels * 0.70f).toInt()
    val endY = (dm.heightPixels * 0.52f).toInt()
    return SwipeSpec(
        startX = centerX,
        startY = startY,
        endX = centerX,
        endY = endY,
        durationMs = 300
    )
}

data class SavedMedia(
    val path: String?,
    val uri: Uri?
)

fun saveBitmapToGallery(context: Context, bitmap: Bitmap): SavedMedia {
    val fileName = "scrollsnap_stitched_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScrollSnap")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore item.")
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("Failed to compress bitmap.")
            }
        } ?: throw IllegalStateException("Failed to open MediaStore output stream.")

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SavedMedia(path = null, uri = uri)
    }

    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ScrollSnap")
    if (!dir.exists()) dir.mkdirs()
    val outFile = File(dir, fileName)
    FileOutputStream(outFile).use { fos ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
            throw IllegalStateException("Failed to compress bitmap.")
        }
    }
    MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("image/png"), null)
    return SavedMedia(path = outFile.absolutePath, uri = null)
}
