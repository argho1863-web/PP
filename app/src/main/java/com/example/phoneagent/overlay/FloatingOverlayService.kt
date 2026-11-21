package com.example.phoneagent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.phoneagent.phoneApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Draggable pill over other apps: live step text, mic, STOP, and Approve/Deny for guarded actions. */
class FloatingOverlayService : Service() {

    companion object {
        val running = MutableStateFlow(false)
        private const val CHANNEL = "overlay"

        fun start(c: Context) =
            ContextCompat.startForegroundService(c, Intent(c, FloatingOverlayService::class.java))

        fun stop(c: Context) { c.stopService(Intent(c, FloatingOverlayService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wm: WindowManager? = null
    private var root: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        running.value = true
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant bubble", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Phone Agent bubble is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun buildOverlay() {
        val app = phoneApp
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

        fun chip(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = bg(color, 16)
            setOnClickListener { onClick() }
        }

        val status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; text = "Idle"
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(190)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val mic = chip("\uD83C\uDFA4", 0xFF3B6FD4.toInt()) {
            if (app.voice.listening.value) app.voice.stopListening()
            else app.voice.startListening { app.orchestrator.submitUserInput(it) }
        }
        val stopBtn = chip("STOP", 0xFFD32F2F.toInt()) { app.orchestrator.stop() }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(status)
            addView(mic, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            addView(stopBtn)
        }
        val approve = chip("Approve", 0xFF2E7D32.toInt()) { app.orchestrator.resolveApproval(true) }
        val deny = chip("Deny", 0xFF616161.toInt()) { app.orchestrator.resolveApproval(false) }
        val approvalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; visibility = View.GONE
            addView(deny, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(approve)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = bg(0xE6202124.toInt(), 24)
            addView(topRow); addView(approvalRow)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(120) }

        // Drag by the status text.
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    wm?.updateViewLayout(container, lp)
                }
            }
            true
        }

        wm?.addView(container, lp)
        root = container

        val o = app.orchestrator
        scope.launch {
            combine(o.status, o.pendingApproval, o.pendingQuestion) { st, ap, q ->
                when {
                    ap != null -> "Approve? ${ap.description}"
                    q != null -> "Agent asks: ${q.question}  (tap \uD83C\uDFA4 to answer)"
                    else -> st
                }
            }.collect { status.text = it }
        }
        scope.launch { o.pendingApproval.collect { approvalRow.visibility = if (it != null) View.VISIBLE else View.GONE } }
        scope.launch { o.running.collect { stopBtn.alpha = if (it) 1f else 0.45f } }
        scope.launch { app.voice.listening.collect { mic.text = if (it) "\u23FA" else "\uD83C\uDFA4" } }
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        running.value = false
        super.onDestroy()
    }
}
