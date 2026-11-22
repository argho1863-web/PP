package com.example.phoneagent.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class PhoneAgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PhoneAgentAccessibilityService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }

    lateinit var executor: GestureExecutor
        private set
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    /** Uptime (ms) of the last window/content/scroll event coming from another app. */
    @Volatile private var lastEventTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        executor = GestureExecutor(this)
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Ignore our own overlay/app so the floating bubble's updates never delay the agent.
                if (e.packageName?.toString() != packageName) lastEventTime = SystemClock.uptimeMillis()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        shotExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Suspends until the UI looks ready for the next observation, instead of sleeping a fixed time.
     *
     * @param since       uptime (ms) when the last action was fired (0 before the first step)
     * @param minWaitMs   if no UI event arrives within this time after [since], assume the screen did not change
     * @param quietMs     how long the UI must stay free of events once changes started (150-250 ms is typical)
     * @param timeoutMs   hard upper bound so busy/animated screens never stall the agent
     */
    suspend fun awaitUiReady(since: Long, minWaitMs: Long, quietMs: Long = 200, timeoutMs: Long = 1500) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - start >= timeoutMs) return
            val last = lastEventTime
            if (last > since) {
                if (now - last >= quietMs) return          // changes happened and have settled
            } else if (now - since >= minWaitMs) {
                return                                    // nothing changed; do not wait longer
            }
            delay(40)
        }
    }

    /** Indexed JSON of the active window. Nodes from the previous snapshot are released. */
    @Suppress("DEPRECATION")
    fun snapshot(): UiSnapshot {
        val dm = resources.displayMetrics
        val snap = UiTreeSerializer.serialize(rootInActiveWindow, dm.widthPixels, dm.heightPixels)
        lastNodes.forEach { runCatching { it.recycle() } }
        lastNodes = snap.nodes
        return snap
    }

    /** Compressed JPEG (max 768px wide, quality 60) as base64, or null if unavailable. */
    suspend fun screenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val out = runCatching {
                    val hw = result.hardwareBuffer
                    val hwBmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    val soft = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()
                    if (soft == null) null else {
                        val w = minOf(768, soft.width)
                        val h = soft.height * w / soft.width
                        val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(out)
            }

            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }
}
