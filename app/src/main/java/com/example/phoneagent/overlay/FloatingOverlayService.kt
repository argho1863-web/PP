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
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.phoneagent.phoneApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Root view that reports taps outside the window and the Back key (needed to leave typing mode). */
private class OverlayRoot(
    context: Context,
    private val onOutside: () -> Unit,
    private val onBack: () -> Unit,
) : LinearLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Draggable card over other apps: live step text, mic, STOP, a typing field with Send, and Approve/Deny
 * for guarded actions.
 *
 * Keyboard handling: the window is FLAG_NOT_FOCUSABLE by default so touches and focus stay with the app
 * underneath (the agent can keep reading/clicking it). Tapping the text field clears that flag so the IME
 * can attach; sending, Back, or tapping outside restores it.
 */
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
    private var root: OverlayRoot? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var inputField: EditText
    private var typing = false

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

    // ---------------- typing mode (focusable window + keyboard) ----------------

    private fun applyLayout() {
        root?.let { r -> runCatching { wm?.updateViewLayout(r, lp) } }
    }

    private fun enterTypingMode() {
        if (typing || root == null) return
        typing = true
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        applyLayout()
        inputField.postDelayed({
            if (typing) {
                inputField.requestFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 80)
    }

    private fun exitTypingMode() {
        if (!typing || root == null) return
        typing = false
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(inputField.windowToken, 0)
        inputField.clearFocus()
        lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        applyLayout()
    }

    /** Sends the typed text to the agent (answers a pending question, or cancels the current run and starts a new one). */
    private fun submitTyped() {
        val text = inputField.text.toString().trim()
        inputField.setText("")
        exitTypingMode()
        if (text.isNotEmpty()) phoneApp.orchestrator.submitUserInput(text)
    }

    // ---------------- UI ----------------

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

        // Typing bar: stays non-focusable until the field is tapped.
        inputField = EditText(this).apply {
            hint = "Type a command..."
            setHintTextColor(0xFF9AA0A6.toInt()); setTextColor(Color.WHITE); textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = bg(0xFF3C4043.toInt(), 16)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) enterTypingMode()
                false
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitTyped(); true
                } else false
            }
        }
        val sendBtn = chip("Send", 0xFF3B6FD4.toInt()) { submitTyped() }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(inputField, LinearLayout.LayoutParams(dp(190), -2).apply { marginEnd = dp(6) })
            addView(sendBtn)
        }

        val approve = chip("Approve", 0xFF2E7D32.toInt()) { app.orchestrator.resolveApproval(true) }
        val deny = chip("Deny", 0xFF616161.toInt()) { app.orchestrator.resolveApproval(false) }
        val approvalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; visibility = View.GONE
            addView(deny, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(approve)
        }

        val container = OverlayRoot(this, onOutside = { exitTypingMode() }, onBack = { exitTypingMode() }).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = bg(0xE6202124.toInt(), 24)
            addView(topRow)
            addView(inputRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(approvalRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(120)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }

        // Drag by the status text.
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    applyLayout()
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
                    q != null -> "Agent asks: ${q.question}  (type or tap \uD83C\uDFA4 to answer)"
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
        if (typing) {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(inputField.windowToken, 0)
        }
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        running.value = false
        super.onDestroy()
    }
}
