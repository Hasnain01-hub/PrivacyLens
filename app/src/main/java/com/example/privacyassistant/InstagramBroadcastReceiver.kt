package com.example.privacyassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log

class InstagramBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                Log.d("InstagramReceiver", "Device booted - Checking accessibility service")
                
                // Check if service was previously enabled by user
                val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val isServiceEnabled = prefs.getBoolean("accessibility_enabled", false)
                
                if (isServiceEnabled) {
                    Log.d("InstagramReceiver", "Service was enabled before reboot - Ready to monitor")
                    // The accessibility service will auto-start when Instagram is opened
                    // No need to manually start it
                } else {
                    Log.d("InstagramReceiver", "Service was not enabled before reboot")
                }
            }
            
            // You can add more intents if needed
            Intent.ACTION_PACKAGE_ADDED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == "com.instagram.android") {
                    Log.d("InstagramReceiver", "Instagram app installed")
                }
            }
            
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == "com.instagram.android") {
                    Log.d("InstagramReceiver", "Instagram app updated")
                }
            }
        }
    }
}