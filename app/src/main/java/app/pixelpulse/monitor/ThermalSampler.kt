package app.pixelpulse.monitor

import android.content.Context
import android.os.HardwarePropertiesManager
import android.os.PowerManager
import java.io.File

class ThermalSampler(context: Context) {
    private val appContext = context.applicationContext

    fun sample(batteryCelsius: Float?): ThermalInfo {
        val status = readStatus()
        val zones = linkedMapOf<String, ThermalZone>()
        readSysfs(File("/sys/class/thermal"), zones)
        readSysfs(File("/sys/devices/virtual/thermal"), zones)
        readHwmon(zones)
        readHardwareProps(zones)
        if (batteryCelsius != null && zones.values.none { it.kind == ThermalZone.Kind.BATTERY }) {
            zones["battery"] = ThermalZone("battery", "Battery", batteryCelsius, ThermalZone.Kind.BATTERY)
        }
        val list = zones.values.sortedWith(
            compareBy<ThermalZone> { kindRank(it.kind) }
                .thenByDescending { it.celsius }
                .thenBy { it.label },
        )
        val skin = list.filter { it.kind == ThermalZone.Kind.SKIN }.maxByOrNull { it.celsius }?.celsius
        val cpu = list.filter { it.kind == ThermalZone.Kind.CPU }.maxByOrNull { it.celsius }?.celsius
        val battery = list.filter { it.kind == ThermalZone.Kind.BATTERY }.maxByOrNull { it.celsius }?.celsius
            ?: batteryCelsius
        val hottest = list.maxByOrNull { it.celsius }?.celsius
        val note = when {
            list.isEmpty() ->
                "Android only exposes a throttle level to apps. Pulse could not read thermal-zone files on this build."
            status == 0 ->
                "Not throttling. Temperatures come from Pixel thermal sensors plus the battery thermometer."
            else ->
                "Android is reducing performance to cool the phone."
        }
        return ThermalInfo(
            status = status,
            label = ThermalLabels.label(status),
            zones = list,
            hottestCelsius = hottest,
            skinCelsius = skin,
            cpuCelsius = cpu,
            batteryCelsius = battery,
            note = note,
        )
    }

    private fun readStatus(): Int {
        return try {
            appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        } catch (_: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    private fun readSysfs(root: File, into: MutableMap<String, ThermalZone>) {
        val dirs = try {
            root.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
        } catch (_: Exception) {
            null
        } ?: return
        dirs.forEach { dir ->
            val type = readText(File(dir, "type"))?.trim().orEmpty()
            if (type.isBlank()) return@forEach
            val raw = readText(File(dir, "temp"))?.trim()?.toLongOrNull() ?: return@forEach
            val celsius = ThermalMath.celsiusFromRaw(raw) ?: return@forEach
            val key = type.lowercase()
            val existing = into[key]
            if (existing == null || celsius > existing.celsius) {
                into[key] = ThermalZone(
                    key = key,
                    label = ThermalMath.displayName(type),
                    celsius = celsius,
                    kind = ThermalMath.classify(type),
                )
            }
        }
    }

    private fun readHwmon(into: MutableMap<String, ThermalZone>) {
        val root = File("/sys/class/hwmon")
        val hosts = try {
            root.listFiles { file -> file.isDirectory && file.name.startsWith("hwmon") }
        } catch (_: Exception) {
            null
        } ?: return
        hosts.forEach { host ->
            val chip = readText(File(host, "name"))?.trim().orEmpty()
            val inputs = try {
                host.listFiles { file -> file.name.startsWith("temp") && file.name.endsWith("_input") }
            } catch (_: Exception) {
                null
            } ?: return@forEach
            inputs.forEach { input ->
                val raw = readText(input)?.trim()?.toLongOrNull() ?: return@forEach
                val celsius = ThermalMath.celsiusFromRaw(raw) ?: return@forEach
                val prefix = input.name.removeSuffix("_input")
                val label = readText(File(host, "${prefix}_label"))?.trim().orEmpty()
                val type = listOf(chip, label).filter { it.isNotBlank() }.joinToString("-").ifBlank { input.name }
                val key = "hwmon-$type".lowercase()
                into.putIfAbsent(
                    key,
                    ThermalZone(key, ThermalMath.displayName(type), celsius, ThermalMath.classify(type)),
                )
            }
        }
    }

    private fun readHardwareProps(into: MutableMap<String, ThermalZone>) {
        val manager = try {
            appContext.getSystemService(HardwarePropertiesManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return
        fun add(type: Int, key: String, label: String, kind: ThermalZone.Kind) {
            val values = try {
                manager.getDeviceTemperatures(type, HardwarePropertiesManager.TEMPERATURE_CURRENT)
            } catch (_: Exception) {
                null
            } ?: return
            val hottest = values.maxOrNull()?.takeIf { it in -20f..120f } ?: return
            into.putIfAbsent(key, ThermalZone(key, label, hottest, kind))
        }
        add(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, "hal-cpu", "CPU", ThermalZone.Kind.CPU)
        add(HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU, "hal-gpu", "GPU", ThermalZone.Kind.GPU)
        add(HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY, "hal-battery", "Battery", ThermalZone.Kind.BATTERY)
        add(HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN, "hal-skin", "Skin", ThermalZone.Kind.SKIN)
    }

    private fun readText(file: File): String? {
        return try {
            file.inputStream().bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun kindRank(kind: ThermalZone.Kind): Int = when (kind) {
        ThermalZone.Kind.SKIN -> 0
        ThermalZone.Kind.CPU -> 1
        ThermalZone.Kind.GPU -> 2
        ThermalZone.Kind.TPU -> 3
        ThermalZone.Kind.BATTERY -> 4
        ThermalZone.Kind.CHARGE -> 5
        ThermalZone.Kind.SOC -> 6
        ThermalZone.Kind.DISPLAY -> 7
        ThermalZone.Kind.OTHER -> 8
    }
}
