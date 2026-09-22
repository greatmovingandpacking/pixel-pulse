package app.pixelpulse.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import android.os.BatteryManager
import android.telephony.TelephonyManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat

class ResourceCollector(private val context: Context) {
    private val cpuSampler = CpuSampler()
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
        val rawAverage = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: Long.MIN_VALUE
        val averageMa = ChargeMath.currentMilliAmps(rawAverage) ?: currentMa
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
                else -> ChargeMath.remainingLabel(percent, charging, averageMa, chargeCounter)
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

    private fun cpuInfo(): CpuInfo = cpuSampler.sample()

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
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val partial = caps?.hasCapability(24) == true // NET_CAPABILITY_PARTIAL_CONNECTIVITY
        val metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val roaming = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        var wifiInfo = caps?.transportInfo as? WifiInfo
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            transports += "VPN"
            if (wifiInfo == null) {
                wifiInfo = underlyingWifi(cm)
                if (wifiInfo != null && "Wi-Fi" !in transports) transports += "Wi-Fi"
            }
        }
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val dtMs = now - lastNetAt
        val rxRate = if (rx >= 0 && lastRx >= 0 && dtMs in 1..MAX_RATE_GAP_MS) {
            ((rx - lastRx).coerceAtLeast(0) / (dtMs / 1000.0)).toLong()
        } else {
            null
        }
        val txRate = if (tx >= 0 && lastTx >= 0 && dtMs in 1..MAX_RATE_GAP_MS) {
            ((tx - lastTx).coerceAtLeast(0) / (dtMs / 1000.0)).toLong()
        } else {
            null
        }
        lastRx = rx
        lastTx = tx
        lastNetAt = now

        val wifiMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 } ?: if (transports.contains("Wi-Fi")) {
            try {
                @Suppress("DEPRECATION")
                context.getSystemService(WifiManager::class.java).connectionInfo?.linkSpeed?.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val cellularLevel = if (transports.contains("Cellular")) {
            try {
                context.getSystemService(TelephonyManager::class.java).signalStrength?.level
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
            validated = validated,
            captivePortal = captive,
            partialConnectivity = partial,
            metered = metered,
            roaming = roaming,
            wifiRssi = wifiInfo?.rssi?.takeIf { it in -126..0 },
            wifiFrequencyMhz = wifiInfo?.frequency?.takeIf { it > 0 },
            wifiStandardLabel = wifiStandardLabel(wifiInfo),
            cellularLevel = cellularLevel,
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

    @Suppress("DEPRECATION")
    private fun underlyingWifi(cm: ConnectivityManager): WifiInfo? {
        return try {
            cm.allNetworks.firstNotNullOfOrNull { network ->
                val capabilities = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
                capabilities.transportInfo as? WifiInfo
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun wifiStandardLabel(info: WifiInfo?): String? {
        if (info == null) return null
        return when (info.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "Legacy Wi‑Fi"
            ScanResult.WIFI_STANDARD_11N -> "Wi‑Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi‑Fi 5"
            ScanResult.WIFI_STANDARD_11AX -> "Wi‑Fi 6"
            ScanResult.WIFI_STANDARD_11AD -> "WiGig"
            ScanResult.WIFI_STANDARD_11BE -> "Wi‑Fi 7"
            else -> null
        }
    }

    companion object {
        private const val MAX_RATE_GAP_MS = 3_000L

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
