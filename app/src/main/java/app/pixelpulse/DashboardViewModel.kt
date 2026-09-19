package app.pixelpulse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pixelpulse.monitor.AppDirectory
import app.pixelpulse.monitor.AppNetworkUsage
import app.pixelpulse.monitor.MemoryBreakdownCollector
import app.pixelpulse.monitor.MemoryDetail
import app.pixelpulse.monitor.NetworkDetail
import app.pixelpulse.monitor.NetworkDiagnose
import app.pixelpulse.monitor.PerAppNetworkCollector
import app.pixelpulse.monitor.RatePoint
import app.pixelpulse.monitor.ResourceCollector
import app.pixelpulse.monitor.ResourceSnapshot
import app.pixelpulse.monitor.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val collector = ResourceCollector(application.applicationContext)
    private val appDirectory = AppDirectory(application.applicationContext)
    private val perAppNetwork = PerAppNetworkCollector(application.applicationContext, appDirectory)
    private val memoryBreakdown = MemoryBreakdownCollector(application.applicationContext, appDirectory)
    private val usageMutex = Mutex()
    private val timeline = ArrayDeque<RatePoint>()

    private val _snapshot = MutableStateFlow<ResourceSnapshot?>(null)
    val snapshot: StateFlow<ResourceSnapshot?> = _snapshot.asStateFlow()

    private val _networkDetail = MutableStateFlow(
        NetworkDetail(
            hasUsageAccess = false,
            findings = emptyList(),
            timeline = emptyList(),
            apps = emptyList(),
            windowMs = PerAppNetworkCollector.WINDOW_MS,
        ),
    )
    val networkDetail: StateFlow<NetworkDetail> = _networkDetail.asStateFlow()

    private val _memoryDetail = MutableStateFlow(
        MemoryDetail(
            hasUsageAccess = false,
            totalBytes = 0,
            availableBytes = 0,
            usedBytes = 0,
            swapUsedBytes = 0,
            swapTotalBytes = 0,
            slices = emptyList(),
            processes = emptyList(),
            note = "",
        ),
    )
    val memoryDetail: StateFlow<MemoryDetail> = _memoryDetail.asStateFlow()

    val apps: AppDirectory get() = appDirectory

    init {
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                try {
                    val snap = withContext(Dispatchers.IO) { collector.sample() }
                    _snapshot.value = snap
                    val now = System.currentTimeMillis()
                    timeline.addLast(
                        RatePoint(
                            timestampMs = now,
                            rxBytesPerSec = snap.network.rxBytesPerSec ?: 0L,
                            txBytesPerSec = snap.network.txBytesPerSec ?: 0L,
                        ),
                    )
                    while (timeline.size > MAX_TIMELINE_POINTS) timeline.removeFirst()

                    val granted = usageGranted()
                    val sampled = tick % 2 == 0
                    val (appRows, memory) = usageMutex.withLock {
                        val rows = if (granted && sampled) {
                            loadNetworkApps(now)
                        } else {
                            _networkDetail.value.apps
                        }
                        val mem = if (sampled) loadMemory(granted) else null
                        rows to mem
                    }
                    if (memory != null) _memoryDetail.value = memory
                    val points = timeline.toList()
                    _networkDetail.value = NetworkDetail(
                        hasUsageAccess = granted,
                        findings = NetworkDiagnose.analyze(snap.network, appRows, points, granted),
                        timeline = points,
                        apps = appRows,
                        windowMs = PerAppNetworkCollector.WINDOW_MS,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Keep the live loop alive after Usage access starts filling per-app data.
                }
                tick++
                delay(1_000)
            }
        }
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            try {
                val granted = usageGranted()
                val now = System.currentTimeMillis()
                val (appRows, memory) = usageMutex.withLock {
                    val rows = if (granted) loadNetworkApps(now) else emptyList()
                    rows to loadMemory(granted)
                }
                val snap = _snapshot.value
                if (snap != null) {
                    _networkDetail.value = _networkDetail.value.copy(
                        hasUsageAccess = granted,
                        apps = appRows,
                        findings = NetworkDiagnose.analyze(
                            snap.network,
                            appRows,
                            _networkDetail.value.timeline,
                            granted,
                        ),
                    )
                } else {
                    _networkDetail.value = _networkDetail.value.copy(
                        hasUsageAccess = granted,
                        apps = appRows,
                    )
                }
                _memoryDetail.value = memory
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    private fun usageGranted(): Boolean {
        return try {
            UsageAccess.granted(getApplication())
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun loadNetworkApps(now: Long): List<AppNetworkUsage> {
        return withContext(Dispatchers.IO) {
            try {
                perAppNetwork.collect(now, PerAppNetworkCollector.WINDOW_MS)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private suspend fun loadMemory(granted: Boolean): MemoryDetail {
        return withContext(Dispatchers.IO) {
            try {
                memoryBreakdown.collect(granted)
            } catch (_: Throwable) {
                _memoryDetail.value.copy(hasUsageAccess = granted)
            }
        }
    }

    companion object {
        private const val MAX_TIMELINE_POINTS = 300
    }
}
