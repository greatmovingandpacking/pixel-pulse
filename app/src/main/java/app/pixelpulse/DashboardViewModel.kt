package app.pixelpulse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pixelpulse.monitor.ResourceCollector
import app.pixelpulse.monitor.ResourceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val collector = ResourceCollector(application.applicationContext)
    private val _snapshot = MutableStateFlow<ResourceSnapshot?>(null)
    val snapshot: StateFlow<ResourceSnapshot?> = _snapshot.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _snapshot.value = withContext(Dispatchers.IO) { collector.sample() }
                delay(1_000)
            }
        }
    }
}
