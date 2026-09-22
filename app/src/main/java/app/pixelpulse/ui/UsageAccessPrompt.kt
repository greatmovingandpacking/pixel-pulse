package app.pixelpulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pixelpulse.monitor.UsageAccess

@Composable
fun UsageAccessPrompt(
    title: String,
    extra: String,
) {
    val context = LocalContext.current
    DetailCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Android will not show a permission popup for this. Because Pulse was sideloaded (Chrome / Files), Pixel locks Usage access behind Restricted settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = extra,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "1. Open Usage access. If Pixel says \u201cRestricted setting\u201d, that is expected.\n" +
                "2. Open Pulse app info \u2192 tap the \u22ee menu \u2192 Allow restricted settings.\n" +
                "3. Go back to Usage access and turn Pulse on. Then return here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { UsageAccess.openUsageAccessList(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Usage access")
            }
            OutlinedButton(
                onClick = { UsageAccess.openAppInfo(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow restricted settings")
            }
        }
    }
}
