package app.pixelpulse

import android.app.AppOpsManager
import app.pixelpulse.monitor.ChargeMath
import app.pixelpulse.monitor.ChargeSource
import app.pixelpulse.monitor.CpuMath
import app.pixelpulse.monitor.Formatters
import app.pixelpulse.monitor.RatePoint
import app.pixelpulse.monitor.ThermalLabels
import app.pixelpulse.monitor.TrafficMath
import app.pixelpulse.monitor.UsageAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeMathTest {
    @Test
    fun currentTreatsPixelMicroampsAsMilliamps() {
        val ma = ChargeMath.currentMilliAmps(-350_000)
        assertEquals(-350.0, ma!!, 0.01)
    }

    @Test
    fun currentLeavesMilliampScaleAlone() {
        val ma = ChargeMath.currentMilliAmps(6400)
        assertEquals(6400.0, ma!!, 0.01)
    }

    @Test
    fun currentRejectsMissingProperty() {
        assertNull(ChargeMath.currentMilliAmps(Long.MIN_VALUE))
    }

    @Test
    fun powerIsVoltageTimesCurrent() {
        val watts = ChargeMath.powerWatts(4.28f, 6400.0)
        assertEquals(27.39, watts!!, 0.05)
    }

    @Test
    fun superFastLabelAbove30W() {
        val label = ChargeMath.chargeSpeedLabel(32.0, true, ChargeSource.USB)
        assertEquals("Super fast", label)
    }

    @Test
    fun wirelessKeepsItsOwnLabel() {
        val label = ChargeMath.chargeSpeedLabel(12.0, true, ChargeSource.WIRELESS)
        assertTrue(label.startsWith("Wireless"))
    }

    @Test
    fun parseProcStatAndUsage() {
        val first = CpuMath.parseProcStatLine("cpu  100 0 50 850 0 0 0 0")!!
        val second = CpuMath.parseProcStatLine("cpu  140 0 70 890 0 0 0 0")!!
        val usage = CpuMath.usagePercent(first, second)!!
        // user +40, system +20, idle +40, total +100 => 60%
        assertEquals(60f, usage, 0.1f)
    }

    @Test
    fun formatters() {
        assertEquals("1.0 GB", Formatters.bytes(1024L * 1024 * 1024))
        assertEquals("12.0 MB/s", Formatters.rateBytesPerSec(12_582_912))
        assertEquals("Not throttling", ThermalLabels.label(0))
        assertEquals("Severe", ThermalLabels.label(3))
        assertEquals("2h 3m", Formatters.uptime(2 * 3_600_000L + 3 * 60_000L))
    }

    @Test
    fun parseCpuListRanges() {
        assertEquals(listOf(0, 1, 2, 3, 6, 7), CpuMath.parseCpuList("0-3,6-7"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), CpuMath.parseCpuList("0-7"))
    }

    @Test
    fun freqUtilAndIdleUsage() {
        assertEquals(50f, CpuMath.freqUtilPercent(1500, 1000, 2000)!!, 0.1f)
        assertEquals(25f, CpuMath.usageFromIdle(1_000_000, 1_750_000, 1_000_000)!!, 0.1f)
        assertEquals(0.52f, CpuMath.parseLoadAvg("0.52 0.58 0.59 2/1234 99")!!, 0.001f)
    }

    @Test
    fun uptimeBusyPercentUsesIdleAcrossCores() {
        val first = CpuMath.parseUptime("100.00 700.00")!!
        val second = CpuMath.parseUptime("101.00 706.40")!!
        // 1s wall, 8 cores, idle +6.40 => busy 1.60 / 8 = 20%
        assertEquals(20f, CpuMath.usageFromUptime(first, second, 8)!!, 0.15f)
    }

    @Test
    fun uptimeRejectsZeroWall() {
        val sample = CpuMath.parseUptime("50.00 100.00")!!
        assertNull(CpuMath.usageFromUptime(sample, sample, 8))
    }

    @Test
    fun shareOverallKeepsAverage() {
        val shared = CpuMath.shareOverall(20f, listOf(1000f, 1000f, 1000f, 1000f))
        val mean = shared.mapNotNull { it }.average()
        assertEquals(20.0, mean, 0.01)
        assertEquals(4, shared.size)
    }

    @Test
    fun shareOverallWeightsFasterCores() {
        val shared = CpuMath.shareOverall(25f, listOf(3000f, 1000f, 0f))
        assertTrue(shared[0]!! > shared[1]!!)
        assertNull(shared[2])
        assertEquals(25.0, listOf(shared[0]!!, shared[1]!!).average(), 0.15)
    }

    @Test
    fun shareOverallFlattensWhenACoreWouldExceed100() {
        val shared = CpuMath.shareOverall(80f, listOf(3000f, 1000f, 0f))
        assertEquals(80f, shared[0]!!, 0.01f)
        assertEquals(80f, shared[1]!!, 0.01f)
        assertNull(shared[2])
    }

    @Test
    fun trafficTotalsFollowRealGaps() {
        val points = listOf(
            RatePoint(0, 100, 0),
            RatePoint(1_000, 1_000, 200),
            RatePoint(3_000, 1_000, 0),
        )
        assertEquals(3_000L, TrafficMath.bytesOver(points) { it.rxBytesPerSec })
        assertEquals(200L, TrafficMath.bytesOver(points) { it.txBytesPerSec })
        assertEquals("Last 3 seconds", TrafficMath.spanLabel(points))
    }

    @Test
    fun usageAccessUsesAppOpsModes() {
        assertTrue(UsageAccess.allowed(AppOpsManager.MODE_ALLOWED, false))
        assertTrue(UsageAccess.allowed(AppOpsManager.MODE_DEFAULT, true))
        assertTrue(!UsageAccess.allowed(AppOpsManager.MODE_DEFAULT, false))
        assertTrue(!UsageAccess.allowed(AppOpsManager.MODE_IGNORED, true))
    }

    @Test
    fun sequentialUptimeSamplesChange() {
        val a = CpuMath.parseUptime("10.00 70.00")!!
        val busy = CpuMath.parseUptime("11.00 74.00")!!
        val idle = CpuMath.parseUptime("12.00 81.90")!!
        val high = CpuMath.usageFromUptime(a, busy, 8)!!
        val low = CpuMath.usageFromUptime(busy, idle, 8)!!
        assertTrue(high > 40f)
        assertTrue(low < 15f)
        assertTrue(high != low)
    }
}
