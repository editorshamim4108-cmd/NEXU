package com.boss.nexu

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NexuVisualAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NexuVisualAccessibilityService? = null
            private set

        @Volatile
        var lastExternalScreenText: String = ""
            private set

        fun isServiceConnected(): Boolean = instance != null
    }

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || ignoredPackages.contains(pkg)) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            try {
                val root = rootInActiveWindow ?: return
                val rootPkg = root.packageName?.toString().orEmpty()
                if (rootPkg != packageName && !ignoredPackages.contains(rootPkg)) {
                    val collected = mutableListOf<String>()
                    collectNodeText(root, collected, 0)
                    val joined = collected.distinct().joinToString(". ").trim()
                    if (joined.length > 8) {
                        lastExternalScreenText = joined.take(2500)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * অন্য অ্যাপের স্ক্রিনে থাকা দৃশ্যমান লেখা পড়ে (Floating Mode বা সদ্য দেখা অ্যাপ উভয় ক্ষেত্রেই কাজ করে)
     */
    fun readVisibleScreenText(): String {
        try {
            for (win in windows) {
                val root = win.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isNotEmpty() && pkg != packageName && !ignoredPackages.contains(pkg)) {
                    val collected = mutableListOf<String>()
                    collectNodeText(root, collected, 0)
                    val text = collected.distinct().joinToString(". ").trim()
                    if (text.length > 8) {
                        lastExternalScreenText = text.take(2500)
                        return lastExternalScreenText
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val pkg = rootNode.packageName?.toString().orEmpty()
                if (pkg != packageName && !ignoredPackages.contains(pkg)) {
                    val collected = mutableListOf<String>()
                    collectNodeText(rootNode, collected, 0)
                    val text = collected.distinct().joinToString(". ").trim()
                    if (text.length > 8) {
                        lastExternalScreenText = text.take(2500)
                        return lastExternalScreenText
                    }
                }
            }
        } catch (_: Exception) {}

        return lastExternalScreenText
    }

    private fun collectNodeText(
        node: AccessibilityNodeInfo?,
        list: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > 28 || list.size > 120) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 1) {
            list.add(text)
        } else if (!desc.isNullOrEmpty() && desc.length > 1) {
            list.add(desc)
        }
        val count = node.childCount
        for (i in 0 until count) {
            collectNodeText(node.getChild(i), list, depth + 1)
        }
    }

    fun scrollDownScreen(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(rootNode) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun lockPhoneScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }
}
