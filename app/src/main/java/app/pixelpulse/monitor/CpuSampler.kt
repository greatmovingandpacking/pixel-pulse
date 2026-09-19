package app.pixelpulse.monitor

import android.os.SystemClock
import java.io.File

/**
 * System CPU on modern Pixels. `/proc/stat` is often blocked for third-party
 * apps. `/proc/uptime` is not, so we use that as the reliable overall reading,
 * then fill per-core bars from idle residency or clock speed.
 */
class CpuSampler {
    private var lastProc: Map<String, CpuMath.Sample> = emptyMap()
    private var lastUptime: CpuMath.UptimeSample? = null
    private var lastIdleUs: Map<Int, Long> = emptyMap()
    private var lastIdleAtMs: Long = 0L
    private var bootstrapped = false
    private val history = ArrayDeque<Float>()

    fun sample(): CpuInfo {
        val first = readOnce()
        if (!bootstrapped && first.usagePercent == null) {
            try {
                Thread.sleep(BOOTSTRAP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            bootstrapped = true
            return readOnce()
        }
        bootstrapped = true
        return first
    }

    private fun readOnce(): CpuInfo {
        val coresPresent = readCpuList("present")
            ?: readCpuList("possible")
            ?: (0 until Runtime.getRuntime().availableProcessors().coerceAtLeast(1)).toList()
        val online = readCpuList("online")?.toSet() ?: coresPresent.toSet()
        val onlineCount = online.size.coerceAtLeast(1)

        val proc = readProcStat()
        val procUsages = hashMapOf<String, Float>()
        if (proc.isNotEmpty() && lastProc.isNotEmpty()) {
            for ((name, current) in proc) {
                val previous = lastProc[name] ?: continue
                CpuMath.usagePercent(previous, current)?.let { procUsages[name] = it }
            }
        }
        if (proc.isNotEmpty()) lastProc = proc

        val uptime = readUptime()
        val overallFromUptime = if (uptime != null && lastUptime != null) {
            CpuMath.usageFromUptime(lastUptime!!, uptime, onlineCount)
        } else {
            null
        }
        if (uptime != null) lastUptime = uptime

        val now = SystemClock.elapsedRealtime()
        val idleNow = coresPresent.associateWith { readCoreIdleUs(it) }
        val idleUsages = hashMapOf<Int, Float>()
        if (lastIdleUs.isNotEmpty() && lastIdleAtMs > 0) {
            val wallUs = ((now - lastIdleAtMs) * 1000L).coerceAtLeast(1)
            coresPresent.forEach { index ->
                val prev = lastIdleUs[index] ?: return@forEach
                val curr = idleNow[index] ?: return@forEach
                CpuMath.usageFromIdle(prev, curr, wallUs)?.let { idleUsages[index] = it }
            }
        }
        lastIdleUs = idleNow.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        lastIdleAtMs = now

        val freqByCore = coresPresent.associateWith { index ->
            Triple(
                readFreqMhz(index, "scaling_cur_freq") ?: readFreqMhz(index, "cpuinfo_cur_freq"),
                readFreqMhz(index, "scaling_min_freq") ?: readFreqMhz(index, "cpuinfo_min_freq"),
                readFreqMhz(index, "scaling_max_freq") ?: readFreqMhz(index, "cpuinfo_max_freq"),
            )
        }

        val overallFromProc = procUsages["cpu"]
        val overallFromIdle = idleUsages.values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val overallFromLoad = readLoadAvgUsage(onlineCount)

        val source: String
        val overall: Float? = when {
            overallFromProc != null -> {
                source = "kernel"
                overallFromProc
            }
            overallFromUptime != null -> {
                source = "uptime"
                overallFromUptime
            }
            overallFromIdle != null -> {
                source = "idle states"
                overallFromIdle
            }
            overallFromLoad != null -> {
                source = "load average"
                overallFromLoad
            }
            else -> {
                source = "waiting"
                null
            }
        }

        val hasPerCoreBusy = coresPresent.any { procUsages.containsKey("cpu$it") || idleUsages.containsKey(it) }
        val shared = if (!hasPerCoreBusy && overall != null) {
            val weights = coresPresent.map { index ->
                if (index !in online) 0f
                else (freqByCore[index]?.first ?: 1).toFloat().coerceAtLeast(1f)
            }
            CpuMath.shareOverall(overall, weights)
        } else {
            null
        }

        val cores = coresPresent.mapIndexed { position, index ->
            val (freq, min, max) = freqByCore[index] ?: Triple(null, null, null)
            val usage = procUsages["cpu$index"]
                ?: idleUsages[index]
                ?: shared?.getOrNull(position)
                ?: CpuMath.freqUtilPercent(freq, min, max)?.takeIf { index in online && overall == null }
            CoreInfo(
                index = index,
                usagePercent = usage,
                freqMhz = freq,
                online = index in online,
                minFreqMhz = min,
                maxFreqMhz = max,
            )
        }

        // Clock-speed-only fallback when every time counter is blocked.
        val overallFromClock = cores.mapNotNull { it.usagePercent }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val resolvedOverall: Float?
        val resolvedSource: String
        if (overall != null) {
            resolvedOverall = overall
            resolvedSource = source
        } else if (overallFromClock != null) {
            resolvedOverall = overallFromClock
            resolvedSource = "clock speed"
        } else {
            resolvedOverall = null
            resolvedSource = source
        }

        resolvedOverall?.let {
            history.addLast(it)
            while (history.size > HISTORY) history.removeFirst()
        }

        val freqs = cores.mapNotNull { it.freqMhz }
        return CpuInfo(
            usagePercent = resolvedOverall,
            cores = cores,
            minFreqMhz = freqs.minOrNull(),
            maxFreqMhz = freqs.maxOrNull(),
            sourceLabel = resolvedSource,
            history = history.toList(),
        )
    }

    private fun readProcStat(): Map<String, CpuMath.Sample> {
        val text = readText("/proc/stat") ?: return emptyMap()
        return text.lineSequence()
            .takeWhile { it.startsWith("cpu") }
            .mapNotNull { line ->
                val name = line.trim().split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
                CpuMath.parseProcStatLine(line)?.let { name to it }
            }
            .toMap()
    }

    private fun readUptime(): CpuMath.UptimeSample? {
        return CpuMath.parseUptime(readText("/proc/uptime") ?: return null)
    }

    private fun readCpuList(file: String): List<Int>? {
        val spec = readText("/sys/devices/system/cpu/$file") ?: return null
        val parsed = CpuMath.parseCpuList(spec)
        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun readCoreIdleUs(index: Int): Long? {
        val dir = File("/sys/devices/system/cpu/cpu$index/cpuidle")
        if (!dir.isDirectory) return null
        var sum = 0L
        var found = false
        val states = dir.listFiles() ?: return null
        for (state in states) {
            if (!state.name.startsWith("state")) continue
            // `time` is microseconds in that idle state. `usage` is an entry
            // count and must not be treated as time.
            val us = readText(File(state, "time"))?.trim()?.toLongOrNull() ?: continue
            sum += us
            found = true
        }
        return if (found) sum else null
    }

    private fun readFreqMhz(index: Int, node: String): Int? {
        val khz = readText("/sys/devices/system/cpu/cpu$index/cpufreq/$node")
            ?.trim()
            ?.toLongOrNull()
            ?: return null
        return if (khz > 0) (khz / 1000L).toInt() else null
    }

    private fun readLoadAvgUsage(onlineCores: Int): Float? {
        val load = CpuMath.parseLoadAvg(readText("/proc/loadavg") ?: return null) ?: return null
        return ((load / onlineCores.coerceAtLeast(1)) * 100f).coerceIn(0f, 100f)
    }

    private fun readText(path: String): String? = readText(File(path))

    private fun readText(file: File): String? {
        return try {
            // Do not trust File.canRead() — SELinux on Pixels lies about /proc.
            file.inputStream().bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val HISTORY = 30
        private const val BOOTSTRAP_MS = 180L
    }
}
