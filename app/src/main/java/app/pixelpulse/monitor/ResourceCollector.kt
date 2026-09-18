package app.pixelpulse.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File

class ResourceCollector(private val context: Context) {
    private var lastCpu: Map<String, CpuMath.Sample> = emptyMap()
    private var lastRx: Long = TrafficStats.getTotalRxBytes()
    private var lastTx: Long = TrafficStats.getTotalTxBytes()
    private var lastNetAt: Long = SystemClock.elapsedRealtime()

    fun sample(): ResourceSnapshot {
        return ResourceSnapshot(
            timestampMs = System.currentTimeMillis(),
            device = deviceInfo(),
            battery = batteryInfo(),
            memory = memoryInfo(),
            cpu = cpuInfo(),
            network = networkInfo(),
            storage = storageInfo(),
            thermal = thermalInfo(),
        )
    }

    private fun deviceInfo(): DeviceInfo {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return DeviceInfo(
            model = model.ifBlank { Build.DEVICE ?: "Android" },
            manufacturer = Build.MANUFACTURER.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            uptimeMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun batteryInfo(): BatteryInfo {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val manager = context.getSystemService(BatteryManager::class.java)

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
        }

        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when (plugged) {
            0 -> ChargeSource.NONE
            BatteryManager.BATTERY_PLUGGED_AC -> ChargeSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> ChargeSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargeSource.WIRELESS
            BatteryManager.BATTERY_PLUGGED_DOCK -> ChargeSource.DOCK
            else -> ChargeSource.UNKNOWN
        }
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
            else -> BatteryStatus.UNKNOWN
        }

        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temperatureC = if (tempRaw == Int.MIN_VALUE) null else tempRaw / 10f
        val voltRaw = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltageV = when {
            voltRaw <= 0 -> null
            voltRaw > 50 -> voltRaw / 1000f
            else -> voltRaw.toFloat()
        }

        val rawCurrent = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Long.MIN_VALUE
        val currentMa = ChargeMath.currentMilliAmps(rawCurrent)
        val powerW = ChargeMath.powerWatts(voltageV, currentMa)
        val charging = status == BatteryStatus.CHARGING ||
            (source != ChargeSource.NONE && status != BatteryStatus.DISCHARGING && (currentMa == null || currentMa > 20))
        val chargeCounter = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it != Long.MIN_VALUE && it > 0 }

