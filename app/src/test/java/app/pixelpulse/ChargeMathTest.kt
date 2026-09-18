package app.pixelpulse

import app.pixelpulse.monitor.ChargeMath
import app.pixelpulse.monitor.ChargeSource
import app.pixelpulse.monitor.CpuMath
import app.pixelpulse.monitor.Formatters
import app.pixelpulse.monitor.ThermalLabels
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
        assertEquals("None", ThermalLabels.label(0))
        assertEquals("Severe", ThermalLabels.label(3))
        assertEquals("2h 3m", Formatters.uptime(2 * 3_600_000L + 3 * 60_000L))
    }
}
