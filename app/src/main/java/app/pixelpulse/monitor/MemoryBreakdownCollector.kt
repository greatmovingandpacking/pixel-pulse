package app.pixelpulse.monitor

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.File

class MemoryBreakdownCollector(
    context: Context,
    private val apps: AppDirectory,
) {
    private val appContext = context.applicationContext

    fun collect(hasUsageAccess: Boolean): MemoryDetail {
        return try {
            collectUnlocked(hasUsageAccess)
        } catch (_: Throwable) {
            MemoryDetail(
                hasUsageAccess = hasUsageAccess,
                totalBytes = 0,
                availableBytes = 0,
                usedBytes = 0,
                swapUsedBytes = 0,
                swapTotalBytes = 0,
                slices = emptyList(),
                processes = emptyList(),
                note = "Could not read the memory breakdown this pass.",
            )
        }
    }

    private fun collectUnlocked(hasUsageAccess: Boolean): MemoryDetail {
        val kb = readMemInfo()
        val slices = MemInfoParser.slices(kb)
        val total = (kb["MemTotal"] ?: 0L) * 1024L
        val available = (kb["MemAvailable"] ?: kb["MemFree"] ?: 0L) * 1024L
        val swapTotal = (kb["SwapTotal"] ?: 0L) * 1024L
        val swapFree = (kb["SwapFree"] ?: 0L) * 1024L
        val processes = collectProcesses(hasUsageAccess)
        val note = when {
            processes.none { it.pssBytes != null && it.pssBytes > 0 } && !hasUsageAccess ->
                "Android hides other apps' RAM sizes. Grant Usage access to list recently active apps and services."
            processes.none { it.pssBytes != null && it.pssBytes > 0 } ->
                "Android still hides per-app PSS on modern Pixels. Active apps below are from Usage access; the stacked bar is the system breakdown."
            else ->
                "PSS is an estimate of RAM uniquely plus a fair share of shared libraries."
        }
        return MemoryDetail(
            hasUsageAccess = hasUsageAccess,
            totalBytes = total,
            availableBytes = available,
            usedBytes = MemInfoParser.usedBytes(kb),
            swapUsedBytes = (swapTotal - swapFree).coerceAtLeast(0),
            swapTotalBytes = swapTotal,
            slices = slices,
            processes = processes,
            note = note,
        )
    }

    private fun collectProcesses(hasUsageAccess: Boolean): List<ProcessMemoryRow> {
        val byKey = linkedMapOf<String, ProcessMemoryRow>()
        addRunningProcesses(byKey)
        addRunningServices(byKey)
        if (hasUsageAccess) addRecentlyUsed(byKey)
        addSelf(byKey)
        return byKey.values.sortedWith(
            compareByDescending<ProcessMemoryRow> { it.pssBytes ?: -1L }
                .thenBy { kindRank(it.kind) }
                .thenBy { it.label.lowercase() },
        )
    }

    private fun addRunningProcesses(into: MutableMap<String, ProcessMemoryRow>) {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return
        val running = try {
            am.runningAppProcesses.orEmpty().take(80)
        } catch (_: Exception) {
            emptyList()
        }
        if (running.isEmpty()) return
        val mem = try {
            am.getProcessMemoryInfo(running.map { it.pid }.toIntArray())
        } catch (_: Exception) {
            emptyArray()
        }
        running.forEachIndexed { index, proc ->
            val pssKb = mem.getOrNull(index)?.totalPss ?: 0
            val pkg = proc.pkgList?.firstOrNull() ?: proc.processName
            val identity = apps.identity(proc.uid)
            val kind = importanceKind(proc.importance)
            into[proc.processName] = ProcessMemoryRow(
                key = proc.processName,
                label = identity.label.takeIf { identity.packageName != null } ?: displayName(proc.processName),
                packageName = identity.packageName ?: pkg,
                kind = kind,
                pssBytes = pssKb.takeIf { it > 0 }?.times(1024L),
                detail = importanceLabel(proc.importance) + " · pid ${proc.pid}",
            )
        }
    }

    private fun addRunningServices(into: MutableMap<String, ProcessMemoryRow>) {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return
        val services = try {
            @Suppress("DEPRECATION")
            am.getRunningServices(64).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        services.forEach { svc ->
            val pkg = svc.service.packageName
            val identity = apps.identity(svc.uid)
            val key = svc.service.flattenToShortString()
            val existing = into[key]
            if (existing != null) return@forEach
            into[key] = ProcessMemoryRow(
                key = key,
                label = identity.label.ifBlank { pkg },
                packageName = pkg,
                kind = if (svc.foreground) ProcessMemoryRow.Kind.SERVICE else ProcessMemoryRow.Kind.SERVICE,
                pssBytes = null,
                detail = buildString {
                    append(svc.service.className.substringAfterLast('.'))
                    if (svc.foreground) append(" · foreground")
                    if (svc.clientCount > 0) append(" · ${svc.clientCount} clients")
                    append(" · pid ${svc.pid}")
                },
            )
        }
    }

    private fun addRecentlyUsed(into: MutableMap<String, ProcessMemoryRow>) {
        val usm = appContext.getSystemService(UsageStatsManager::class.java) ?: return
        val end = System.currentTimeMillis()
        val start = end - PerAppNetworkCollector.WINDOW_MS
        val events = try {
            usm.queryEvents(start, end)
        } catch (_: Exception) {
            return
        } ?: return
        val last = linkedMapOf<String, Pair<Long, Int>>()
        val event = UsageEvents.Event()
        var seen = 0
        try {
            while (events.hasNextEvent() && seen < MAX_USAGE_EVENTS) {
                events.getNextEvent(event)
                seen++
                val pkg = event.packageName ?: continue
                if (isTrackedUsageEvent(event.eventType)) {
                    last[pkg] = event.timeStamp to event.eventType
                }
            }
        } catch (_: Throwable) {
            return
        }
        last.forEach { (pkg, pair) ->
            if (into.values.any { it.packageName == pkg }) return@forEach
            val identity = try {
                val uid = appContext.packageManager.getApplicationInfo(pkg, 0).uid
                apps.identity(uid)
            } catch (_: Exception) {
                AppIdentity(-1, pkg, pkg.substringAfterLast('.'), false)
            }
            val kind = when (pair.second) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> ProcessMemoryRow.Kind.SERVICE
                UsageEvents.Event.ACTIVITY_RESUMED -> ProcessMemoryRow.Kind.APP
                else -> ProcessMemoryRow.Kind.CACHED
            }
            into[pkg] = ProcessMemoryRow(
                key = pkg,
                label = identity.label,
                packageName = pkg,
                kind = kind,
                pssBytes = null,
                detail = "Active in the last ${Formatters.uptime(end - pair.first)}",
            )
        }
    }

    private fun addSelf(into: MutableMap<String, ProcessMemoryRow>) {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val pss = info.totalPss * 1024L
        val key = appContext.packageName
        into[key] = ProcessMemoryRow(
            key = key,
            label = "Pulse",
            packageName = key,
            kind = ProcessMemoryRow.Kind.APP,
            pssBytes = pss,
            detail = "This app · pid ${Process.myPid()} · Java ${info.getMemoryStat("summary.java-heap") ?: "—"} kB",
        )
    }

    private fun readMemInfo(): Map<String, Long> {
        return try {
            MemInfoParser.parseKb(File("/proc/meminfo").readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val MAX_USAGE_EVENTS = 800

        fun isTrackedUsageEvent(eventType: Int): Boolean = when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.FOREGROUND_SERVICE_START,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.FOREGROUND_SERVICE_STOP,
            -> true
            else -> false
        }

        fun importanceKind(importance: Int): ProcessMemoryRow.Kind = when {
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                ProcessMemoryRow.Kind.APP
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                ProcessMemoryRow.Kind.SERVICE
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                ProcessMemoryRow.Kind.APP
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ->
                ProcessMemoryRow.Kind.SERVICE
            else -> ProcessMemoryRow.Kind.CACHED
        }

        fun importanceLabel(importance: Int): String = when {
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground app"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground service"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Background service"
            else -> "Cached"
        }

        fun kindLabel(kind: ProcessMemoryRow.Kind): String = when (kind) {
            ProcessMemoryRow.Kind.APP -> "App"
            ProcessMemoryRow.Kind.SERVICE -> "Service"
            ProcessMemoryRow.Kind.CACHED -> "Cached"
            ProcessMemoryRow.Kind.SYSTEM -> "System"
            ProcessMemoryRow.Kind.UNKNOWN -> "Process"
        }

        fun displayName(processName: String): String {
            val leaf = processName.substringAfterLast('.')
            return if (leaf.isBlank()) processName else leaf.replaceFirstChar { it.titlecase() }
        }

        private fun kindRank(kind: ProcessMemoryRow.Kind): Int = when (kind) {
            ProcessMemoryRow.Kind.APP -> 0
            ProcessMemoryRow.Kind.SERVICE -> 1
            ProcessMemoryRow.Kind.SYSTEM -> 2
            ProcessMemoryRow.Kind.CACHED -> 3
            ProcessMemoryRow.Kind.UNKNOWN -> 4
        }
    }
}
