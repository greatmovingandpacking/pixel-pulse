package app.pixelpulse

import android.app.usage.UsageEvents
import app.pixelpulse.monitor.AppIdentity
import app.pixelpulse.monitor.AppNetworkUsage
import app.pixelpulse.monitor.MemInfoParser
import app.pixelpulse.monitor.MemoryBreakdownCollector
import app.pixelpulse.monitor.NetworkDiagnose
import app.pixelpulse.monitor.NetworkFinding
import app.pixelpulse.monitor.NetworkInfo
import app.pixelpulse.monitor.ProcessMemoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailLogicTest {
    @Test
    fun memInfoParsesKbAndBuildsSlices() {
        val kb = MemInfoParser.parseKb(
            """
            MemTotal:       8000000 kB
            MemFree:        1000000 kB
            MemAvailable:   3000000 kB
            Buffers:         200000 kB
            Cached:         1500000 kB
            AnonPages:      2500000 kB
            SReclaimable:    300000 kB
            SUnreclaim:      100000 kB
            KernelStack:      20000 kB
            PageTables:       30000 kB
            Percpu:           10000 kB
            """.trimIndent(),
        )
        assertEquals(8_000_000L, kb["MemTotal"])
        assertEquals(5_000_000L * 1024, MemInfoParser.usedBytes(kb))
        val slices = MemInfoParser.slices(kb)
        assertTrue(slices.any { it.key == "apps" && it.bytes == 2_500_000L * 1024 })
        assertTrue(slices.any { it.key == "cache" && it.bytes == 1_800_000L * 1024 })
        assertTrue(slices.any { it.key == "free" && it.bytes == 1_000_000L * 1024 })
    }

    @Test
    fun diagnoseCaptivePortalAndTopTalker() {
        val network = NetworkInfo(
            connected = true,
            transportLabel = "Wi-Fi",
            rxBytesPerSec = 1000,
            txBytesPerSec = 100,
            rxTotal = 10,
            txTotal = 10,
            downlinkCapKbps = null,
            uplinkCapKbps = null,
            wifiLinkMbps = 200,
            validated = false,
            captivePortal = true,
        )
        val hog = AppNetworkUsage(
            app = AppIdentity(10123, "com.example.video", "Video", false),
            rxBytes = 9_000_000,
            txBytes = 100_000,
            rxBytesPerSec = 0,
            txBytesPerSec = 0,
            wifiBytes = 9_100_000,
            mobileBytes = 0,
            foregroundBytes = 9_100_000,
            backgroundBytes = 0,
            history = emptyList(),
        )
        val findings = NetworkDiagnose.analyze(network, listOf(hog), emptyList(), true)
        assertTrue(findings.any { it.title.contains("Captive") && it.severity == NetworkFinding.Severity.WARN })
        assertTrue(findings.any { it.title.contains("Video") })
    }

    @Test
    fun diagnoseOffline() {
        val network = NetworkInfo(
            connected = false,
            transportLabel = "Offline",
            rxBytesPerSec = 0,
            txBytesPerSec = 0,
            rxTotal = 0,
            txTotal = 0,
            downlinkCapKbps = null,
            uplinkCapKbps = null,
            wifiLinkMbps = null,
        )
        val findings = NetworkDiagnose.analyze(network, emptyList(), emptyList(), false)
        assertTrue(findings.any { it.severity == NetworkFinding.Severity.BAD && it.title.contains("No network") })
    }

    @Test
    fun importanceMapsToServiceAndCached() {
        assertEquals(ProcessMemoryRow.Kind.SERVICE, MemoryBreakdownCollector.importanceKind(125))
        assertEquals(ProcessMemoryRow.Kind.CACHED, MemoryBreakdownCollector.importanceKind(400))
        assertEquals("Foreground service", MemoryBreakdownCollector.importanceLabel(125))
        assertEquals("Youtube", MemoryBreakdownCollector.displayName("com.google.android.youtube"))
    }

    @Test
    fun usageEventFilterKeepsResumeAndIgnoresNoise() {
        assertTrue(MemoryBreakdownCollector.isTrackedUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED))
        assertTrue(!MemoryBreakdownCollector.isTrackedUsageEvent(UsageEvents.Event.NONE))
    }
}
