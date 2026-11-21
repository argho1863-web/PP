package com.example.phoneagent.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        executor = GestureExecutor(this)
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* we poll via snapshot() instead */ }
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
