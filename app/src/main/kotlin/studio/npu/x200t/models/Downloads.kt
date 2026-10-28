package studio.npu.x200t.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadState(
    val running: Boolean,
    val done: Long,
    val total: Long,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Runs model downloads in the app-wide scope (they continue while you use other screens). */
class Downloads(private val store: ModelStore, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state.asStateFlow()
    private val jobs = HashMap<String, Job>()

    fun isRunning(id: String) = jobs[id]?.isActive == true

    fun start(m: CatalogModel, onFinished: () -> Unit) {
        if (isRunning(m.id)) return
        val need = m.totalBytes - store.downloadedBytes(m)
        val free = store.freeBytes()
        if (free in 0 until need + 500_000_000L) {
            set(m.id, DownloadState(false, store.downloadedBytes(m), m.totalBytes,
                "Not enough free space: need ${gb(need)}, have ${gb(free)}"))
            return
        }
        log("Downloading ${m.name} (${gb(m.totalBytes)})")
        jobs[m.id] = scope.launch {
            set(m.id, DownloadState(true, store.downloadedBytes(m), m.totalBytes))
            try {
                store.download(m) { done, total -> set(m.id, DownloadState(true, done, total)) }
                set(m.id, DownloadState(false, m.totalBytes, m.totalBytes, finished = true))
                log("${m.name} downloaded")
                onFinished()
            } catch (e: CancellationException) {
                set(m.id, DownloadState(false, store.downloadedBytes(m), m.totalBytes, "Paused"))
            } catch (e: Throwable) {
                set(m.id, DownloadState(false, store.downloadedBytes(m), m.totalBytes, e.message ?: "Download failed"))
                log("Download of ${m.name} failed: ${e.message}")
            }
        }
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
    }

    fun clear(id: String) = _state.update { it - id }

    private fun set(id: String, s: DownloadState) = _state.update { it + (id to s) }

    companion object {
        fun gb(bytes: Long) = if (bytes < 0) "?" else String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
    }
}