        return BatteryInfo(
            percent = percent,
            status = status,
            source = source,
            healthLabel = healthLabel(intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)),
            temperatureC = temperatureC,
            voltageV = voltageV,
            currentMa = currentMa,
            powerW = powerW,
            chargeSpeedLabel = ChargeMath.chargeSpeedLabel(powerW, charging && status != BatteryStatus.FULL, source),
            remainingLabel = when (status) {
                BatteryStatus.FULL -> "Full"
                else -> ChargeMath.remainingLabel(percent, charging, currentMa, chargeCounter)
            },
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            present = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true,
        )
    }

    private fun healthLabel(code: Int?): String = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failed"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }

    private fun memoryInfo(): MemoryStats {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = (info.totalMem - info.availMem).coerceAtLeast(0)
        val percent = if (info.totalMem > 0) used * 100f / info.totalMem else 0f
        return MemoryStats(
            availBytes = info.availMem,
            totalBytes = info.totalMem,
            usedBytes = used,
            usedPercent = percent,
            lowMemory = info.lowMemory,
            thresholdBytes = info.threshold,
        )
    }

    private fun cpuInfo(): CpuInfo {
        val samples = readProcStat()
        val usages = linkedMapOf<String, Float?>()
        if (samples.isNotEmpty() && lastCpu.isNotEmpty()) {
            for ((name, current) in samples) {
                val previous = lastCpu[name] ?: continue
                usages[name] = CpuMath.usagePercent(previous, current)
            }
        }
        if (samples.isNotEmpty()) lastCpu = samples

        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cores = (0 until coreCount).map { index ->
            val usage = usages["cpu$index"] ?: usages["cpu$index".trim()]
            CoreInfo(
                index = index,
                usagePercent = usage,
                freqMhz = readCoreFreqMhz(index),
            )
        }
        val overall = usages["cpu"] ?: cores.mapNotNull { it.usagePercent }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val freqs = cores.mapNotNull { it.freqMhz }
        return CpuInfo(
            usagePercent = overall,
            cores = cores,
            minFreqMhz = freqs.minOrNull(),
            maxFreqMhz = freqs.maxOrNull(),
        )
    }

    private fun readProcStat(): Map<String, CpuMath.Sample> {
        return try {
            File("/proc/stat").useLines { lines ->
                lines
                    .takeWhile { it.startsWith("cpu") }
                    .mapNotNull { line ->
                        val name = line.trim().split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
                        CpuMath.parseProcStatLine(line)?.let { name to it }
                    }
                    .toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readCoreFreqMhz(index: Int): Int? {
        val paths = listOf(
            "/sys/devices/system/cpu/cpu$index/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_cur_freq",
        )
        for (path in paths) {
            try {
                val khz = File(path).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull() ?: continue
                if (khz > 0) return (khz / 1000L).toInt()
            } catch (_: Exception) {
                // try next path
            }
        }
        return null
    }

    private fun networkInfo(): NetworkInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val transports = mutableListOf<String>()
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi-Fi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Cellular"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "Bluetooth"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) transports += "USB"
        }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val dtSec = ((now - lastNetAt).coerceAtLeast(1)) / 1000.0
        val rxRate = if (rx >= 0 && lastRx >= 0 && now > lastNetAt) {
            ((rx - lastRx).coerceAtLeast(0) / dtSec).toLong()
        } else {
            null
        }
        val txRate = if (tx >= 0 && lastTx >= 0 && now > lastNetAt) {
            ((tx - lastTx).coerceAtLeast(0) / dtSec).toLong()
        } else {
            null
        }
        lastRx = rx
        lastTx = tx
        lastNetAt = now

        val wifiMbps = if (transports.contains("Wi-Fi")) {
            try {
                @Suppress("DEPRECATION")
                val info = context.getSystemService(WifiManager::class.java).connectionInfo
                info?.linkSpeed?.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return NetworkInfo(
            connected = connected,
            transportLabel = if (transports.isEmpty()) {
                if (connected) "Connected" else "Offline"
            } else {
                transports.joinToString(" + ")
            },
            rxBytesPerSec = rxRate,
            txBytesPerSec = txRate,
            rxTotal = rx.coerceAtLeast(0),
            txTotal = tx.coerceAtLeast(0),
            downlinkCapKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            uplinkCapKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
            wifiLinkMbps = wifiMbps,
        )
    }

    private fun storageInfo(): StorageInfo {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = (total - free).coerceAtLeast(0)
        val percent = if (total > 0) used * 100f / total else 0f
        return StorageInfo(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free,
            usedPercent = percent,
        )
    }

    private fun thermalInfo(): ThermalInfo {
        val pm = context.getSystemService(PowerManager::class.java)
        val status = try {
            pm.currentThermalStatus
        } catch (_: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }
        return ThermalInfo(status = status, label = ThermalLabels.label(status))
    }

    companion object {
        fun sourceLabel(source: ChargeSource): String = when (source) {
            ChargeSource.NONE -> "Battery"
            ChargeSource.AC -> "Wall charger"
            ChargeSource.USB -> "USB"
            ChargeSource.WIRELESS -> "Wireless"
            ChargeSource.DOCK -> "Dock"
            ChargeSource.UNKNOWN -> "Charger"
        }

        fun statusLabel(status: BatteryStatus): String = when (status) {
            BatteryStatus.CHARGING -> "Charging"
            BatteryStatus.DISCHARGING -> "Discharging"
            BatteryStatus.FULL -> "Full"
            BatteryStatus.NOT_CHARGING -> "Not charging"
            BatteryStatus.UNKNOWN -> "Battery"
        }
    }
}
