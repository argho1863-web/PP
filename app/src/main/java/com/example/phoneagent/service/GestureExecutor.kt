package com.example.phoneagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GestureExecutor(private val service: AccessibilityService) {

    suspend fun tap(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 50)

    suspend fun longPress(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 900)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs.coerceIn(50, 5000))

    /** direction = which content you want to REVEAL: "down" shows content below (finger swipes up). */
    suspend fun scroll(direction: String): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val cx = w / 2
        val cy = h / 2
        return when (direction.lowercase()) {
            "down" -> swipe(cx, h * 0.72f, cx, h * 0.28f, 250)
            "up" -> swipe(cx, h * 0.28f, cx, h * 0.72f, 250)
            "right" -> swipe(w * 0.8f, cy, w * 0.2f, cy, 250)
            "left" -> swipe(w * 0.2f, cy, w * 0.8f, cy, 250)
            else -> false
        }
    }

    suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { node.getBoundsInScreen(it) }
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun global(name: String): Boolean {
        val code = when (name.uppercase()) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "SCREENSHOT" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return service.performGlobalAction(code)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = service.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }
}
