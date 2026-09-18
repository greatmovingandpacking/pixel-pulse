package app.pixelpulse.monitor

data class ResourceSnapshot(
    val timestampMs: Long,
    val device: DeviceInfo,
    val battery: BatteryInfo,
    val memory: MemoryStats,
    val cpu: CpuInfo,
    val network: NetworkInfo,
    val storage: StorageInfo,
    val thermal: ThermalInfo,
)

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdk: Int,
    val uptimeMs: Long,
)

enum class ChargeSource {
    NONE,
    AC,
    USB,
    WIRELESS,
    DOCK,
    UNKNOWN,
}

enum class BatteryStatus {
    CHARGING,
    DISCHARGING,
    FULL,
    NOT_CHARGING,
    UNKNOWN,
}

data class BatteryInfo(
    val percent: Int,
    val status: BatteryStatus,
    val source: ChargeSource,
    val healthLabel: String,
    val temperatureC: Float?,
    val voltageV: Float?,
    val currentMa: Double?,
    val powerW: Double?,
    val chargeSpeedLabel: String,
    val remainingLabel: String?,
    val technology: String?,
    val present: Boolean,
)

data class MemoryStats(
    val availBytes: Long,
    val totalBytes: Long,
    val usedBytes: Long,
    val usedPercent: Float,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
)

data class CoreInfo(
    val index: Int,
    val usagePercent: Float?,
    val freqMhz: Int?,
)

data class CpuInfo(
    val usagePercent: Float?,
    val cores: List<CoreInfo>,
    val minFreqMhz: Int?,
    val maxFreqMhz: Int?,
)

data class NetworkInfo(
    val connected: Boolean,
    val transportLabel: String,
    val rxBytesPerSec: Long?,
    val txBytesPerSec: Long?,
    val rxTotal: Long,
    val txTotal: Long,
    val downlinkCapKbps: Int?,
    val uplinkCapKbps: Int?,
    val wifiLinkMbps: Int?,
)

data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val usedPercent: Float,
)

data class ThermalInfo(
    val status: Int,
    val label: String,
)
