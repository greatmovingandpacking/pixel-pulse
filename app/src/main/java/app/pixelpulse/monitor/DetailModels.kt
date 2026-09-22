package app.pixelpulse.monitor

data class RatePoint(
    val timestampMs: Long,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
)

data class AppIdentity(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val isSystem: Boolean,
)

data class AppNetworkUsage(
    val app: AppIdentity,
    val rxBytes: Long,
    val txBytes: Long,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val foregroundBytes: Long,
    val backgroundBytes: Long,
    val history: List<RatePoint>,
) {
    val totalBytes: Long get() = rxBytes + txBytes
    val totalRate: Long get() = rxBytesPerSec + txBytesPerSec
}

data class NetworkFinding(
    val severity: Severity,
    val title: String,
    val detail: String,
) {
    enum class Severity { OK, INFO, WARN, BAD }
}

data class NetworkDetail(
    val hasUsageAccess: Boolean,
    val findings: List<NetworkFinding>,
    val timeline: List<RatePoint>,
    val apps: List<AppNetworkUsage>,
    val windowMs: Long,
)

data class MemorySlice(
    val key: String,
    val label: String,
    val bytes: Long,
    val detail: String,
)

data class ProcessMemoryRow(
    val key: String,
    val label: String,
    val packageName: String?,
    val kind: Kind,
    val pssBytes: Long?,
    val detail: String,
) {
    enum class Kind { APP, SERVICE, CACHED, SYSTEM, UNKNOWN }
}

data class CpuPoint(
    val timestampMs: Long,
    val percent: Float,
)

data class AppCpuUsage(
    val app: AppIdentity,
    val percent: Float,
    val processCount: Int,
    val history: List<CpuPoint>,
)

object CpuWindows {
    const val WINDOW_MS = 30_000L
}

data class CpuDetail(
    val overallPercent: Float?,
    val sourceLabel: String,
    val cores: List<CoreInfo>,
    val apps: List<AppCpuUsage>,
    val readableProcessCount: Int,
    val note: String,
    val hasUsageAccess: Boolean = false,
    val windowMs: Long = CpuWindows.WINDOW_MS,
)

data class MemoryDetail(
    val hasUsageAccess: Boolean,
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val swapUsedBytes: Long,
    val swapTotalBytes: Long,
    val slices: List<MemorySlice>,
    val processes: List<ProcessMemoryRow>,
    val note: String,
)
