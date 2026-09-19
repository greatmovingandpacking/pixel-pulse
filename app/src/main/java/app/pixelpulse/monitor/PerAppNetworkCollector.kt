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
    private val previous = mutableMapOf<Int, BytePair>()
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
        val start = nowMs - windowMs
        val acc = mutableMapOf<Int, Accumulator>()
        queryInto(ConnectivityManager.TYPE_WIFI, start, nowMs, acc, wifi = true)
        queryInto(ConnectivityManager.TYPE_MOBILE, start, nowMs, acc, wifi = false)
        queryInto(ConnectivityManager.TYPE_ETHERNET, start, nowMs, acc, wifi = true)

        val rows = acc.map { (uid, a) ->
            val prev = previous[uid]
            val dtSec = if (prev != null) ((nowMs - prev.atMs).coerceAtLeast(1)) / 1000.0 else 0.0
            val rxRate = if (prev != null && dtSec > 0) ((a.rx - prev.rx).coerceAtLeast(0) / dtSec).toLong() else 0L
            val txRate = if (prev != null && dtSec > 0) ((a.tx - prev.tx).coerceAtLeast(0) / dtSec).toLong() else 0L
            previous[uid] = BytePair(a.rx, a.tx, nowMs)
            val history = histories.getOrPut(uid) { ArrayDeque() }
            history.addLast(RatePoint(nowMs, rxRate, txRate))
            while (history.size > MAX_POINTS) history.removeFirst()
            AppNetworkUsage(
                app = apps.identity(uid),
                rxBytes = a.rx,
                txBytes = a.tx,
                rxBytesPerSec = rxRate,
                txBytesPerSec = txRate,
                wifiBytes = a.wifi,
                mobileBytes = a.mobile,
                foregroundBytes = a.foreground,
                backgroundBytes = a.background,
                history = history.toList(),
            )
        }
            .filter { it.totalBytes > 0 || it.totalRate > 0 }
            .sortedByDescending { it.totalBytes }
        val keep = rows.take(40).map { it.app.uid }.toSet()
        histories.keys.retainAll(keep)
        previous.keys.retainAll(keep)
        return rows
    }

    private fun queryInto(
        networkType: Int,
        start: Long,
        end: Long,
        acc: MutableMap<Int, Accumulator>,
        wifi: Boolean,
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
                if (wifi) row.wifi += combined else row.mobile += combined
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
