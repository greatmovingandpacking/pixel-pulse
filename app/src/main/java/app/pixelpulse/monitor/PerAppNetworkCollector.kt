package app.pixelpulse.monitor

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager

class PerAppNetworkCollector(
    context: Context,
    private val apps: AppDirectory,
) {
    private val nsm = context.applicationContext.getSystemService(NetworkStatsManager::class.java)
    private val sessionStartMs = System.currentTimeMillis()
    private val sessionPrevious = mutableMapOf<Int, BytePair>()
    private val histories = mutableMapOf<Int, ArrayDeque<RatePoint>>()

    @Synchronized
    fun collect(nowMs: Long, windowMs: Long): List<AppNetworkUsage> {
        if (nsm == null) return emptyList()
        return try {
            collectLocked(nowMs, windowMs)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun collectLocked(nowMs: Long, windowMs: Long): List<AppNetworkUsage> {
        val windowStart = (nowMs - windowMs).coerceAtLeast(0)
        val windowAcc = mutableMapOf<Int, Accumulator>()
        queryInto(ConnectivityManager.TYPE_WIFI, windowStart, nowMs, windowAcc, Link.WIFI)
        queryInto(ConnectivityManager.TYPE_MOBILE, windowStart, nowMs, windowAcc, Link.MOBILE)
        queryInto(ConnectivityManager.TYPE_ETHERNET, windowStart, nowMs, windowAcc, Link.OTHER)

        // Rates come from a counter that only grows (session start → now).
        // Differencing the sliding 5-minute window cancels steady traffic.
        val sessionAcc = mutableMapOf<Int, Accumulator>()
        val sessionFrom = sessionStartMs.coerceAtMost(nowMs)
        queryInto(ConnectivityManager.TYPE_WIFI, sessionFrom, nowMs, sessionAcc, Link.WIFI)
        queryInto(ConnectivityManager.TYPE_MOBILE, sessionFrom, nowMs, sessionAcc, Link.MOBILE)
        queryInto(ConnectivityManager.TYPE_ETHERNET, sessionFrom, nowMs, sessionAcc, Link.OTHER)

        val rows = (windowAcc.keys + sessionAcc.keys).map { uid ->
            val window = windowAcc[uid]
            val session = sessionAcc[uid]
            val prev = sessionPrevious[uid]
            val dtSec = if (prev != null) ((nowMs - prev.atMs).coerceAtLeast(1)) / 1000.0 else 0.0
            val sessionRx = session?.rx ?: 0L
            val sessionTx = session?.tx ?: 0L
            val rxRate = if (prev != null && dtSec > 0) ((sessionRx - prev.rx).coerceAtLeast(0) / dtSec).toLong() else 0L
            val txRate = if (prev != null && dtSec > 0) ((sessionTx - prev.tx).coerceAtLeast(0) / dtSec).toLong() else 0L
            sessionPrevious[uid] = BytePair(sessionRx, sessionTx, nowMs)
            val history = histories.getOrPut(uid) { ArrayDeque() }
            history.addLast(RatePoint(nowMs, rxRate, txRate))
            while (history.size > MAX_POINTS) history.removeFirst()
            AppNetworkUsage(
                app = apps.identity(uid),
                rxBytes = window?.rx ?: 0L,
                txBytes = window?.tx ?: 0L,
                rxBytesPerSec = rxRate,
                txBytesPerSec = txRate,
                wifiBytes = window?.wifi ?: 0L,
                mobileBytes = window?.mobile ?: 0L,
                foregroundBytes = window?.foreground ?: 0L,
                backgroundBytes = window?.background ?: 0L,
                history = history.toList(),
            )
        }
            .filter { it.totalBytes > 0 || it.totalRate > 0 }
            .sortedByDescending { it.totalBytes }
        val keep = rows.take(40).map { it.app.uid }.toSet()
        histories.keys.retainAll(keep)
        sessionPrevious.keys.retainAll(keep)
        return rows
    }

    private fun queryInto(
        networkType: Int,
        start: Long,
        end: Long,
        acc: MutableMap<Int, Accumulator>,
        link: Link,
    ) {
        val stats = try {
            nsm.querySummary(networkType, null, start, end)
        } catch (_: SecurityException) {
            return
        } catch (_: Exception) {
            return
        } ?: return
        val bucket = NetworkStats.Bucket()
        try {
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val uid = bucket.uid
                if (uid == NetworkStats.Bucket.UID_ALL) continue
                val row = acc.getOrPut(uid) { Accumulator() }
                row.rx += bucket.rxBytes
                row.tx += bucket.txBytes
                val combined = bucket.rxBytes + bucket.txBytes
                when (link) {
                    Link.WIFI -> row.wifi += combined
                    Link.MOBILE -> row.mobile += combined
                    Link.OTHER -> Unit
                }
                if (bucket.state == NetworkStats.Bucket.STATE_FOREGROUND) {
                    row.foreground += combined
                } else {
                    row.background += combined
                }
            }
        } catch (_: Throwable) {
            // A bad bucket or binder failure should not take down the app.
        } finally {
            try {
                stats.close()
            } catch (_: Throwable) {
            }
        }
    }

    private enum class Link { WIFI, MOBILE, OTHER }

    private data class BytePair(val rx: Long, val tx: Long, val atMs: Long)
    private class Accumulator {
        var rx: Long = 0
        var tx: Long = 0
        var wifi: Long = 0
        var mobile: Long = 0
        var foreground: Long = 0
        var background: Long = 0
    }

    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_POINTS = 150
    }
}
