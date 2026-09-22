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
        val user = values[0]
        val nice = values[1]
        val system = values[2]
        val idle = values[3]
        val iowait = values.getOrElse(4) { 0L }
        val irq = values.getOrElse(5) { 0L }
        val softirq = values.getOrElse(6) { 0L }
        val steal = values.getOrElse(7) { 0L }
        val idleAll = idle + iowait
        val busy = user + nice + system + irq + softirq + steal
        return Sample(total = idleAll + busy, idle = idleAll)
    }

    fun parseCpuList(spec: String): List<Int> {
        return spec.trim().split(',').flatMap { part ->
            val bits = part.split('-')
            val start = bits.getOrNull(0)?.toIntOrNull() ?: return@flatMap emptyList()
            val end = bits.getOrNull(1)?.toIntOrNull() ?: start
            (start..end).toList()
        }.distinct().sorted()
    }

    fun parseLoadAvg(line: String): Float? {
        return line.trim().split(Regex("\\s+")).firstOrNull()?.toFloatOrNull()
    }

    fun freqUtilPercent(curMhz: Int?, minMhz: Int?, maxMhz: Int?): Float? {
        if (curMhz == null || minMhz == null || maxMhz == null) return null
        val span = (maxMhz - minMhz).coerceAtLeast(1)
        return ((curMhz - minMhz).toFloat() / span * 100f).coerceIn(0f, 100f)
    }

    fun usageFromIdle(previousIdleUs: Long, currentIdleUs: Long, wallUs: Long): Float? {
        if (wallUs <= 0) return null
        val idleDelta = (currentIdleUs - previousIdleUs).coerceAtLeast(0)
        val busy = (wallUs - idleDelta).coerceAtLeast(0)
        return ((busy.toDouble() / wallUs) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    data class UptimeSample(
        val uptimeSec: Double,
        val idleSec: Double,
    )

    fun parseUptime(line: String): UptimeSample? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val uptime = parts[0].toDoubleOrNull() ?: return null
        val idle = parts[1].toDoubleOrNull() ?: return null
        if (uptime < 0 || idle < 0) return null
        return UptimeSample(uptime, idle)
    }

    /**
     * `/proc/uptime` idle is the sum of idle time across CPUs, in seconds.
     * Busy fraction = 1 - idleDelta / (uptimeDelta * onlineCpus).
     */
    fun usageFromUptime(previous: UptimeSample, current: UptimeSample, onlineCpus: Int): Float? {
        val wall = current.uptimeSec - previous.uptimeSec
        if (wall <= 0.0) return null
        val idleDelta = (current.idleSec - previous.idleSec).coerceAtLeast(0.0)
        val capacity = wall * onlineCpus.coerceAtLeast(1)
        val busy = (capacity - idleDelta).coerceAtLeast(0.0)
        return ((busy / capacity) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    /**
     * Spread an overall usage across cores using relative weights (usually clock
     * speed). Average of the online cores equals [overall]. If a weighted share
     * would pass 100%, every online core gets [overall] so the bars still match
     * the headline.
     */
    fun shareOverall(overall: Float, weights: List<Float>): List<Float?> {
        val sum = weights.sum()
        val online = weights.count { it > 0f }.coerceAtLeast(1)
        if (sum <= 0f) return weights.map { null }
        val weighted = weights.map { weight ->
            if (weight <= 0f) null else (weight / sum) * overall * online
        }
        if (weighted.any { it != null && it > 100f }) {
            val flat = overall.coerceIn(0f, 100f)
            return weights.map { weight -> if (weight <= 0f) null else flat }
        }
        return weighted.map { it?.coerceIn(0f, 100f) }
    }
}

object TrafficMath {
    /** Integrate per-second rates across real sample gaps. A long pause does not invent traffic. */
    fun bytesOver(points: List<RatePoint>, select: (RatePoint) -> Long): Long {
        if (points.size < 2) return 0L
        var sum = 0.0
        for (index in 1 until points.size) {
            val dtSec = ((points[index].timestampMs - points[index - 1].timestampMs) / 1000.0).coerceIn(0.0, 5.0)
            sum += select(points[index]) * dtSec
        }
        return sum.toLong()
    }

    fun spanLabel(points: List<RatePoint>): String {
        if (points.size < 2) return "Just started"
        val sec = ((points.last().timestampMs - points.first().timestampMs) / 1000L).coerceAtLeast(0L)
        return when {
            sec < 90 -> "Last ${sec.coerceAtLeast(1)} seconds"
            sec < 3_600 -> "Last ${((sec + 30) / 60).coerceAtLeast(1)} minutes"
            else -> "Last ${sec / 3_600}h ${(sec % 3_600) / 60}m"
        }
    }
}

object ThermalLabels {
    fun label(status: Int): String = when (status) {
        0 -> "Not throttling"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown"
        else -> "Unknown"
    }
}

object ThermalMath {
    fun celsiusFromRaw(raw: Long): Float? {
        if (raw == 0L) return null
        val celsius = when {
            kotlin.math.abs(raw) >= 1_000 -> raw / 1_000f
            kotlin.math.abs(raw) >= 200 -> raw / 10f
            else -> raw.toFloat()
        }
        return celsius.takeIf { it in -20f..120f }
    }

    fun classify(type: String): ThermalZone.Kind {
        val t = type.lowercase()
        return when {
            t.contains("skin") || t.contains("quiet-therm") || t.contains("back_therm") ||
                t.contains("fps-therm") || t.contains("ambient") -> ThermalZone.Kind.SKIN
            t.contains("battery") || t == "batt" -> ThermalZone.Kind.BATTERY
            t.contains("gpu") -> ThermalZone.Kind.GPU
            t.contains("tpu") -> ThermalZone.Kind.TPU
            t.contains("cpu") -> ThermalZone.Kind.CPU
            t.contains("charg") || t.contains("usb") -> ThermalZone.Kind.CHARGE
            t.contains("disp") || t.contains("panel") -> ThermalZone.Kind.DISPLAY
            t.contains("soc") || t.contains("tsens") || t.contains("xo-therm") -> ThermalZone.Kind.SOC
            else -> ThermalZone.Kind.OTHER
        }
    }

    fun displayName(type: String): String {
        val cleaned = type
            .replace(Regex("-(usr|adc|user)$"), "")
            .replace('_', '-')
        return when (classify(type)) {
            ThermalZone.Kind.SKIN -> "Skin"
            ThermalZone.Kind.CPU -> if (cleaned.contains("cpu", ignoreCase = true)) humanize(cleaned) else "CPU"
            ThermalZone.Kind.GPU -> "GPU"
            ThermalZone.Kind.TPU -> "TPU"
            ThermalZone.Kind.BATTERY -> "Battery"
            ThermalZone.Kind.CHARGE -> "Charge"
            ThermalZone.Kind.DISPLAY -> "Display"
            ThermalZone.Kind.SOC -> "SoC"
            ThermalZone.Kind.OTHER -> humanize(cleaned)
        }
    }

    fun kindLabel(kind: ThermalZone.Kind): String = when (kind) {
        ThermalZone.Kind.SKIN -> "Skin"
        ThermalZone.Kind.CPU -> "CPU"
        ThermalZone.Kind.GPU -> "GPU"
        ThermalZone.Kind.TPU -> "TPU"
        ThermalZone.Kind.BATTERY -> "Battery"
        ThermalZone.Kind.CHARGE -> "Charge"
        ThermalZone.Kind.DISPLAY -> "Display"
        ThermalZone.Kind.SOC -> "SoC"
        ThermalZone.Kind.OTHER -> "Sensor"
    }

    private fun humanize(value: String): String {
        return value.split('-', '_', '.')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.titlecase() } }
            .ifBlank { value }
    }
}

object ProcCpuParser {
    data class Sample(
        val pid: Int,
        val comm: String,
        val jiffies: Long,
    )

    fun parseStat(line: String): Sample? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open <= 0 || close <= open) return null
        val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val comm = line.substring(open + 1, close).ifBlank { "pid $pid" }
        val rest = line.substring(close + 1).trim().split(Regex("\\s+"))
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return Sample(pid, comm, utime + stime)
    }

    fun percentOfAll(deltaJiffies: Long, dtMs: Long, ticksPerSec: Long, onlineCpus: Int): Float? {
        if (deltaJiffies < 0 || dtMs < 40) return null
        val capacity = (ticksPerSec.coerceAtLeast(1) * (dtMs / 1000.0) * onlineCpus.coerceAtLeast(1))
        if (capacity <= 0.0) return null
        return ((deltaJiffies / capacity) * 100.0).toFloat().coerceIn(0f, 100f)
    }
}
