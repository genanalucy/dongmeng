package com.verba.interpretation.export

import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Deliberately memory-only: recreation/process death cancels a pending picker safely. */
internal class DocumentExportController(
    private val scope: CoroutineScope = DocumentExportWriteScope.scope,
) {
    internal data class Request(
        val snapshot: ExportSnapshot,
        val format: ExportFormat,
        val userId: String,
        val generation: Long,
    )
    internal data class State(
        val busy: Boolean = false,
        val message: String? = null,
        val canRetry: Boolean = false,
    )

    private val lock = Any()
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var owner: String? = null
    private var generation = 0L
    private var pending: Request? = null
    private var retry: Request? = null
    private var writing = false

    fun updateOwner(userId: String?) = synchronized(lock) {
        if (owner != userId) {
            owner = userId
            invalidateLocked()
        }
    }

    /** Call before logout, including logout followed by login as the same user. */
    fun invalidateAccount() = synchronized(lock) {
        owner = null
        invalidateLocked()
    }

    private fun invalidateLocked() {
        generation++
        // Keep the picker slot occupied until its old result arrives; never consume it
        // as the result of a new request from another account.
        retry = null
        mutableState.value = State(busy = pending != null || writing)
    }

    fun prepare(snapshot: ExportSnapshot, format: ExportFormat): Request? = synchronized(lock) {
        if (mutableState.value.busy) return null
        val userId = owner
        if (userId.isNullOrBlank()) {
            mutableState.value = State(message = "请先登录再导出。")
            return null
        }
        if (snapshot.entries.isEmpty()) {
            mutableState.value = State(message = "暂无可导出的内容，不会创建文件。")
            return null
        }
        Request(snapshot, format, userId, generation).also {
            pending = it
            retry = null
            mutableState.value = State(busy = true)
        }
    }

    fun retryPicker(): Request? = synchronized(lock) {
        if (mutableState.value.busy) return null
        val request = retry?.takeIf(::validLocked) ?: return null
        pending = request
        retry = null
        mutableState.value = State(busy = true)
        request
    }

    fun canLaunch(request: Request): Boolean = synchronized(lock) {
        pending === request && validLocked(request)
    }

    fun pickerLaunchFailed() = synchronized(lock) {
        val request = pending ?: return
        pending = null
        failedLocked(request)
    }

    /** Null is normal picker cancellation; no stream is opened and no error shown. */
    fun documentResult(openStream: (() -> OutputStream?)?) {
        val request = synchronized(lock) {
            val value = pending ?: return
            pending = null
            if (!validLocked(value) || openStream == null) {
                mutableState.value = State()
                return
            }
            writing = true
            value
        }
        scope.launch {
            var success = false
            try {
                if (!synchronized(lock) { validLocked(request) }) return@launch
                val text = request.snapshot.render(request.format)
                if (!synchronized(lock) { validLocked(request) }) return@launch
                val stream = openStream?.invoke() ?: error("document_stream_unavailable")
                stream.use { output ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    var offset = 0
                    while (offset < bytes.size) {
                        synchronized(lock) {
                            check(validLocked(request)) { "export_owner_changed" }
                            val length = minOf(4096, bytes.size - offset)
                            // Serialize each bounded write with revocation. Once
                            // invalidateAccount returns no further bytes may be sent.
                            output.write(bytes, offset, length)
                            offset += length
                        }
                    }
                }
                // close() must succeed before publishing success.
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not disclose provider messages, paths, or conversation text.
            } finally {
                synchronized(lock) {
                    writing = false
                    if (!validLocked(request)) mutableState.value = State()
                    else if (success) mutableState.value = State(message = "导出成功。文件为明文，请妥善保管。")
                    else failedLocked(request)
                }
            }
        }
    }

    private fun validLocked(request: Request): Boolean =
        owner != null && owner == request.userId && generation == request.generation

    private fun failedLocked(request: Request) {
        if (validLocked(request)) {
            retry = request
            mutableState.value = State(
                message = "导出失败。可重试原快照并重新选择文件；目标位置可能留有不完整文件，请检查或删除。",
                canRetry = true,
            )
        } else mutableState.value = State()
    }

    fun dismissMessage() = synchronized(lock) {
        retry = null
        mutableState.value = mutableState.value.copy(message = null, canRetry = false)
    }
}

private object DocumentExportWriteScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
