package com.example.phoneagent.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*

/** [nodes] is index-aligned with the "index" field in [json], so tap_node(index) maps straight back. */
class UiSnapshot(val json: String, val nodes: List<AccessibilityNodeInfo>, val packageName: String)

object UiTreeSerializer {
    private const val MAX_NODES = 180
    private const val MAX_TEXT = 80

    fun serialize(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): UiSnapshot {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val items = mutableListOf<JsonObject>()
        if (root != null) walk(root, nodes, items)
        val pkg = root?.packageName?.toString().orEmpty()
        val obj = buildJsonObject {
            put("package", pkg)
            put("screen", buildJsonArray { add(screenW); add(screenH) })
            put("nodes", JsonArray(items))
        }
        return UiSnapshot(obj.toString(), nodes, pkg)
    }

    private fun walk(n: AccessibilityNodeInfo, nodes: MutableList<AccessibilityNodeInfo>, items: MutableList<JsonObject>) {
        if (nodes.size >= MAX_NODES || !n.isVisibleToUser) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val text = n.text?.toString()?.take(MAX_TEXT)
        val desc = n.contentDescription?.toString()?.take(MAX_TEXT)
        val meaningful = !text.isNullOrBlank() || !desc.isNullOrBlank() ||
            n.isClickable || n.isEditable || n.isScrollable || n.isCheckable

        if (meaningful && r.width() > 0 && r.height() > 0) {
            val index = nodes.size
            nodes += n
            items += buildJsonObject {
                put("index", index)
                if (!text.isNullOrBlank()) put("text", text)
                if (!desc.isNullOrBlank()) put("contentDescription", desc)
                n.viewIdResourceName?.let { put("viewId", it.substringAfter(":id/")) }
                put("className", n.className?.toString()?.substringAfterLast('.').orEmpty())
                put("bounds", buildJsonArray { add(r.left); add(r.top); add(r.right); add(r.bottom) })
                put("center", buildJsonArray { add(r.centerX()); add(r.centerY()) })
                if (n.isClickable) put("isClickable", true)
                if (n.isEditable) put("isEditable", true)
                if (n.isScrollable) put("isScrollable", true)
                if (n.isCheckable) put("isChecked", n.isChecked)
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, nodes, items)
        }
    }
}
