package com.example.phoneagent.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*

/**
 * [nodes] is index-aligned with the "index" field in [json], so tap_node(index) maps straight back.
 * [signature] identifies the exact screen content (used by the loop guard); [summary] is a tiny
 * human-readable description used in the agent's rolling history.
 */
class UiSnapshot(
    val json: String,
    val nodes: List<AccessibilityNodeInfo>,
    val packageName: String,
    val summary: String,
) {
    val signature: String = "$packageName:${json.hashCode()}"
}

object UiTreeSerializer {
    private const val MAX_NODES = 150
    private const val MAX_TEXT = 60

    fun serialize(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): UiSnapshot {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val items = mutableListOf<JsonObject>()
        val labels = mutableListOf<String>()
        if (root != null) walk(root, nodes, items, labels, false, screenW.toLong() * screenH)
        val pkg = root?.packageName?.toString().orEmpty()
        val obj = buildJsonObject {
            put("package", pkg)
            put("screen", buildJsonArray { add(screenW); add(screenH) })
            put("nodes", JsonArray(items))
        }
        val summary = "$pkg [" + labels.take(6).joinToString(" | ").take(90) + "]"
        return UiSnapshot(obj.toString(), nodes, pkg, summary)
    }

    /**
     * Keeps only nodes that carry text / contentDescription or are clickable / editable / scrollable / checkable.
     * A clickable container without its own label borrows its children's text, and those text-only
     * children are then skipped (fewer nodes, clearer tap targets, smaller prompt).
     */
    private fun walk(
        n: AccessibilityNodeInfo,
        nodes: MutableList<AccessibilityNodeInfo>,
        items: MutableList<JsonObject>,
        labels: MutableList<String>,
        covered: Boolean,
        screenArea: Long,
    ) {
        if (nodes.size >= MAX_NODES || !n.isVisibleToUser) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        var text = n.text?.toString()?.trim()?.take(MAX_TEXT)
        val desc = n.contentDescription?.toString()?.trim()?.take(MAX_TEXT)
        var childrenCovered = covered

        val smallEnough = r.width().toLong() * r.height() < screenArea / 2
        if (n.isClickable && !n.isEditable && smallEnough) {
            if (text.isNullOrBlank() && desc.isNullOrBlank()) {
                val derived = descendantText(n)
                if (derived.isNotEmpty()) { text = derived; childrenCovered = true }
            } else {
                childrenCovered = true
            }
        }

        val shownText = text?.takeIf { it.isNotBlank() }
        val shownDesc = desc?.takeIf { it.isNotBlank() && it != shownText }
        val hasLabel = shownText != null || shownDesc != null
        val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable

        if (r.width() > 0 && r.height() > 0 && (interactive || (hasLabel && !covered))) {
            val index = nodes.size
            nodes += n
            (shownText ?: shownDesc)?.let { labels += it }
            items += buildJsonObject {
                put("index", index)
                if (shownText != null) put("text", shownText)
                if (shownDesc != null) put("contentDescription", shownDesc)
                n.viewIdResourceName?.let { put("viewId", it.substringAfter(":id/")) }
                put("className", n.className?.toString()?.substringAfterLast('.').orEmpty())
                put("center", buildJsonArray { add(r.centerX()); add(r.centerY()) })
                if (n.isClickable) put("isClickable", true)
                if (n.isEditable) put("isEditable", true)
                if (n.isScrollable) put("isScrollable", true)
                if (n.isCheckable) put("isChecked", n.isChecked)
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, nodes, items, labels, childrenCovered, screenArea)
        }
    }

    private fun descendantText(
        n: AccessibilityNodeInfo,
        depth: Int = 0,
        out: MutableList<String> = mutableListOf(),
    ): String {
        if (depth <= 4 && out.size < 3) {
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val t = c.text?.toString()?.trim().orEmpty()
                    .ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
                if (t.isNotEmpty()) out += t.take(30)
                if (out.size >= 3) break
                descendantText(c, depth + 1, out)
            }
        }
        return out.joinToString(" / ").take(MAX_TEXT)
    }
}
