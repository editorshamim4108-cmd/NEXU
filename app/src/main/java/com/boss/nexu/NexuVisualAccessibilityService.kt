package com.boss.nexu

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NexuVisualAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NexuVisualAccessibilityService? = null
            private set

        fun isServiceConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Live UI inspection handled on-demand when Boss gives voice instructions
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * স্ক্রিনের সব দৃশ্যমান লেখা (Screen Intelligence & Smart Read Aloud) সংগ্রহ করে
     */
    fun readVisibleScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        // নিজের (JARVIS) অ্যাপের স্ক্রিন পড়বে না — অন্য অ্যাপ সামনে থাকলেই শুধু তার লেখা
        if (rootNode.packageName?.toString() == packageName) return ""
        val collected = mutableListOf<String>()
        collectNodeText(rootNode, collected)
        return collected.distinct().joinToString(". ")
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 1) {
            list.add(text)
        } else if (!desc.isNullOrEmpty() && desc.length > 1) {
            list.add(desc)
        }
        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), list)
        }
    }

    /**
     * স্ক্রিনে নিচে স্ক্রল করে (Smart Read Aloud & Auto Scroll)
     */
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

    /**
     * নির্দিষ্ট নামের বাটনে বা অপশনে দৃশ্যমানভাবে ক্লিক করে
     */
    fun clickNodeByText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }
        return false
    }

    /**
     * টেক্সট বক্সে (যেমন WhatsApp, Gemini বা Search বারে) দৃশ্যমানভাবে টেক্সট টাইপ করে
     */
    fun typeIntoFocusedOrFirstInput(textToType: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val inputNode = findEditableNode(rootNode) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToType
            )
        }
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /**
     * Android 9+ এ সরাসরি নিরাপদ ভয়েস ফোন লক (PIN/Fingerprint অক্ষুণ্ণ রেখে)
     */
    fun lockPhoneScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }
}
