package app.pixelpulse.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.pixelpulse.monitor.AppDirectory
import app.pixelpulse.monitor.AppNetworkUsage
import app.pixelpulse.monitor.Formatters
import app.pixelpulse.monitor.TrafficMath
import app.pixelpulse.monitor.NetworkDetail
import app.pixelpulse.monitor.NetworkFinding
import app.pixelpulse.monitor.NetworkInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    snapshot: NetworkInfo?,
    detail: NetworkDetail,
    apps: AppDirectory,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Network", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = snapshot?.transportLabel ?: "Reading…",
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
            snapshot?.let { LiveRatesCard(it) }
            DiagnosisCard(detail.findings)
            HistoryCard(detail)
            if (!detail.hasUsageAccess) {
                UsageAccessPrompt(
                    title = "See which apps are using data",
                    extra = "Pulse never leaves the device. Usage access is only used to read per-app network history on this phone.",
                )
            }
            AppUsageCard(detail.apps, apps)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LiveRatesCard(network: NetworkInfo) {
    DetailCard {
        Text("Right now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Download", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Formatters.rateBytesPerSec(network.rxBytesPerSec), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Upload", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Formatters.rateBytesPerSec(network.txBytesPerSec), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        val bits = buildList {
            add(network.transportLabel)
            if (network.validated) add("validated")
            network.wifiStandardLabel?.let { add(it) }
            network.wifiRssi?.let { add("$it dBm") }
            network.wifiFrequencyMhz?.let { add("$it MHz") }
            network.wifiLinkMbps?.let { add("link $it Mbps") }
            network.cellularLevel?.let { add("signal $it/4") }
            if (network.metered) add("metered")
            if (network.roaming) add("roaming")
        }
        Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosisCard(findings: List<NetworkFinding>) {
    DetailCard {
        Text("Diagnosis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        findings.forEachIndexed { index, finding ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = when (finding.severity) {
                        NetworkFinding.Severity.OK -> Icons.Outlined.CheckCircle
                        NetworkFinding.Severity.INFO -> Icons.Outlined.Info
                        NetworkFinding.Severity.WARN -> Icons.Outlined.WarningAmber
                        NetworkFinding.Severity.BAD -> Icons.Outlined.ErrorOutline
                    },
                    contentDescription = null,
                    tint = when (finding.severity) {
                        NetworkFinding.Severity.OK -> MaterialTheme.colorScheme.primary
                        NetworkFinding.Severity.INFO -> MaterialTheme.colorScheme.secondary
                        NetworkFinding.Severity.WARN -> MaterialTheme.colorScheme.tertiary
                        NetworkFinding.Severity.BAD -> MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(finding.title, fontWeight = FontWeight.SemiBold)
                    Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(detail: NetworkDetail) {
    DetailCard {
        Text(TrafficMath.spanLabel(detail.timeline), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Green is download, teal is upload. Updates every second while Pulse is open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        RateTimeline(detail.timeline)
        val down = TrafficMath.bytesOver(detail.timeline) { it.rxBytesPerSec }
        val up = TrafficMath.bytesOver(detail.timeline) { it.txBytesPerSec }
        if (detail.timeline.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "≈ ${Formatters.bytes(down)} down · ${Formatters.bytes(up)} up over this session window",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppUsageCard(apps: List<AppNetworkUsage>, directory: AppDirectory) {
    DetailCard {
        Text("Apps using the network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Totals are the last 5 minutes. Rates are the change since the previous sample.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (apps.isEmpty()) {
            Text("No per-app samples yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            apps.take(24).forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                AppNetworkRow(row, directory.icon(row.app.packageName))
            }
        }
    }
}

@Composable
private fun AppNetworkRow(row: AppNetworkUsage, icon: Drawable?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppGlyph(icon, row.app.label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val share = buildList {
                add("${Formatters.bytes(row.totalBytes)} / 5 min")
                if (row.wifiBytes > 0) add("Wi‑Fi ${Formatters.bytes(row.wifiBytes)}")
                if (row.mobileBytes > 0) add("Cell ${Formatters.bytes(row.mobileBytes)}")
                if (row.foregroundBytes > 0 && row.backgroundBytes > 0) {
                    val fg = row.foregroundBytes * 100 / row.totalBytes.coerceAtLeast(1)
                    add("$fg% foreground")
                }
            }
            Text(share.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.history.size >= 2) {
                Spacer(Modifier.height(6.dp))
                RateTimeline(row.history, modifier = Modifier.height(36.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("↓ ${Formatters.rateBytesPerSec(row.rxBytesPerSec)}", style = MaterialTheme.typography.labelLarge)
            Text("↑ ${Formatters.rateBytesPerSec(row.txBytesPerSec)}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun PermissionCard(title: String, body: String, action: String, onClick: () -> Unit) {
    DetailCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
internal fun DetailCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
internal fun AppGlyph(icon: Drawable?, label: String) {
    val bitmap = remember(icon) { icon.toSafeImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun Drawable?.toSafeImageBitmap(size: Int = 96): ImageBitmap? {
    if (this == null) return null
    return try {
        val src = constantState?.newDrawable()?.mutate() ?: mutate()
        src.toBitmap(size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
