package com.aiavatar.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchA11y"
        var instance: TouchAccessibilityService? = null
            private set
        var lastResult: String = ""
            private set
        var isRunning = false
            private set

        // Get screen elements for AI to understand
        fun getScreenElements(): String {
            val svc = instance ?: return "service_not_running"
            return try {
                val root = svc.rootInActiveWindow ?: return "no_root_window"
                val elements = mutableListOf<String>()
                collectElements(root, elements, 0)
                root.recycle()
                if (elements.isEmpty()) "no_elements"
                else elements.joinToString("\n")
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        private fun collectElements(node: AccessibilityNodeInfo, list: MutableList<String>, depth: Int) {
            if (depth > 8) return // prevent too deep
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val clickable = node.isClickable
            val label = text.ifEmpty { desc }

            if (label.isNotEmpty() || clickable) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                list.add("[$cx,$cy] ${if (clickable) "可点击" else ""} \"$label\" ($cls) [${rect.left},${rect.top},${rect.right},${rect.bottom}]")
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectElements(child, list, depth + 1)
                child.recycle()
            }
        }

        fun tap(x: Float, y: Float): Boolean {
            val svc = instance ?: run { lastResult = "服务未运行"; return false }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                lastResult = "需要Android 7+"; return false
            }
            return try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                    .build()
                val ok = svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        lastResult = "✅ 点击成功 ($x, $y)"
                        Log.d(TAG, lastResult)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        lastResult = "❌ 点击被取消 ($x, $y)"
                        Log.d(TAG, lastResult)
                    }
                }, null)
                if (!ok) lastResult = "❌ dispatchGesture返回false"
                ok
            } catch (e: Exception) {
                lastResult = "❌ 点击失败: ${e.message}"
                Log.e(TAG, lastResult, e)
                false
            }
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
            val svc = instance ?: run { lastResult = "服务未运行"; return false }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                lastResult = "需要Android 7+"; return false
            }
            return try {
                val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
                svc.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        lastResult = "✅ 滑动完成 ($x1,$y1)→($x2,$y2)"
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        lastResult = "❌ 滑动取消"
                    }
                }, null)
            } catch (e: Exception) {
                lastResult = "❌ 滑动失败: ${e.message}"
                false
            }
        }

        fun pressBack(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_BACK).also {
                lastResult = if (it) "✅ 返回" else "❌ 返回失败"
            }
        }

        fun pressHome(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_HOME).also {
                lastResult = if (it) "✅ 主页" else "❌ 主页失败"
            }
        }

        fun pressRecents(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_RECENTS).also {
                lastResult = if (it) "✅ 最近任务" else "❌ 最近任务失败"
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        lastResult = "无障碍服务已启动"
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRunning = false
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
}
