package com.mindfulfocus

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mindfulfocus.ui.AppRoot
import com.mindfulfocus.ui.theme.MindFocusTheme

class MainActivity : ComponentActivity() {

    /** Ask the system dialog only once per process; afterwards just warn. */
    private var permissionRequested = false
    private var warnedNotifications = false
    private var warnedExactAlarm = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                warnedNotifications = true
                Toast.makeText(
                    this,
                    "没有通知权限，铃响时可能收不到提醒",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+: notifications are opt-in, and without them a background bell is
        // completely silent. Ask up front...
        if (needsNotificationPermission() && !permissionRequested) {
            permissionRequested = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MindFocusTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ... and re-check on every return to the app, because the permission can be
        // revoked at any time from system settings.
        if (needsNotificationPermission() && !warnedNotifications) {
            warnedNotifications = true
            Toast.makeText(
                this,
                "通知权限已关闭：请在系统设置中允许通知，否则后台铃响看不到提醒",
                Toast.LENGTH_LONG
            ).show()
        }
        checkExactAlarmPermission()
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Exact alarms are what keep a bell punctual under Doze. When the user (or an OEM
     * ROM) revokes them, warn once and offer the system toggle instead of silently
     * drifting late.
     */
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (am.canScheduleExactAlarms()) return
        if (warnedExactAlarm) return
        warnedExactAlarm = true
        Toast.makeText(
            this,
            "请允许「闹钟和提醒」，否则铃响可能不准时",
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }
}
