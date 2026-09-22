package app.pixelpulse.monitor

object NetworkDiagnose {
    fun analyze(
        network: NetworkInfo,
        apps: List<AppNetworkUsage>,
        timeline: List<RatePoint>,
        hasUsageAccess: Boolean,
    ): List<NetworkFinding> {
        val findings = mutableListOf<NetworkFinding>()

        when {
            !network.connected && network.transportLabel == "Offline" ->
                findings += NetworkFinding(
                    NetworkFinding.Severity.BAD,
                    "No network",
                    "The radio is offline. Toggle Airplane mode or reconnect Wi‑Fi / mobile data.",
                )
            network.captivePortal ->
                findings += NetworkFinding(
                    NetworkFinding.Severity.WARN,
                    "Captive portal",
                    "This network needs a sign-in page. Open Chrome and try loading any website.",
                )
            network.partialConnectivity ->
                findings += NetworkFinding(
                    NetworkFinding.Severity.WARN,
                    "Partial connectivity",
                    "The link is up but only some destinations work. Try another network.",
                )
            network.connected && !network.validated ->
                findings += NetworkFinding(
                    NetworkFinding.Severity.WARN,
                    "No internet",
                    "Connected to ${network.transportLabel}, but Android has not validated internet access.",
                )
            network.connected && network.validated ->
                findings += NetworkFinding(
                    NetworkFinding.Severity.OK,
                    "Internet looks healthy",
                    "Android validated ${network.transportLabel}.",
                )
        }

        val rssi = network.wifiRssi
        if (rssi != null) {
            when {
                rssi <= -80 -> findings += NetworkFinding(
                    NetworkFinding.Severity.BAD,
                    "Very weak Wi‑Fi",
                    "RSSI ${rssi} dBm. Move closer to the AP or switch bands.",
                )
                rssi <= -70 -> findings += NetworkFinding(
                    NetworkFinding.Severity.WARN,
                    "Weak Wi‑Fi",
                    "RSSI ${rssi} dBm. Throughput will drop and latency will rise.",
                )
            }
        }

        if (network.cellularLevel != null && network.transportLabel.contains("Cellular") && network.cellularLevel <= 1) {
            findings += NetworkFinding(
                NetworkFinding.Severity.WARN,
                "Weak mobile signal",
                "Cellular signal is at ${network.cellularLevel}/4.",
            )
        }

        if (network.metered) {
            findings += NetworkFinding(
                NetworkFinding.Severity.INFO,
                "Metered connection",
                "Android treats this link as metered. Background data may be deferred.",
            )
        }
        if (network.roaming) {
            findings += NetworkFinding(
                NetworkFinding.Severity.INFO,
                "Roaming",
                "The device reports this network as roaming.",
            )
        }

        val hog = apps.maxByOrNull { it.totalBytes }
        val total = apps.sumOf { it.totalBytes }
        if (hog != null && total > 256 * 1024 && hog.totalBytes * 100 / total >= 55) {
            findings += NetworkFinding(
                NetworkFinding.Severity.INFO,
                "${hog.app.label} is the top talker",
                "${Formatters.bytes(hog.totalBytes)} of the last few minutes — ${hog.totalBytes * 100 / total}% of app traffic.",
            )
        }

        val recent = timeline.takeLast(15)
        val avgRate = if (recent.isNotEmpty()) recent.sumOf { it.rxBytesPerSec + it.txBytesPerSec } / recent.size else 0
        val linkBps = network.wifiLinkMbps?.let { it * 1_000_000L / 8 }
        if (linkBps != null && avgRate > 200_000 && avgRate < linkBps / 20 && (network.wifiRssi != null && network.wifiRssi <= -67)) {
            findings += NetworkFinding(
                NetworkFinding.Severity.WARN,
                "Slow versus link rate",
                "You are transferring well below the ${network.wifiLinkMbps} Mbps radio rate on a weak signal.",
            )
        }

        if (!hasUsageAccess) {
            findings += NetworkFinding(
                NetworkFinding.Severity.INFO,
                "Grant Usage access for per-app data",
                "Android only exposes which apps are using the network after you allow Usage access.",
            )
        }

        return findings
    }
}
