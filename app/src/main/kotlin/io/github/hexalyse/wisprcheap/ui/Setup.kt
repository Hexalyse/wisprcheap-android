package io.github.hexalyse.wisprcheap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService

/** Permissions and system settings the app depends on. */
data class SetupStatus(
    val mic: Boolean,
    val notifications: Boolean,
    val a11yEnabled: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val essentialsDone: Boolean get() = mic && a11yEnabled

    companion object {
        fun read(ctx: Context) = SetupStatus(
            mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            a11yEnabled = isAccessibilityEnabled(ctx),
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
        )

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, WisprAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}

object SystemScreens {
    /** Our service's own page needs a privileged permission, so this opens the list (Phase 0 finding). */
    fun accessibility(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun appInfo(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
    )

    @SuppressLint("BatteryLife")
    fun battery(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
    }
}
