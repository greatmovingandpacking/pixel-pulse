package app.pixelpulse.monitor

object MemInfoParser {
    fun parseKb(text: String): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        text.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
                .removeSuffix("kB")
                .removeSuffix("KB")
                .trim()
                .toLongOrNull()
            if (value != null) out[key] = value
        }
        return out
    }

    fun slices(kb: Map<String, Long>): List<MemorySlice> {
        fun bytes(key: String) = (kb[key] ?: 0L) * 1024L
        val total = bytes("MemTotal")
        val apps = bytes("AnonPages").takeIf { it > 0 }
            ?: (bytes("Active(anon)") + bytes("Inactive(anon)"))
        val cache = bytes("Cached") + bytes("SReclaimable")
        val buffers = bytes("Buffers")
        val kernel = bytes("SUnreclaim") + bytes("KernelStack") + bytes("PageTables") + bytes("Percpu")
        val free = bytes("MemFree")
        val usedParts = apps + cache + buffers + kernel + free
        val other = (total - usedParts).coerceAtLeast(0)

        return listOf(
            MemorySlice("apps", "Apps & services", apps, "Anonymous memory held by processes"),
            MemorySlice("cache", "File cache", cache, "Reclaimable — Android frees this when apps need RAM"),
            MemorySlice("buffers", "Buffers", buffers, "Block-device buffers"),
            MemorySlice("kernel", "Kernel", kernel, "Slabs, page tables, stacks"),
            MemorySlice("free", "Free", free, "Completely unused pages"),
            MemorySlice("other", "Other", other, "Graphics, ION, CMA, and uncategorized"),
        ).filter { it.bytes > 0 || it.key == "apps" || it.key == "free" }
    }

    fun usedBytes(kb: Map<String, Long>): Long {
        val total = (kb["MemTotal"] ?: 0L) * 1024L
        val available = (kb["MemAvailable"] ?: kb["MemFree"] ?: 0L) * 1024L
        return (total - available).coerceAtLeast(0)
    }
}
