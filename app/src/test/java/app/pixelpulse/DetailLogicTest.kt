package app.pixelpulse

import android.app.usage.UsageEvents
import app.pixelpulse.monitor.AppIdentity
import app.pixelpulse.monitor.AppNetworkUsage
import app.pixelpulse.monitor.MemInfoParser
import app.pixelpulse.monitor.MemoryBreakdownCollector
import app.pixelpulse.monitor.NetworkDiagnose
import app.pixelpulse.monitor.NetworkFinding
import app.pixelpulse.monitor.NetworkInfo
import app.pixelpulse.monitor.CpuChartMath
import app.pixelpulse.monitor.CpuPoint
import app.pixelpulse.monitor.CpuWindows
import app.pixelpulse.monitor.ProcCpuParser
import app.pixelpulse.monitor.ProcessCpuCollector
import app.pixelpulse.monitor.ProcessMemoryRow
import app.pixelpulse.monitor.ThermalMath
import app.pixelpulse.monitor.ThermalZone
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

    @Test
    fun thermalRawTempsAndPixelZoneNames() {
        assertEquals(42.5f, ThermalMath.celsiusFromRaw(42_500)!!, 0.01f)
        assertEquals(35.0f, ThermalMath.celsiusFromRaw(350)!!, 0.01f)
        assertEquals(ThermalZone.Kind.SKIN, ThermalMath.classify("skin_therm"))
        assertEquals(ThermalZone.Kind.SKIN, ThermalMath.classify("quiet-therm-usr"))
        assertEquals(ThermalZone.Kind.CPU, ThermalMath.classify("cpu-0-0-usr"))
        assertEquals(ThermalZone.Kind.TPU, ThermalMath.classify("tpu_thermal"))
        assertEquals("Skin", ThermalMath.displayName("back_therm"))
    }

    @Test
    fun procStatParsesJiffiesAndShare() {
        val sample = ProcCpuParser.parseStat("142 (Pulse) S 1 0 0 0 0 0 0 0 0 0 20 30 0 0")!!
        assertEquals(142, sample.pid)
        assertEquals("Pulse", sample.comm)
        assertEquals(50L, sample.jiffies)
        val percent = ProcCpuParser.percentOfAll(50, 1_000, 100, 8)!!
        assertEquals(6.25f, percent, 0.05f)
    }

    @Test
    fun uidCpuParsersAndMicrosShare() {
        assertEquals(10_123 to 3_000L, ProcCpuParser.parseUidStatLine("10123: 1000 2000"))
        assertEquals(10_123 to 3_000L, ProcCpuParser.parseUidStatLine("10123 1000 2000"))
        val block = ProcCpuParser.parseUidStat("0: 10 20\n10123: 1000 2000\n")
        assertEquals(30L, block[0])
        assertEquals(3_000L, block[10_123])
        assertEquals(
            5_000L,
            ProcCpuParser.parseCpuStatUsageUsec("usage_usec 5000\nuser_usec 3000\nsystem_usec 2000\n"),
        )
        assertEquals(4_000L, ProcCpuParser.parseCpuStatUsageUsec("user_usec 3000\nsystem_usec 1000\n"))
        assertEquals(2_000L, ProcCpuParser.parseCpuacctUsageNs("2000000\n"))
        assertEquals(500_000L, ProcCpuParser.jiffiesToMicros(50, 100))
        val percent = ProcCpuParser.percentFromMicros(1_000_000, 1_000, 8)!!
        assertEquals(12.5f, percent, 0.05f)
        assertEquals(6.25f, ProcCpuParser.percentFromMicros(500_000, 1_000, 8)!!, 0.05f)
    }

    @Test
    fun cpuChartKeepsLast30Seconds() {
        val now = 100_000L
        val points = listOf(
            CpuPoint(now - 45_000, 10f),
            CpuPoint(now - 20_000, 20f),
            CpuPoint(now - 1_000, 30f),
        )
        val windowed = CpuChartMath.windowed(points, now, CpuWindows.WINDOW_MS)
        assertEquals(2, windowed.size)
        assertEquals(20f, windowed.first().percent, 0.01f)
        assertEquals(0f, CpuChartMath.xFraction(now - 30_000, now), 0.001f)
        assertEquals(1f, CpuChartMath.xFraction(now, now), 0.001f)
        assertEquals(0.5f, CpuChartMath.xFraction(now - 15_000, now), 0.001f)
        assertTrue(ProcessCpuCollector.isAppUid(10_123))
        assertTrue(!ProcessCpuCollector.isAppUid(1_000))
        assertTrue(ProcessCpuCollector.keepRow(0f, points, now))
        assertTrue(!ProcessCpuCollector.keepRow(0f, listOf(CpuPoint(now - 45_000, 8f)), now))
    }
}
