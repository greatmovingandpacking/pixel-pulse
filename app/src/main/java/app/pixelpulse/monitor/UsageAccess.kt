package app.pixelpulse.monitor

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Usage access is not a runtime permission. Sideloaded APKs on Pixel / Android 13+
 * also hide it behind **Allow restricted settings** on the app-info page.
 */
object UsageAccess {
    fun granted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.PACKAGE_USAGE_STATS,
        ) == PackageManager.PERMISSION_GRANTED
        return allowed(mode, permissionGranted)
    }

    fun allowed(mode: Int, permissionGranted: Boolean): Boolean {
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> permissionGranted
            else -> false
        }
    }

    fun appInfoIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun usageAccessListIntent(context: Context): Intent {
        // Do not attach a package: URI. Pixel Settings often fails to show the
        // toggle when ACTION_USAGE_ACCESS_SETTINGS carries data.
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openAppInfo(context: Context) {
        start(context, appInfoIntent(context), fallback = settingsHome())
    }

    fun openUsageAccessList(context: Context) {
        start(context, usageAccessListIntent(context), fallback = appInfoIntent(context))
    }

    private fun settingsHome(): Intent {
        return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun start(context: Context, intent: Intent, fallback: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
                // Settings is missing; the in-app copy still names the path.
            }
        }
    }
}
