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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pixelpulse.monitor.AppDirectory
import app.pixelpulse.monitor.Formatters
import app.pixelpulse.monitor.MemoryBreakdownCollector
import app.pixelpulse.monitor.MemoryDetail
import app.pixelpulse.monitor.ProcessMemoryRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDetailScreen(
    detail: MemoryDetail,
    apps: AppDirectory,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var filter by remember { mutableStateOf<ProcessMemoryRow.Kind?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Memory", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${Formatters.bytes(detail.usedBytes)} used \u00b7 ${Formatters.bytes(detail.availableBytes)} available",
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
            BreakdownCard(detail)
            if (!detail.hasUsageAccess) {
                UsageAccessPrompt(
                    title = "List recently active apps",
                    extra = "Usage access lets Pulse see which apps were recently active. Android still hides other apps\u2019 exact RAM sizes.",
                )
            }
            ProcessCard(detail, apps, filter, onFilter = { filter = it })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BreakdownCard(detail: MemoryDetail) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
        MaterialTheme.colorScheme.outline,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
    )
    val total = detail.totalBytes.coerceAtLeast(1)
    val colored = detail.slices.mapIndexed { index, slice ->
        palette[index % palette.size] to (slice.bytes.toFloat() / total)
    }
    DetailCard {
        Text("What\u2019s using RAM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "${Formatters.bytes(detail.totalBytes)} total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        StackedMemoryBar(colored)
        Spacer(Modifier.height(12.dp))
        detail.slices.forEachIndexed { index, slice ->
            SliceRow(palette[index % palette.size], slice.label, slice.bytes, slice.detail, total)
            Spacer(Modifier.height(8.dp))
        }
        if (detail.swapTotalBytes > 0) {
            Text(
                "Swap ${Formatters.bytes(detail.swapUsedBytes)} / ${Formatters.bytes(detail.swapTotalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(detail.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SliceRow(color: Color, label: String, bytes: Long, detail: String, total: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp, 10.dp)) {
            drawCircle(color)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Formatters.bytes(bytes), fontWeight = FontWeight.SemiBold)
            Text(
                Formatters.percent(bytes * 100f / total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessCard(
    detail: MemoryDetail,
    directory: AppDirectory,
    filter: ProcessMemoryRow.Kind?,
    onFilter: (ProcessMemoryRow.Kind?) -> Unit,
) {
    val rows = detail.processes.filter { filter == null || it.kind == filter }
    DetailCard {
        Text("Apps & services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("All") })
            FilterChip(selected = filter == ProcessMemoryRow.Kind.APP, onClick = { onFilter(ProcessMemoryRow.Kind.APP) }, label = { Text("Apps") })
            FilterChip(selected = filter == ProcessMemoryRow.Kind.SERVICE, onClick = { onFilter(ProcessMemoryRow.Kind.SERVICE) }, label = { Text("Services") })
            FilterChip(selected = filter == ProcessMemoryRow.Kind.CACHED, onClick = { onFilter(ProcessMemoryRow.Kind.CACHED) }, label = { Text("Cached") })
        }
        Spacer(Modifier.height(12.dp))
        if (rows.isEmpty()) {
            Text("Nothing in this filter yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            rows.take(40).forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    AppGlyph(directory.icon(row.packageName), row.label)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${MemoryBreakdownCollector.kindLabel(row.kind)} \u00b7 ${row.detail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        row.pssBytes?.let { Formatters.bytes(it) } ?: "\u2014",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
