package com.autodrops.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AgentNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        synchronized(recent) { recent.add(0, describe(sbn)); while (recent.size > 50) recent.removeAt(recent.size - 1) }
    }
    companion object {
        @Volatile var instance: AgentNotificationListener? = null
        val recent = mutableListOf<JSONObject>()
        fun describe(s: StatusBarNotification): JSONObject {
            val e = s.notification.extras
            return JSONObject().put("package", s.packageName).put("time", s.postTime)
                .put("title", e.getCharSequence("android.title")?.toString() ?: "")
                .put("text", e.getCharSequence("android.text")?.toString()?.take(500) ?: "")
        }
        fun active(): JSONArray = JSONArray().apply {
            instance?.activeNotifications?.forEach { put(describe(it)) }
        }
    }
}

class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun dump(maxNodes: Int = 300): String {
        val root = rootInActiveWindow ?: return "No active window"
        val sb = StringBuilder("package=${root.packageName}\n")
        var n = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || n >= maxNodes) return
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) {
                val r = Rect(); node.getBoundsInScreen(r); n++
                sb.append("  ".repeat(depth.coerceAtMost(10))).append("[").append(node.className?.toString()?.substringAfterLast('.'))
                    .append("] ").append(if (node.isPassword) "<password>" else text.take(80))
                if (desc.isNotEmpty()) sb.append(" desc=").append(desc.take(60))
                if (id.isNotEmpty()) sb.append(" id=").append(id)
                if (node.isClickable) sb.append(" clickable"); if (node.isEditable) sb.append(" editable")
                sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun find(text: String?, id: String?): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val list = if (!id.isNullOrBlank()) root.findAccessibilityNodeInfosByViewId(id) else root.findAccessibilityNodeInfosByText(text ?: "")
        return list.firstOrNull()
    }

    fun click(text: String?, id: String?): String {
        var node = find(text, id) ?: return "Node not found"
        while (!node.isClickable && node.parent != null) node = node.parent
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Clicked" else "Click failed"
    }

    fun setText(text: String?, id: String?, value: String): String {
        val root = rootInActiveWindow ?: return "No window"
        val node = (if (text.isNullOrBlank() && id.isNullOrBlank()) root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else find(text, id))
            ?: return "Editable node not found"
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "Text set" else "Set text failed"
    }

    fun tap(x: Float, y: Float): String {
        val p = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
        return if (ok) "Tapped $x,$y" else "Tap failed"
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): String {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
        return if (ok) "Swiped" else "Swipe failed"
    }

    fun global(action: String): String {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "Unknown global action"
        }
        return if (performGlobalAction(a)) "Done: $action" else "Failed: $action"
    }

    companion object { @Volatile var instance: AgentAccessibilityService? = null }
}
