package app.pixelpulse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pixelpulse.monitor.AppDirectory
import app.pixelpulse.monitor.AppNetworkUsage
import app.pixelpulse.monitor.CpuDetail
import app.pixelpulse.monitor.MemoryBreakdownCollector
import app.pixelpulse.monitor.MemoryDetail
import app.pixelpulse.monitor.NetworkDetail
import app.pixelpulse.monitor.ProcessCpuCollector
import app.pixelpulse.monitor.NetworkDiagnose
import app.pixelpulse.monitor.NetworkInfo
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
import kotlinx.coroutines.flow.collectLatest
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
    private val processCpu = ProcessCpuCollector(application.applicationContext, appDirectory)
    private val usageMutex = Mutex()
    private val timeline = ArrayDeque<RatePoint>()
    private val foreground = MutableStateFlow(false)
    private val visibleScreen = MutableStateFlow(MonitorScreen.Dashboard)
    private var lastNetworkCollectMs = 0L
    private var lastMemoryCollectMs = 0L

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

    private val _cpuDetail = MutableStateFlow(
        CpuDetail(
            overallPercent = null,
            sourceLabel = "waiting",
            cores = emptyList(),
            apps = emptyList(),
            readableProcessCount = 0,
            note = "",
            hasUsageAccess = false,
        ),
    )
    val cpuDetail: StateFlow<CpuDetail> = _cpuDetail.asStateFlow()

    val apps: AppDirectory get() = appDirectory

    init {
        viewModelScope.launch {
            foreground.collectLatest { active ->
                if (!active) return@collectLatest
                while (isActive) {
                    try {
                        sampleOnce()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Keep the live loop alive after Usage access starts filling per-app data.
                    }
                    delay(1_000)
                }
            }
        }
    }

    fun setForeground(active: Boolean) {
        if (active && !foreground.value) {
            lastNetworkCollectMs = 0L
            lastMemoryCollectMs = 0L
        }
        foreground.value = active
    }

    fun setVisibleScreen(screen: MonitorScreen) {
        if (visibleScreen.value == screen) return
        if (screen == MonitorScreen.Network) lastNetworkCollectMs = 0L
        if (screen == MonitorScreen.Memory) lastMemoryCollectMs = 0L
        visibleScreen.value = screen
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            try {
                val granted = usageGranted()
                val now = System.currentTimeMillis()
                val visible = visibleScreen.value
                val (appRows, memory) = usageMutex.withLock {
                    val rows = when {
                        !granted -> emptyList()
                        visible == MonitorScreen.Network -> {
                            lastNetworkCollectMs = now
                            loadNetworkApps(now)
                        }
                        else -> _networkDetail.value.apps
                    }
                    val mem = if (visible == MonitorScreen.Memory) {
                        lastMemoryCollectMs = now
                        loadMemory(granted)
                    } else {
                        _memoryDetail.value.copy(hasUsageAccess = granted)
                    }
                    rows to mem
                }
                publishNetwork(granted, appRows)
                _memoryDetail.value = memory
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun sampleOnce() {
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
        val visible = visibleScreen.value
        val networkDue = granted &&
            visible == MonitorScreen.Network &&
            now - lastNetworkCollectMs >= DETAIL_INTERVAL_MS
        val memoryDue = visible == MonitorScreen.Memory &&
            now - lastMemoryCollectMs >= DETAIL_INTERVAL_MS
        var appRows = if (granted) _networkDetail.value.apps else emptyList()
        var memory: MemoryDetail? = null
        if (!granted || networkDue || memoryDue) {
            val loaded = usageMutex.withLock {
                val rows = when {
                    !granted -> emptyList()
                    networkDue -> {
                        lastNetworkCollectMs = now
                        loadNetworkApps(now)
                    }
                    else -> appRows
                }
                val mem = if (memoryDue) {
                    lastMemoryCollectMs = now
                    loadMemory(granted)
                } else {
                    null
                }
                rows to mem
            }
            appRows = loaded.first
            memory = loaded.second
        }
        if (memory != null) _memoryDetail.value = memory
        publishNetwork(granted, appRows, snap.network)
        _cpuDetail.value = withContext(Dispatchers.IO) {
            try {
                processCpu.collect(now, snap.cpu, granted)
            } catch (_: Throwable) {
                _cpuDetail.value.copy(
                    overallPercent = snap.cpu.usagePercent,
                    sourceLabel = snap.cpu.sourceLabel,
                    cores = snap.cpu.cores,
                    hasUsageAccess = granted,
                )
            }
        }
    }

    private fun publishNetwork(
        granted: Boolean,
        appRows: List<AppNetworkUsage>,
        network: NetworkInfo? = _snapshot.value?.network,
    ) {
        val points = timeline.toList()
        if (network != null) {
            _networkDetail.value = NetworkDetail(
                hasUsageAccess = granted,
                findings = NetworkDiagnose.analyze(network, appRows, points, granted),
                timeline = points,
                apps = appRows,
                windowMs = PerAppNetworkCollector.WINDOW_MS,
            )
        } else {
            _networkDetail.value = _networkDetail.value.copy(
                hasUsageAccess = granted,
                apps = appRows,
                timeline = points,
            )
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
        private const val DETAIL_INTERVAL_MS = 10_000L
    }
}

enum class MonitorScreen { Dashboard, Network, Memory, Cpu }
