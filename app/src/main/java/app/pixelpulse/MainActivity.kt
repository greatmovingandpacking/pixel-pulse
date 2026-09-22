package app.pixelpulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pixelpulse.ui.CpuDetailScreen
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
                val cpuDetail by viewModel.cpuDetail.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf(MonitorScreen.Dashboard) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, viewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                            Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                            Lifecycle.Event.ON_RESUME -> viewModel.refreshPermissions()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    viewModel.setForeground(
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                    )
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        viewModel.setForeground(false)
                    }
                }
                LaunchedEffect(screen) {
                    viewModel.setVisibleScreen(screen)
                }

                when (screen) {
                    MonitorScreen.Dashboard -> DashboardScreen(
                        snapshot = snapshot,
                        onNetworkClick = { screen = MonitorScreen.Network },
                        onMemoryClick = { screen = MonitorScreen.Memory },
                        onCpuClick = { screen = MonitorScreen.Cpu },
                    )
                    MonitorScreen.Network -> NetworkDetailScreen(
                        snapshot = snapshot?.network,
                        detail = networkDetail,
                        apps = viewModel.apps,
                        onBack = { screen = MonitorScreen.Dashboard },
                    )
                    MonitorScreen.Memory -> MemoryDetailScreen(
                        detail = memoryDetail,
                        apps = viewModel.apps,
                        onBack = { screen = MonitorScreen.Dashboard },
                    )
                    MonitorScreen.Cpu -> CpuDetailScreen(
                        detail = cpuDetail,
                        apps = viewModel.apps,
                        onBack = { screen = MonitorScreen.Dashboard },
                    )
                }
            }
        }
    }
}
