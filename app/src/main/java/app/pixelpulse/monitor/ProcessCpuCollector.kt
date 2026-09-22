package app.pixelpulse.monitor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

class ProcessCpuCollector(
    context: Context,
    private val apps: AppDirectory,
) {
    private val appContext = context.applicationContext
    private val previous = HashMap<Int, PidSample>()
    private var previousAtMs = 0L
    private val histories = HashMap<Int, ArrayDeque<CpuPoint>>()

    @Synchronized
    fun collect(nowMs: Long, cpu: CpuInfo): CpuDetail {
        return try {
            collectLocked(nowMs, cpu)
        } catch (_: Throwable) {
            CpuDetail(
                overallPercent = cpu.usagePercent,
                sourceLabel = cpu.sourceLabel,
                cores = cpu.cores,
                apps = emptyList(),
                readableProcessCount = 0,
                note = "Could not read process CPU this pass.",
            )
        }
    }

    private fun collectLocked(nowMs: Long, cpu: CpuInfo): CpuDetail {
        val online = cpu.cores.count { it.online }.coerceAtLeast(1)
        val ticks = ticksPerSecond()
        val seen = scanPids()
        val dtMs = if (previousAtMs > 0) nowMs - previousAtMs else 0L
        val deltaByUid = HashMap<Int, Long>()
        val countByUid = HashMap<Int, Int>()
        for ((pid, sample) in seen) {
            countByUid[sample.uid] = (countByUid[sample.uid] ?: 0) + 1
            val prior = previous[pid] ?: continue
            if (prior.uid != sample.uid) continue
            val delta = (sample.jiffies - prior.jiffies).coerceAtLeast(0)
            if (delta == 0L) continue
            deltaByUid[sample.uid] = (deltaByUid[sample.uid] ?: 0L) + delta
        }
        previous.clear()
        previous.putAll(seen)
        previousAtMs = nowMs

        val percents = HashMap<Int, Float>()
        if (dtMs >= 40) {
            deltaByUid.forEach { (uid, delta) ->
                ProcCpuParser.percentOfAll(delta, dtMs, ticks, online)?.let { percents[uid] = it }
            }
        }
        val tracked = (histories.keys + percents.keys).toSet()
        tracked.forEach { uid ->
            val history = histories.getOrPut(uid) { ArrayDeque() }
            history.addLast(CpuPoint(nowMs, percents[uid] ?: 0f))
            while (history.size > MAX_POINTS) history.removeFirst()
        }
        val rows = tracked.map { uid ->
            val identity = apps.identity(uid)
            AppCpuUsage(
                app = identity,
                percent = percents[uid] ?: 0f,
                processCount = countByUid[uid] ?: 0,
                history = histories[uid]?.toList().orEmpty(),
            )
        }
            .filter { it.percent > 0.15f || it.history.takeLast(6).any { point -> point.percent > 0.4f } }
            .sortedByDescending { it.percent }
            .take(12)
        val keep = rows.map { it.app.uid }.toSet()
        histories.keys.retainAll(keep)

        val selfUid = android.os.Process.myUid()
        val others = rows.any { it.app.uid != selfUid && it.percent > 0.2f }
        val note = when {
            seen.isEmpty() ->
                "Android hid every process CPU counter. The overall % above still comes from kernel uptime."
            !others && rows.isNotEmpty() ->
                "Android only lets Pulse read its own process CPU time. Other apps stay hidden."
            rows.isEmpty() ->
                "Waiting for a second sample. Each line is that app’s share of all CPU cores."
            else ->
                "Each line is that app’s share of all CPU cores. Android still hides some processes."
        }
        return CpuDetail(
            overallPercent = cpu.usagePercent,
            sourceLabel = cpu.sourceLabel,
            cores = cpu.cores,
            apps = rows,
            readableProcessCount = seen.size,
            note = note,
        )
    }

    private fun scanPids(): Map<Int, PidSample> {
        val root = File("/proc")
        val dirs = try {
            root.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } }
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val out = HashMap<Int, PidSample>()
        dirs.take(MAX_PIDS).forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = readText(File(dir, "stat")) ?: return@forEach
            val parsed = ProcCpuParser.parseStat(stat) ?: return@forEach
            val uid = try {
                Os.stat(dir.absolutePath).st_uid
            } catch (_: Exception) {
                -1
            }
            out[pid] = PidSample(uid = uid, jiffies = parsed.jiffies)
        }
        return out
    }

    private fun ticksPerSecond(): Long {
        return try {
            Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
        } catch (_: Exception) {
            100L
        }
    }

    private fun readText(file: File): String? {
        return try {
            file.inputStream().bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private data class PidSample(val uid: Int, val jiffies: Long)

    companion object {
        private const val MAX_PIDS = 400
        private const val MAX_POINTS = 60
    }
}
