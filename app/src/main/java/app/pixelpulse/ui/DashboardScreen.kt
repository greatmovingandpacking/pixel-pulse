package app.pixelpulse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pixelpulse.BuildConfig
import app.pixelpulse.monitor.BatteryInfo
import app.pixelpulse.monitor.BatteryStatus
import app.pixelpulse.monitor.CpuInfo
import app.pixelpulse.monitor.Formatters
import app.pixelpulse.monitor.MemoryStats
import app.pixelpulse.monitor.NetworkInfo
import app.pixelpulse.monitor.ResourceCollector
import app.pixelpulse.monitor.ResourceSnapshot
import app.pixelpulse.monitor.StorageInfo
import app.pixelpulse.monitor.ThermalInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    snapshot: ResourceSnapshot?,
    onNetworkClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pixel Pulse", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = snapshot?.let {
                                "${it.device.model} · Android ${it.device.androidRelease} · up ${Formatters.uptime(it.device.uptimeMs)}"
                            } ?: "Reading sensors…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        if (snapshot == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BatteryCard(snapshot.battery)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { CpuCard(snapshot.cpu) }
                Box(modifier = Modifier.weight(1f)) { MemoryCard(snapshot.memory, onMemoryClick) }
            }
            NetworkCard(snapshot.network, onNetworkClick)
            StorageCard(snapshot.storage)
            FooterRow(snapshot.thermal)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun BatteryCard(battery: BatteryInfo) {
    val progress by animateFloatAsState(
        targetValue = battery.percent / 100f,
        animationSpec = tween(400),
        label = "battery",
    )
    val ringColor = when {
        battery.status == BatteryStatus.CHARGING -> MaterialTheme.colorScheme.primary
        battery.percent <= 15 -> MaterialTheme.colorScheme.error
        battery.temperatureC != null && battery.temperatureC >= 42f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = ringColor,
                )
                Spacer(Modifier.width(8.dp))
                Text("Battery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    text = battery.chargeSpeedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
                    val track = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(Modifier.size(128.dp)) {
                        drawArc(
                            color = track,
                            startAngle = -210f,
                            sweepAngle = 240f,
                            useCenter = false,
                            style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = ringColor,
                            startAngle = -210f,
                            sweepAngle = 240f * progress,
                            useCenter = false,
                            style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = Formatters.percent(battery.percent),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = ResourceCollector.statusLabel(battery.status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    val headline = when {
                        battery.status == BatteryStatus.CHARGING && battery.powerW != null ->
                            Formatters.watts(battery.powerW)
                        battery.status == BatteryStatus.DISCHARGING && battery.powerW != null ->
                            "${Formatters.watts(battery.powerW)} draw"
                        else -> ResourceCollector.sourceLabel(battery.source)
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = ResourceCollector.sourceLabel(battery.source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    battery.remainingLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric("Voltage", Formatters.voltage(battery.voltageV))
                Metric("Current", Formatters.current(battery.currentMa))
                Metric("Temp", Formatters.temperature(battery.temperatureC))
                Metric("Health", battery.healthLabel)
            }
        }
    }
}

@Composable
private fun CpuCard(cpu: CpuInfo) {
    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = { Icon(Icons.Outlined.Speed, null) }, title = "CPU")
            Text(
                text = Formatters.percent(cpu.usagePercent),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { (cpu.usagePercent ?: 0f) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            CoreBars(cpu)
            val freq = when {
                cpu.minFreqMhz != null && cpu.maxFreqMhz != null && cpu.minFreqMhz != cpu.maxFreqMhz ->
                    "${Formatters.frequency(cpu.minFreqMhz)}–${Formatters.frequency(cpu.maxFreqMhz)}"
                else -> Formatters.frequency(cpu.maxFreqMhz ?: cpu.minFreqMhz)
            }
            Text(
                text = "${cpu.cores.size} cores · $freq",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoreBars(cpu: CpuInfo) {
    val barColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val cores = cpu.cores
        if (cores.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (cores.size - 1)) / cores.size).coerceAtLeast(3.dp.toPx())
        cores.forEachIndexed { index, core ->
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = track,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            val usage = (core.usagePercent ?: 0f) / 100f
            val h = size.height * usage
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

@Composable
private fun MemoryCard(memory: MemoryStats, onClick: () -> Unit) {
    DashboardCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                icon = { Icon(Icons.Outlined.Memory, null) },
                title = "Memory",
                trailingIcon = true,
            )
            Text(
                text = Formatters.percent(memory.usedPercent),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { memory.usedPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (memory.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${Formatters.bytes(memory.usedBytes)} / ${Formatters.bytes(memory.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (memory.lowMemory) "Low memory" else "${Formatters.bytes(memory.availBytes)} free · apps",
                style = MaterialTheme.typography.labelMedium,
                color = if (memory.lowMemory) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun NetworkCard(network: NetworkInfo, onClick: () -> Unit) {
    DashboardCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(
                icon = { Icon(Icons.Outlined.Wifi, null) },
                title = "Network",
                trailing = network.transportLabel,
                trailingIcon = true,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Download", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        Formatters.rateBytesPerSec(network.rxBytesPerSec),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Upload", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        Formatters.rateBytesPerSec(network.txBytesPerSec),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val extras = buildList {
                add("${Formatters.bytes(network.rxTotal)} down")
                add("${Formatters.bytes(network.txTotal)} up")
                network.wifiLinkMbps?.let { add("Link $it Mbps") }
                if (network.wifiLinkMbps == null) {
                    network.downlinkCapKbps?.let { add("Cap ${Formatters.bytes(it.toLong() * 125)}/s") }
                }
            }
            Text(
                text = extras.joinToString(" · ") + " · tap for apps & history",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageCard(storage: StorageInfo) {
    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = { Icon(Icons.Outlined.Storage, null) }, title = "Storage")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = Formatters.bytes(storage.usedBytes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "  / ${Formatters.bytes(storage.totalBytes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { storage.usedPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Text(
                text = "${Formatters.bytes(storage.freeBytes)} free · ${Formatters.percent(storage.usedPercent)} used",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FooterRow(thermal: ThermalInfo) {
    val hot = thermal.status >= 2
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.DeviceThermostat,
                contentDescription = null,
                tint = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Thermal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = thermal.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "v${BuildConfig.VERSION_NAME} · on-device only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CardHeader(
    icon: @Composable () -> Unit,
    title: String,
    trailing: String? = null,
    trailingIcon: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailingIcon) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}
