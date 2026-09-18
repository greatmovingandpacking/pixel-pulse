package app.pixelpulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pixelpulse.ui.DashboardScreen
import app.pixelpulse.ui.theme.PixelPulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PixelPulseTheme {
                val viewModel: DashboardViewModel = viewModel()
                val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
                DashboardScreen(snapshot = snapshot)
            }
        }
    }
}
