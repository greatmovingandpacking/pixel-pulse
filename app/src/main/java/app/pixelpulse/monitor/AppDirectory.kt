package app.pixelpulse.monitor

import android.content.Context
import android.app.usage.NetworkStats
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

class AppDirectory(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val labels = ConcurrentHashMap<Int, AppIdentity>()
    private val icons = ConcurrentHashMap<String, Drawable>()

    fun identity(uid: Int): AppIdentity {
        labels[uid]?.let { return it }
        val built = buildIdentity(uid)
        val existing = labels.putIfAbsent(uid, built)
        return existing ?: built
    }

    fun icon(packageName: String?): Drawable? {
        if (packageName.isNullOrBlank()) return null
        icons[packageName]?.let { return it }
        return try {
            val drawable = pm.getApplicationIcon(packageName)
            val existing = icons.putIfAbsent(packageName, drawable)
            existing ?: drawable
        } catch (_: Exception) {
            null
        }
    }

    private fun buildIdentity(uid: Int): AppIdentity {
        specialUid(uid)?.let { return it }
        val packages = try {
            pm.getPackagesForUid(uid)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val primary = packages.firstOrNull { pkg ->
            try {
                pm.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
        } ?: packages.firstOrNull()
        if (primary != null) {
            val info = try {
                pm.getApplicationInfo(primary, 0)
            } catch (_: Exception) {
                null
            }
            val label = try {
                info?.let { pm.getApplicationLabel(it).toString() } ?: primary
            } catch (_: Exception) {
                primary
            }
            val shown = if (packages.size > 1) "$label +${packages.size - 1}" else label
            val system = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            return AppIdentity(uid, primary, shown, system)
        }
        return AppIdentity(uid, null, "UID $uid", uid < 10000)
    }

    private fun specialUid(uid: Int): AppIdentity? {
        val label = when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> "Uninstalled apps"
            NetworkStats.Bucket.UID_TETHERING -> "Tethering"
            0 -> "Kernel / root"
            1000 -> "Android system"
            else -> null
        } ?: return null
        return AppIdentity(uid, null, label, true)
    }
}
