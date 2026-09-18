package app.pixelpulse.monitor

import kotlin.math.abs
import kotlin.math.roundToInt

object Formatters {
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        val pattern = if (unit <= 1 || amount >= 100) "%.0f %s" else "%.1f %s"
        return pattern.format(amount, units[unit])
    }

    fun rateBytesPerSec(value: Long?): String {
        if (value == null) return "—"
        return bytes(abs(value)) + "/s"
    }

    fun percent(value: Int): String = "$value%"

    fun percent(value: Float?): String {
        if (value == null || value.isNaN()) return "—"
        return "${value.roundToInt()}%"
    }

    fun watts(value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        return if (value >= 10) "%.1f W".format(value) else "%.2f W".format(value)
    }

    fun voltage(value: Float?): String {
        if (value == null || value.isNaN() || value <= 0f) return "—"
        return "%.2f V".format(value)
    }

    fun current(value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        val absMa = abs(value)
        return if (absMa >= 1000) "%.2f A".format(absMa / 1000.0) else "%.0f mA".format(absMa)
    }

    fun temperature(value: Float?): String {
        if (value == null || value.isNaN()) return "—"
        return "%.1f°C".format(value)
    }

    fun frequency(mhz: Int?): String {
        if (mhz == null || mhz <= 0) return "—"
        return if (mhz >= 1000) "%.2f GHz".format(mhz / 1000.0) else "$mhz MHz"
    }

    fun uptime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val days = totalSec / 86_400
        val hours = (totalSec % 86_400) / 3_600
        val minutes = (totalSec % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun durationHours(hours: Double): String {
        if (hours.isNaN() || hours.isInfinite() || hours < 0) return "—"
        val totalMin = (hours * 60).roundToInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h <= 0 -> "$m min"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

object ChargeMath {
    /**
     * Android documents CURRENT_NOW as microamps. Some devices report milliamps.
     * Values whose magnitude is already in a milliamp range stay as mA.
     */
    fun currentMilliAmps(raw: Long): Double? {
        if (raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong()) return null
        val magnitude = abs(raw)
        if (magnitude == 0L) return 0.0
        return if (magnitude > 20_000) raw / 1000.0 else raw.toDouble()
    }

    fun powerWatts(voltageV: Float?, currentMa: Double?): Double? {
        if (voltageV == null || voltageV <= 0f || currentMa == null || currentMa.isNaN()) return null
        return abs(voltageV * (currentMa / 1000.0))
    }

    fun chargeSpeedLabel(
        watts: Double?,
        charging: Boolean,
        source: ChargeSource,
    ): String {
        if (!charging && source == ChargeSource.NONE) return "On battery"
        if (source == ChargeSource.WIRELESS) {
            return if (watts != null && watts > 0.3) "Wireless · ${Formatters.watts(watts)}" else "Wireless"
        }
        if (!charging) return "Plugged in"
        val w = watts ?: return "Charging"
        return when {
            w >= 30 -> "Super fast"
            w >= 18 -> "Fast charge"
            w >= 7.5 -> "Standard"
            w >= 1.0 -> "Trickle"
            else -> "Connected"
        }
    }

    fun remainingLabel(
        percent: Int,
        charging: Boolean,
        currentMa: Double?,
        chargeCounterUah: Long?,
    ): String? {
        val current = currentMa?.let { abs(it) } ?: return null
        if (current < 25) return null
        val counter = chargeCounterUah ?: return fallbackFromPercent(percent, charging, current)
        if (counter <= 0 || percent !in 1..100) return fallbackFromPercent(percent, charging, current)
        val fullUah = counter * 100.0 / percent
        return if (charging) {
            val remainingUah = (fullUah - counter).coerceAtLeast(0.0)
            if (remainingUah <= 0) "Full soon"
            else "~${Formatters.durationHours((remainingUah / 1000.0) / current)} to full"
        } else {
            "~${Formatters.durationHours((counter / 1000.0) / current)} left"
        }
    }

    private fun fallbackFromPercent(percent: Int, charging: Boolean, currentMa: Double): String? {
        // Last-resort estimate assuming a 5000 mAh class cell when charge counter is missing.
        val assumedMah = 5_000.0
        return if (charging) {
            val remaining = assumedMah * ((100 - percent).coerceAtLeast(0) / 100.0)
            if (remaining <= 20) "Full soon"
            else "~${Formatters.durationHours(remaining / currentMa)} to full"
        } else {
            val remaining = assumedMah * (percent.coerceAtLeast(0) / 100.0)
            "~${Formatters.durationHours(remaining / currentMa)} left"
        }
    }
}

object CpuMath {
    data class Sample(
        val total: Long,
        val idle: Long,
    )

    fun usagePercent(previous: Sample, current: Sample): Float? {
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0) return null
        val busy = (totalDelta - idleDelta).coerceAtLeast(0)
        return ((busy.toDouble() / totalDelta) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    fun parseProcStatLine(line: String): Sample? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5 || !parts[0].startsWith("cpu")) return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + values.getOrElse(4) { 0L }
        return Sample(total = values.sum(), idle = idle)
    }
}

object ThermalLabels {
    fun label(status: Int): String = when (status) {
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown"
        else -> "Unknown"
    }
}
