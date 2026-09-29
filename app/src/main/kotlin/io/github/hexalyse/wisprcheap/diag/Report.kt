package io.github.hexalyse.wisprcheap.diag

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Permissions and settings the app depends on. */
data class SetupStatus(
    val mic: Boolean,
    val notifications: Boolean,
    val a11yEnabled: Boolean,
    val a11yConnected: Boolean,
    val batteryUnrestricted: Boolean,
    val installSource: String,
) {
    companion object {
        fun read(ctx: Context) = SetupStatus(
            mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            a11yEnabled = isAccessibilityEnabled(ctx),
            a11yConnected = WisprApp.instance.runtime.editor.value.serviceConnected,
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName),
            installSource = installSource(ctx),
        )

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, WisprAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun installSource(ctx: Context): String = runCatching {
            val info = ctx.packageManager.getInstallSourceInfo(ctx.packageName)
            val source = when (info.packageSource) {
                PackageInstaller.PACKAGE_SOURCE_STORE -> "store"
                PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> "local file"
                PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> "downloaded file"
                PackageInstaller.PACKAGE_SOURCE_OTHER -> "other"
                else -> "unspecified"
            }
            "${info.installingPackageName ?: "unknown installer"} ($source)"
        }.getOrElse { "unknown" }
    }
}

/** Markdown report of all results, to share back for the compatibility table in PLAN.md. */
object Report {
    fun build(ctx: Context, results: List<ProbeResult>): String = buildString {
        val s = SetupStatus.read(ctx)
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        appendLine("# WisprCheap Phase 0 report")
        appendLine()
        appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine(
            "- Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "security patch ${Build.VERSION.SECURITY_PATCH}, build ${Build.DISPLAY}",
        )
        appendLine("- App $version, installed by ${s.installSource}")
        appendLine(
            "- Mic permission: ${s.mic}, notifications: ${s.notifications}, accessibility enabled: " +
                "${s.a11yEnabled} (connected: ${s.a11yConnected}), battery unrestricted: ${s.batteryUnrestricted}",
        )
        appendLine()
        appendLine("| # | Time | Test | App | OK | Summary |")
        appendLine("|---|---|---|---|---|---|")
        results.forEachIndexed { i, r ->
            appendLine(
                "| ${i + 1} | ${time.format(Date(r.ts))} | ${r.test} | ${r.pkg ?: "-"} | " +
                    "${if (r.ok) "yes" else "NO"} | ${r.summary.replace("|", "/")} |",
            )
        }
        appendLine()
        appendLine("## Details")
        results.forEachIndexed { i, r ->
            appendLine()
            appendLine("### ${i + 1}. ${r.test} @ ${r.pkg ?: "-"}")
            r.details.forEach { (k, v) -> appendLine("- $k: ${v.replace("\n", " ")}") }
        }
    }
}
