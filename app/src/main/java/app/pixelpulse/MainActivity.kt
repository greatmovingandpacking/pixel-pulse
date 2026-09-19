package app.pixelpulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pixelpulse.ui.DashboardScreen
import app.pixelpulse.ui.MemoryDetailScreen
import app.pixelpulse.ui.NetworkDetailScreen
import app.pixelpulse.ui.theme.PixelPulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PixelPulseTheme {
                val viewModel: DashboardViewModel = viewModel()
                val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
                val networkDetail by viewModel.networkDetail.collectAsStateWithLifecycle()
                val memoryDetail by viewModel.memoryDetail.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf(Screen.Dashboard) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                when (screen) {
                    Screen.Dashboard -> DashboardScreen(
                        snapshot = snapshot,
                        onNetworkClick = { screen = Screen.Network },
                        onMemoryClick = { screen = Screen.Memory },
                    )
                    Screen.Network -> NetworkDetailScreen(
                        snapshot = snapshot?.network,
                        detail = networkDetail,
                        apps = viewModel.apps,
                        onBack = { screen = Screen.Dashboard },
                    )
                    Screen.Memory -> MemoryDetailScreen(
                        detail = memoryDetail,
                        apps = viewModel.apps,
                        onBack = { screen = Screen.Dashboard },
                    )
                }
            }
        }
    }
}

private enum class Screen { Dashboard, Network, Memory }
