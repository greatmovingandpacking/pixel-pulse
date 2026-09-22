package app.pixelpulse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pixelpulse.monitor.AppCpuUsage
import app.pixelpulse.monitor.AppDirectory
import app.pixelpulse.monitor.CpuDetail
import app.pixelpulse.monitor.Formatters
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuDetailScreen(
    detail: CpuDetail,
    apps: AppDirectory,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val palette = cpuPalette()
    val series = detail.apps.filter { it.history.size >= 2 }.take(6)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CPU", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = detail.overallPercent?.let { "${it.roundToInt()}% · ${detail.sourceLabel}" }
                                ?: "Reading…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailCard {
                Text("Apps using the CPU", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Each color is one app. The line is that app’s share of all cores, updated while Pulse is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (series.isEmpty()) {
                    Text("Collecting the first samples…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AppCpuChart(series, palette)
                    Spacer(Modifier.height(12.dp))
                    series.forEachIndexed { index, row ->
                        LegendRow(palette[index % palette.size], row.app.label, row.percent)
                    }
                }
            }
            DetailCard {
                Text("Right now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                if (detail.apps.isEmpty()) {
                    Text("No per-app samples yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.apps.forEachIndexed { index, row ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        AppCpuRow(row, apps, palette[index % palette.size])
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(detail.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun cpuPalette(): List<Color> {
    return listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun LegendRow(color: Color, label: String, percent: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${percent.roundToInt()}%", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppCpuRow(row: AppCpuUsage, directory: AppDirectory, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppGlyph(directory.icon(row.app.packageName), row.app.label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val extra = buildList {
                add("${row.percent.roundToInt()}% of all cores")
                if (row.processCount > 1) add("${row.processCount} processes")
            }
            Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.history.size >= 2) {
                Spacer(Modifier.height(6.dp))
                CpuSparkline(row.history.map { it.percent }, color = color, modifier = Modifier.height(28.dp))
            }
        }
        Text("${row.percent.roundToInt()}%", fontWeight = FontWeight.SemiBold)
    }
}
