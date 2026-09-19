package app.pixelpulse.monitor

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

object UsageAccess {
    fun granted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent {
        val targeted = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        return if (targeted.resolveActivity(context.packageManager) != null) {
            targeted
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
    }
}
