package com.scrollsnap.feature.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.scrollsnap.R
import com.scrollsnap.core.capture.CapturePipeline
import com.scrollsnap.core.capture.PipelineResult
import com.scrollsnap.core.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class OverlayControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var pipeline: CapturePipeline

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var captureButton: Button? = null
    private var hintOverlayView: View? = null
    private var stopTouchOverlayView: View? = null
    @Volatile
    private var stopRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        shizukuManager = ShizukuManager(applicationContext)
        pipeline = CapturePipeline(applicationContext, shizukuManager)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.overlay_ready)))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OVERLAY -> showOverlay()
            ACTION_STOP_OVERLAY -> stopSelf()
            ACTION_CAPTURE_NOW -> runCapture()
            else -> showOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_perm_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC222222.toInt())
            setPadding(18, 12, 18, 12)
        }
        val snapBtn = Button(this).apply {
            text = "Snap"
            setOnClickListener { runCapture() }
        }
        val closeBtn = Button(this).apply {
            text = "X"
            setOnClickListener { stopSelf() }
        }
        container.addView(snapBtn)
        container.addView(closeBtn)
        captureButton = snapBtn

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 220
        }

        windowManager?.addView(container, params)
        overlayView = container
        updateNotification(getString(R.string.overlay_active))
    }

    private fun runCapture() {
        if (isCapturing) return
        captureButton?.isEnabled = false
        isCapturing = true
        updateNotification(getString(R.string.capturing))
        overlayView?.visibility = View.INVISIBLE
        stopRequested = false
        scope.launch {
            try {
                showCaptureHintOverlay()
                delay(900)
                removeCaptureHintOverlay()
                showStopTouchOverlay()
                // Ensure overlays settle before first frame.
                delay(120)
                val result = runCatching {
                    withContext(Dispatchers.Default) {
                        withTimeout(60_000L) {
                            pipeline.runUntilStopped { stopRequested }
                        }
                    }
                }.getOrElse { e ->
                    PipelineResult(
                        success = false,
                        message = getString(R.string.capture_timeout_error, e.message ?: "unknown")
                    )
                }
                if (result.success) {
                    Toast.makeText(
                        this@OverlayControlService,
                        getString(R.string.saved_output, result.outputDisplay),
                        Toast.LENGTH_LONG
                    ).show()
                    updateNotification(getString(R.string.last_output, result.outputDisplay))
                } else {
                    Toast.makeText(
                        this@OverlayControlService,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                    updateNotification(getString(R.string.capture_failed))
                }
            } finally {
                removeCaptureHintOverlay()
                removeStopTouchOverlay()
                overlayView?.visibility = View.VISIBLE
                captureButton?.isEnabled = true
                isCapturing = false
            }
        }
    }

    private fun showCaptureHintOverlay() {
        if (hintOverlayView != null) return
        val hint = TextView(this).apply {
            text = getString(R.string.starting_capture)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 14, 24, 14)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 110
        }
        windowManager?.addView(hint, params)
        hintOverlayView = hint
    }

    private fun removeCaptureHintOverlay() {
        hintOverlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        hintOverlayView = null
    }

    private fun showStopTouchOverlay() {
        if (stopTouchOverlayView != null) return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val touchHeight = (metrics.heightPixels * 0.2f).toInt().coerceAtLeast(120)
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    stopRequested = true
                    Toast.makeText(
                        this@OverlayControlService,
                        getString(R.string.stop_requested),
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
            }
        )
        val overlay = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                // Intercept only the double-tap trigger; do not hold long gestures.
                event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            touchHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = 0
        }
        wm.addView(overlay, params)
        stopTouchOverlayView = overlay
    }

    private fun removeStopTouchOverlay() {
        stopTouchOverlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        stopTouchOverlayView = null
    }

    private fun createNotification(content: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScrollSnap Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scrollsnap_status)
            .setContentTitle("ScrollSnap")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        overlayView?.let { windowManager?.removeView(it) }
        removeCaptureHintOverlay()
        removeStopTouchOverlay()
        overlayView = null
        captureButton = null
        shizukuManager.dispose()
        scope.cancel()
        isRunning = false
        isCapturing = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_OVERLAY = "com.scrollsnap.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.scrollsnap.action.STOP_OVERLAY"
        const val ACTION_CAPTURE_NOW = "com.scrollsnap.action.CAPTURE_NOW"

        private const val CHANNEL_ID = "scrollsnap_overlay_channel"
        private const val NOTIFICATION_ID = 2201

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var isCapturing: Boolean = false
    }
}
