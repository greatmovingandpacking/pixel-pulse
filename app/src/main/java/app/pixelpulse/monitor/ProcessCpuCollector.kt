package app.pixelpulse.monitor

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

class ProcessCpuCollector(
    context: Context,
    private val apps: AppDirectory,
) {
    private val appContext = context.applicationContext
    private val previousMicros = HashMap<Int, Long>()
    private var previousAtMs = 0L
    private var previousSource = ""
    private val histories = HashMap<Int, ArrayDeque<CpuPoint>>()
    private val knownUids = LinkedHashSet<Int>()
    private var lastDiscoverMs = 0L
    private var statFileForUid: ((Int) -> File)? = null

    @Synchronized
    fun collect(nowMs: Long, cpu: CpuInfo, hasUsageAccess: Boolean): CpuDetail {
        return try {
            collectLocked(nowMs, cpu, hasUsageAccess)
        } catch (_: Throwable) {
            emptyDetail(cpu, hasUsageAccess, "Could not read process CPU this pass.")
        }
    }

    private fun collectLocked(nowMs: Long, cpu: CpuInfo, hasUsageAccess: Boolean): CpuDetail {
        val online = cpu.cores.count { it.online }.coerceAtLeast(1)
        if (nowMs - lastDiscoverMs >= DISCOVER_MS) {
            lastDiscoverMs = nowMs
            knownUids.clear()
            knownUids.addAll(discoverUids(hasUsageAccess))
        }
        knownUids.add(Process.myUid())
        knownUids.addAll(histories.keys)

        val snapshot = readUidCpu()
        if (snapshot.source != previousSource) {
            previousMicros.clear()
            previousAtMs = 0L
            previousSource = snapshot.source
        }

        val dtMs = if (previousAtMs > 0) nowMs - previousAtMs else 0L
        val percents = HashMap<Int, Float>()
        if (dtMs in MIN_DELTA_MS..MAX_DELTA_MS) {
            snapshot.micros.forEach { (uid, micros) ->
                val prior = previousMicros[uid] ?: return@forEach
                val delta = (micros - prior).coerceAtLeast(0)
                ProcCpuParser.percentFromMicros(delta, dtMs, online)?.let { percents[uid] = it }
            }
        }
        previousMicros.clear()
        previousMicros.putAll(snapshot.micros)
        previousAtMs = nowMs

        val cutoff = nowMs - WINDOW_MS
        percents.forEach { (uid, percent) ->
            if (percent <= 0.01f && !histories.containsKey(uid)) return@forEach
            val history = histories.getOrPut(uid) { ArrayDeque() }
            history.addLast(CpuPoint(nowMs, percent))
            while (history.isNotEmpty() && history.first().timestampMs < cutoff) history.removeFirst()
        }
        histories.keys.toList().forEach { uid ->
            if (!percents.containsKey(uid)) {
                val history = histories[uid] ?: return@forEach
                history.addLast(CpuPoint(nowMs, 0f))
                while (history.isNotEmpty() && history.first().timestampMs < cutoff) history.removeFirst()
            }
        }

        val rows = histories.keys.map { uid ->
            val identity = apps.identity(uid)
            AppCpuUsage(
                app = identity,
                percent = percents[uid] ?: 0f,
                processCount = snapshot.processCountByUid[uid] ?: 0,
                history = histories[uid]?.toList().orEmpty(),
            )
        }
            .filter { keepRow(it.percent, it.history, nowMs) }
            .sortedWith(
                compareByDescending<AppCpuUsage> { if (isAppUid(it.app.uid)) 1 else 0 }
                    .thenByDescending { it.percent }
                    .thenByDescending { it.history.maxOfOrNull { point -> point.percent } ?: 0f },
            )
            .take(12)
        val keep = rows.map { it.app.uid }.toSet()
        histories.keys.retainAll(keep)

        val selfUid = Process.myUid()
        val otherApps = rows.any { it.app.uid != selfUid && isAppUid(it.app.uid) }
        val note = when {
            snapshot.micros.isEmpty() ->
                "Android hid every per-app CPU counter. The overall % above still comes from kernel uptime."
            !otherApps && !hasUsageAccess ->
                "Pulse can only see its own process list unless Usage access is on. Grant it so YouTube and other apps can be found, then Pulse reads their UID CPU time."
            !otherApps ->
                "Pulse still cannot read other apps’ CPU counters on this Pixel. The overall % is real; per-app lines stay limited to what Android exposes."
            else ->
                "Each line is that app’s share of all CPU cores over the last 30 seconds."
        }
        return CpuDetail(
            overallPercent = cpu.usagePercent,
            sourceLabel = cpu.sourceLabel,
            cores = cpu.cores,
            apps = rows,
            readableProcessCount = snapshot.micros.size,
            note = note,
            hasUsageAccess = hasUsageAccess,
            windowMs = WINDOW_MS,
        )
    }

    private fun emptyDetail(cpu: CpuInfo, hasUsageAccess: Boolean, note: String): CpuDetail {
        return CpuDetail(
            overallPercent = cpu.usagePercent,
            sourceLabel = cpu.sourceLabel,
            cores = cpu.cores,
            apps = emptyList(),
            readableProcessCount = 0,
            note = note,
            hasUsageAccess = hasUsageAccess,
            windowMs = WINDOW_MS,
        )
    }

    private fun readUidCpu(): UidCpuSnapshot {
        val fromStat = readShowUidStat()
        if (coversOtherApps(fromStat)) {
            return UidCpuSnapshot(fromStat, emptyMap(), "uid_cputime")
        }
        val fromCgroup = listCgroupUidStats()
        if (coversOtherApps(fromCgroup)) {
            return UidCpuSnapshot(fromCgroup, emptyMap(), "cgroup")
        }
        val probed = HashMap<Int, Long>()
        knownUids.forEach { uid ->
            readUidFileMicros(uid)?.let { probed[uid] = it }
        }
        if (coversOtherApps(probed)) {
            return UidCpuSnapshot(probed, emptyMap(), "cgroup")
        }
        val pids = scanPids()
        val ticks = ticksPerSecond()
        val byUid = HashMap<Int, Long>()
        val counts = HashMap<Int, Int>()
        pids.forEach { (_, sample) ->
            counts[sample.uid] = (counts[sample.uid] ?: 0) + 1
            val micros = ProcCpuParser.jiffiesToMicros(sample.jiffies, ticks)
            byUid[sample.uid] = (byUid[sample.uid] ?: 0L) + micros
        }
        fromStat.forEach { (uid, micros) -> byUid[uid] = micros }
        fromCgroup.forEach { (uid, micros) -> byUid[uid] = micros }
        probed.forEach { (uid, micros) -> byUid[uid] = micros }
        val source = when {
            fromStat.isNotEmpty() -> "uid_cputime"
            fromCgroup.isNotEmpty() || probed.isNotEmpty() -> "cgroup"
            else -> "proc"
        }
        return UidCpuSnapshot(byUid, counts, source)
    }

    private fun coversOtherApps(micros: Map<Int, Long>): Boolean {
        val self = Process.myUid()
        return micros.keys.any { it != self && isAppUid(it) }
    }

    private fun readShowUidStat(): Map<Int, Long> {
        SHOW_UID_STAT.forEach { path ->
            val text = readText(File(path)) ?: return@forEach
            val parsed = ProcCpuParser.parseUidStat(text)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun listCgroupUidStats(): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        CGROUP_ROOTS.forEach { rootPath ->
            val root = File(rootPath)
            val dirs = try {
                root.listFiles { file -> file.isDirectory && file.name.startsWith("uid_") }
            } catch (_: Exception) {
                null
            } ?: return@forEach
            dirs.take(MAX_UIDS).forEach { dir ->
                val uid = dir.name.removePrefix("uid_").substringBefore('.').toIntOrNull() ?: return@forEach
                val micros = readCpuStatDir(dir) ?: return@forEach
                out[uid] = micros
                if (statFileForUid == null) {
                    val stat = File(dir, "cpu.stat")
                    val acct = File(dir, "cpuacct.usage")
                    val file = when {
                        stat.exists() -> stat
                        acct.exists() -> acct
                        else -> null
                    }
                    if (file != null) {
                        val template = file.absolutePath
                        statFileForUid = { id -> File(template.replace(Regex("uid_\\d+"), "uid_$id")) }
                    }
                }
            }
            if (out.size >= 2) return out
        }
        return out
    }

    private fun readUidFileMicros(uid: Int): Long? {
        statFileForUid?.let { factory ->
            readCpuStatFile(factory(uid))?.let { return it }
        }
        UID_STAT_TEMPLATES.forEach { template ->
            val file = File(template.format(uid))
            val micros = readCpuStatFile(file) ?: return@forEach
            statFileForUid = { id -> File(template.format(id)) }
            return micros
        }
        return null
    }

    private fun readCpuStatDir(dir: File): Long? {
        readCpuStatFile(File(dir, "cpu.stat"))?.let { return it }
        return readCpuStatFile(File(dir, "cpuacct.usage"))
    }

    private fun readCpuStatFile(file: File): Long? {
        val text = readText(file) ?: return null
        return ProcCpuParser.parseCpuStatUsageUsec(text)
            ?: ProcCpuParser.parseCpuacctUsageNs(text)
    }

    private fun discoverUids(hasUsageAccess: Boolean): Set<Int> {
        val uids = LinkedHashSet<Int>()
        uids += Process.myUid()
        addRunningUids(uids)
        if (hasUsageAccess) addRecentUsageUids(uids)
        addLaunchableUids(uids)
        addInstalledAppUids(uids)
        uids.addAll(histories.keys)
        uids.addAll(previousMicros.keys)
        return uids.take(MAX_UIDS).toSet()
    }

    private fun addRunningUids(into: MutableSet<Int>) {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return
        try {
            am.runningAppProcesses.orEmpty().forEach { into += it.uid }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            am.getRunningServices(80).orEmpty().forEach { into += it.uid }
        } catch (_: Exception) {
        }
    }

    private fun addRecentUsageUids(into: MutableSet<Int>) {
        val usm = appContext.getSystemService(UsageStatsManager::class.java) ?: return
        val end = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(end - USAGE_LOOKBACK_MS, end)
        } catch (_: Exception) {
            return
        } ?: return
        val event = UsageEvents.Event()
        val packages = LinkedHashSet<String>()
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (MemoryBreakdownCollector.isTrackedUsageEvent(event.eventType)) {
                    packages += pkg
                }
            }
        } catch (_: Throwable) {
            return
        }
        val pm = appContext.packageManager
        packages.forEach { pkg ->
            try {
                into += pm.getApplicationInfo(pkg, 0).uid
            } catch (_: Exception) {
            }
        }
    }

    private fun addLaunchableUids(into: MutableSet<Int>) {
        val pm = appContext.packageManager
        try {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launch, 0).forEach { info ->
                try {
                    into += info.activityInfo.applicationInfo.uid
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun addInstalledAppUids(into: MutableSet<Int>) {
        val pm = appContext.packageManager
        try {
            pm.getInstalledApplications(0).forEach { info ->
                if (info.uid >= 10_000) into += info.uid
            }
        } catch (_: Exception) {
        }
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

    private data class UidCpuSnapshot(
        val micros: Map<Int, Long>,
        val processCountByUid: Map<Int, Int>,
        val source: String,
    )

    companion object {
        const val WINDOW_MS = CpuWindows.WINDOW_MS
        private const val MAX_PIDS = 400
        private const val MAX_UIDS = 220
        private const val DISCOVER_MS = 4_000L
        private const val MIN_DELTA_MS = 40L
        private const val MAX_DELTA_MS = 3_500L
        private const val USAGE_LOOKBACK_MS = 120_000L
        private val SHOW_UID_STAT = listOf(
            "/proc/uid_cputime/show_uid_stat",
            "/proc/uid_cputime/stats",
        )
        private val CGROUP_ROOTS = listOf(
            "/sys/fs/cgroup",
            "/sys/fs/cgroup/apps",
            "/dev/cpuctl",
            "/sys/fs/cgroup/cpu",
        )
        private val UID_STAT_TEMPLATES = listOf(
            "/sys/fs/cgroup/uid_%d/cpu.stat",
            "/sys/fs/cgroup/apps/uid_%d/cpu.stat",
            "/dev/cpuctl/uid_%d/cpu.stat",
            "/sys/fs/cgroup/cpu/uid_%d/cpu.stat",
            "/acct/uid/%d/cpuacct.usage",
        )

        fun isAppUid(uid: Int): Boolean = uid >= 10_000

        fun keepRow(
            percent: Float,
            history: List<CpuPoint>,
            nowMs: Long,
            windowMs: Long = WINDOW_MS,
        ): Boolean {
            if (percent > 0.08f) return true
            val start = nowMs - windowMs
            return history.any { it.timestampMs >= start && it.percent > 0.2f }
        }
    }
}
