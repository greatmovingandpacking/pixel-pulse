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
import kotlinx.coroutines.withContext

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val collector = ResourceCollector(application.applicationContext)
    private val appDirectory = AppDirectory(application.applicationContext)
    private val perAppNetwork = PerAppNetworkCollector(application.applicationContext, appDirectory)
    private val memoryBreakdown = MemoryBreakdownCollector(application.applicationContext, appDirectory)
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

                val granted = UsageAccess.granted(getApplication())
                val appRows: List<AppNetworkUsage> = if (granted && tick % 2 == 0) {
                    withContext(Dispatchers.IO) {
                        perAppNetwork.collect(now, PerAppNetworkCollector.WINDOW_MS)
                    }
                } else {
                    _networkDetail.value.apps
                }
                if (tick % 2 == 0) {
                    _memoryDetail.value = withContext(Dispatchers.IO) {
                        memoryBreakdown.collect(granted)
                    }
                }
                val points = timeline.toList()
                _networkDetail.value = NetworkDetail(
                    hasUsageAccess = granted,
                    findings = NetworkDiagnose.analyze(snap.network, appRows, points, granted),
                    timeline = points,
                    apps = appRows,
                    windowMs = PerAppNetworkCollector.WINDOW_MS,
                )
                tick++
                delay(1_000)
            }
        }
    }

    fun refreshPermissions() {
        val granted = UsageAccess.granted(getApplication())
        if (granted != _networkDetail.value.hasUsageAccess) {
            _networkDetail.value = _networkDetail.value.copy(hasUsageAccess = granted)
        }
    }

    companion object {
        private const val MAX_TIMELINE_POINTS = 300
    }
}
