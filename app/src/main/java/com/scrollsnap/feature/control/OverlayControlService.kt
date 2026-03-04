package com.scrollsnap.feature.control

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
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
    private var floatingBubble: FrameLayout? = null
    private var actionPanel: LinearLayout? = null
    private var hintOverlayView: View? = null
    private var stopTouchOverlayView: View? = null

    @Volatile
    private var stopRequested: Boolean = false

    // 悬浮球状态
    private var isExpanded = false
    private var isFloatingBubbleVisible = true

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

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 创建悬浮球容器
        floatingBubble = FrameLayout(this).apply {
            // 现代化设计：圆形白底 + 蓝边框
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(4, Color.parseColor("#2563EB")) // 品牌蓝
            }
            background = drawable

            // 截取按钮
            val captureBtn = Button(this@OverlayControlService).apply {
                text = "📸"
                textSize = 24f
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    if (isExpanded) {
                        runCapture()
                    } else {
                        expandPanel()
                    }
                }
                // 长按关闭悬浮球
                setOnLongClickListener {
                    Toast.makeText(this@OverlayControlService, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
                    this@OverlayControlService.stopSelf()
                    true
                }
            }
            addView(captureBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // 创建操作面板（初始隐藏）
        actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 24, 32, 24)

            // 圆角背景
            val panelDrawable = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.WHITE)
                setStroke(2, Color.parseColor("#2563EB"))
            }
            background = panelDrawable

            // 开始截屏按钮
            val startBtn = Button(this@OverlayControlService).apply {
                text = "开始截屏"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 12f
                    setColor(Color.parseColor("#2563EB"))
                }
                setOnClickListener { runCapture() }
            }
            addView(startBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            ).apply {
                bottomMargin = dpToPx(12)
            })

            // 收起按钮
            val closeBtn = Button(this@OverlayControlService).apply {
                text = "收起"
                setTextColor(Color.parseColor("#2563EB"))
                background = GradientDrawable().apply {
                    cornerRadius = 12f
                    setColor(Color.TRANSPARENT)
                    setStroke(2, Color.parseColor("#2563EB"))
                }
                setOnClickListener { collapsePanel() }
            }
            addView(closeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            ))
        }

        // 悬浮球布局参数
        val bubbleParams = WindowManager.LayoutParams(
            dpToPx(56),
            dpToPx(56),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(16)
            y = dpToPx(200)
        }

        // 面板布局参数（初始不可见）
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 添加拖拽功能
        setupDragListener(floatingBubble!!, bubbleParams)

        // 添加到窗口
        windowManager?.addView(floatingBubble, bubbleParams)
        windowManager?.addView(actionPanel, panelParams)

        // 初始隐藏面板
        actionPanel?.visibility = View.GONE

        overlayView = floatingBubble
        updateNotification(getString(R.string.overlay_active))
    }

    private fun expandPanel() {
        isExpanded = true
        actionPanel?.visibility = View.VISIBLE
        actionPanel?.alpha = 0f
        actionPanel?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.start()
    }

    private fun collapsePanel() {
        isExpanded = false
        actionPanel?.animate()
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction {
                actionPanel?.visibility = View.GONE
            }
            ?.start()
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun runCapture() {
        if (isCapturing) return
        isCapturing = true
        updateNotification(getString(R.string.capturing))

        // 隐藏悬浮球
        hideFloatingBubble()

        scope.launch {
            try {
                showCaptureHintOverlay()
                delay(900)
                removeCaptureHintOverlay()
                showStopTouchOverlay()

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
                // 显示悬浮球
                showFloatingBubble()
                isCapturing = false
            }
        }
    }

    private fun hideFloatingBubble() {
        isFloatingBubbleVisible = false
        floatingBubble?.animate()
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction {
                floatingBubble?.visibility = View.GONE
            }
            ?.start()

        // 同时隐藏操作面板
        actionPanel?.visibility = View.GONE
    }

    private fun showFloatingBubble() {
        floatingBubble?.visibility = View.VISIBLE
        floatingBubble?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(200)
            ?.start()
        isFloatingBubbleVisible = true
    }

    private fun showCaptureHintOverlay() {
        if (hintOverlayView != null) return
        val hint = TextView(this).apply {
            text = getString(R.string.starting_capture)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dpToPx(24), dpToPx(14), dpToPx(24), dpToPx(14))
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
            y = dpToPx(110)
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
        // 移除悬浮球
        floatingBubble?.let { windowManager?.removeView(it) }
        actionPanel?.let { windowManager?.removeView(it) }
        removeCaptureHintOverlay()
        removeStopTouchOverlay()
        overlayView = null
        floatingBubble = null
        actionPanel = null
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
