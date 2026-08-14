package com.woodward.tailcodex.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodward.tailcodex.security.SecureConfigStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

class TailCodexViewModel(application: Application) : AndroidViewModel(application),
    CodexWebSocketClient.Listener {
    private val configStore = SecureConfigStore(application)
    private val client = CodexWebSocketClient(this)
    private val _state = MutableStateFlow(TailCodexState(config = configStore.load()))
    val state: StateFlow<TailCodexState> = _state.asStateFlow()

    private var autoReconnect = false
    private var reconnectJob: Job? = null

    fun connect(endpoint: String, token: String, defaultCwd: String) {
        val normalizedEndpoint = endpoint.trim()
        when {
            !normalizedEndpoint.startsWith("wss://") -> {
                _state.update { it.copy(error = "端点必须使用 wss://") }
                return
            }
            token.isBlank() -> {
                _state.update { it.copy(error = "请输入访问令牌") }
                return
            }
            !defaultCwd.startsWith("/") -> {
                _state.update { it.copy(error = "工作目录必须是主机上的绝对路径") }
                return
            }
        }
        val config = ConnectionConfig(normalizedEndpoint, token.trim(), defaultCwd.trim())
        configStore.save(config)
        autoReconnect = true
        reconnectJob?.cancel()
        _state.update {
            it.copy(config = config, status = ConnectionStatus.CONNECTING, error = null, reconnectAttempt = 0)
        }
        client.connect(config)
    }

    fun reconnect() {
        val config = _state.value.config
        if (config.token.isBlank()) return
        connect(config.endpoint, config.token, config.defaultCwd)
    }

    fun disconnect(forget: Boolean = false) {
        autoReconnect = false
        reconnectJob?.cancel()
        client.close()
        if (forget) configStore.clear()
        _state.update {
            TailCodexState(config = if (forget) ConnectionConfig() else it.config)
        }
    }

    fun updateSearch(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun loadThreads() {
        if (_state.value.status != ConnectionStatus.CONNECTED) return
        _state.update { it.copy(loadingThreads = true, error = null) }
        client.listThreads(_state.value.search) { result ->
            result.onSuccess { threads ->
                _state.update { it.copy(threads = threads, loadingThreads = false) }
            }.onFailure(::showError)
        }
    }

    fun openThread(thread: ThreadSummary) {
        _state.update { it.copy(activeThread = thread, messages = emptyList(), error = null) }
        client.resumeThread(thread.id) { result ->
            result.onSuccess { (summary, messages) ->
                _state.update { it.copy(activeThread = summary, messages = messages) }
            }.onFailure(::showError)
        }
    }

    fun startThread() {
        client.startThread(_state.value.config.defaultCwd) { result ->
            result.onSuccess { (summary, messages) ->
                _state.update { it.copy(activeThread = summary, messages = messages) }
            }.onFailure(::showError)
        }
    }

    fun closeThread() {
        _state.update { it.copy(activeThread = null, messages = emptyList(), activeTurnId = null, approval = null) }
        loadThreads()
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val current = _state.value
        val thread = current.activeThread ?: return
        if (trimmed.isBlank()) return
        val localEntry = ChatEntry("local-${UUID.randomUUID()}", MessageRole.USER, trimmed)
        _state.update { it.copy(messages = it.messages + localEntry, error = null) }
        val callback: (Result<JSONObject>) -> Unit = { result -> result.onFailure(::showError) }
        if (current.activeTurnId == null) {
            client.startTurn(thread.id, trimmed, callback)
        } else {
            client.steerTurn(thread.id, current.activeTurnId, trimmed, callback)
        }
    }

    fun interrupt() {
        val current = _state.value
        val threadId = current.activeThread?.id ?: return
        val turnId = current.activeTurnId ?: return
        client.interruptTurn(threadId, turnId) { result -> result.onFailure(::showError) }
    }

    fun resolveApproval(decision: String) {
        val approval = _state.value.approval ?: return
        client.resolveApproval(approval, decision)
        if (decision == "cancel") {
            approval.threadId?.let { threadId ->
                approval.turnId?.let { turnId -> client.interruptTurn(threadId, turnId) }
            }
        }
        _state.update { it.copy(approval = null) }
    }

    override fun onConnected() {
        reconnectJob?.cancel()
        _state.update { it.copy(status = ConnectionStatus.CONNECTED, error = null, reconnectAttempt = 0) }
        loadThreads()
    }

    override fun onDisconnected(reason: String) {
        _state.update { it.copy(status = ConnectionStatus.RECONNECTING, error = reason) }
        scheduleReconnect()
    }

    override fun onNotification(method: String, params: JSONObject) {
        when (method) {
            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId").ifBlank { "streaming-agent" }
                appendDelta(itemId, params.optString("delta"))
            }
            "item/started", "item/completed" -> {
                params.optJSONObject("item")?.let(CodexProtocol::parseItem)?.let(::upsertMessage)
            }
            "turn/started" -> {
                val turnId = params.optJSONObject("turn")?.optString("id")
                _state.update { it.copy(activeTurnId = turnId?.takeIf(String::isNotBlank)) }
            }
            "turn/completed" -> _state.update { it.copy(activeTurnId = null) }
            "error" -> showError(IllegalStateException(params.optString("message", "Codex error")))
        }
    }

    override fun onApprovalRequested(request: ApprovalRequest) {
        _state.update { it.copy(approval = request) }
    }

    override fun onProtocolError(message: String) {
        showError(IllegalStateException(message))
    }

    private fun appendDelta(itemId: String, delta: String) {
        if (delta.isEmpty()) return
        _state.update { current ->
            val existingIndex = current.messages.indexOfFirst { it.id == itemId }
            val messages = current.messages.toMutableList()
            if (existingIndex >= 0) {
                val old = messages[existingIndex]
                messages[existingIndex] = old.copy(text = old.text + delta)
            } else {
                messages += ChatEntry(itemId, MessageRole.ASSISTANT, delta)
            }
            current.copy(messages = messages)
        }
    }

    private fun upsertMessage(entry: ChatEntry) {
        _state.update { current ->
            val messages = current.messages.toMutableList()
            val index = messages.indexOfFirst { it.id == entry.id }
            val optimisticIndex = if (entry.role == MessageRole.USER) {
                messages.indexOfLast {
                    it.id.startsWith("local-") && it.role == MessageRole.USER && it.text == entry.text
                }
            } else {
                -1
            }
            when {
                index >= 0 -> messages[index] = entry
                optimisticIndex >= 0 -> messages[optimisticIndex] = entry
                else -> messages += entry
            }
            current.copy(messages = messages)
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            val attempt = _state.value.reconnectAttempt + 1
            _state.update { it.copy(reconnectAttempt = attempt) }
            delay(min(30_000L, 1_000L shl min(attempt - 1, 5)))
            if (autoReconnect) {
                // Release the one-shot job before starting the asynchronous connection so a
                // subsequent failure can schedule the next backoff attempt.
                reconnectJob = null
                client.connect(_state.value.config)
            }
        }
    }

    private fun showError(error: Throwable) {
        _state.update { it.copy(error = error.message ?: "未知错误", loadingThreads = false) }
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}
