package com.example.privacyassistant

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    private lateinit var notificationHelper: NotificationHelper

    override fun onServiceConnected() {
        super.onServiceConnected()
        notificationHelper = NotificationHelper(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val packageName = event.packageName?.toString() ?: ""
            val viewId = event.source?.viewIdResourceName ?: ""

            if (packageName == "com.instagram.android") {
                Log.d("MyAccessibilityService", "Instagram click: $viewId")

                if (viewId.contains("creation_tab")) {
                    Log.d("MyAccessibilityService", "Instagram POST action detected")

                    notificationHelper.showInstagramPostAssistantNotification()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d("MyAccessibilityService", "Service interrupted")
    }
}
