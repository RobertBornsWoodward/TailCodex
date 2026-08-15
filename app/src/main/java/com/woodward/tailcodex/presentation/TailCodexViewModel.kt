package com.woodward.tailcodex.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.ConnectionConfig
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.ReviewTarget
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.security.SecureConfigStore
import com.woodward.tailcodex.security.DraftStore
import com.woodward.tailcodex.security.ThreadPinStore
import com.woodward.tailcodex.infrastructure.TailCodexRuntimeFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.ImageAttachment
import com.woodward.tailcodex.domain.HostProfile
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class TailCodexViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = SecureConfigStore(application)
    private val draftStore = DraftStore(application)
    private val notifier = TailCodexNotifier(application)
    private val pinStore = ThreadPinStore(application)
    private val initialConfig = configStore.load()
    @Volatile private var currentHostId = initialConfig.hostId
    private val appCoordinator = TailCodexRuntimeFactory.create(
        context = application,
        initialConfig = initialConfig,
        hostIdentity = { currentHostId },
        isPinned = { pinStore.isPinned(currentHostId, it) },
        scope = viewModelScope,
    )
    private val coordinator get() = appCoordinator.codexSession

    val state: StateFlow<SessionState> = coordinator.state
    val hostState = appCoordinator.hostControl.state
    val appState = appCoordinator.state
    val hostControlEnabled: Boolean = appCoordinator.hostControlEnabled

    init {
        viewModelScope.launch {
            var previousRequestKeys = emptySet<String>()
            var previousTurn: TurnState = TurnState.Idle
            var previousProfileConnection: ConnectionState? = null
            var previousProfileThread: String? = null
            state.collect { value ->
                value.serverRequests.firstOrNull { "${value.hostId}:${it.requestId}" !in previousRequestKeys }?.let {
                    val title = when (it) {
                        is ServerRequest.UserInput -> "Codex 需要你的回答"
                        is ServerRequest.McpElicitation -> "MCP 需要确认"
                        else -> "Codex 需要授权"
                    }
                    notifier.showRequest(value.hostId, it, title, value.currentThread?.title ?: it.method)
                }
                if (value.turn != previousTurn) when (val turn = value.turn) {
                    is TurnState.Completed -> notifier.showTask("${value.hostId}:${turn.turnId}".hashCode(), "Codex 已完成", value.currentThread?.title.orEmpty())
                    is TurnState.Failed -> notifier.showTask("${value.hostId}:${turn.turnId}".hashCode(), "Codex 执行失败", turn.message.orEmpty())
                    else -> Unit
                }
                previousRequestKeys = value.serverRequests.map { "${value.hostId}:${it.requestId}" }.toSet()
                previousTurn = value.turn
                val threadId = value.currentThread?.id
                if (value.connection != previousProfileConnection || threadId != previousProfileThread) {
                    configStore.updateProfileRuntime(value.hostId, value.connection, threadId)
                    previousProfileConnection = value.connection
                    previousProfileThread = threadId
                }
            }
        }
    }

    fun connect(
        endpoint: String,
        token: String,
        defaultCwd: String,
        hostName: String = state.value.config.hostName,
        hostAgentEndpoint: String = state.value.config.hostAgentEndpoint,
        pairingCode: String = "",
    ) {
        val normalizedEndpoint = endpoint.trim()
        val normalizedHostAgentEndpoint = hostAgentEndpoint.trim().trimEnd('/')
        val cwd = defaultCwd.trim()
        when {
            !normalizedEndpoint.startsWith("wss://") -> return showValidation("端点必须使用 wss://")
            token.isBlank() -> return showValidation("请输入访问令牌")
            !cwd.startsWith("/") -> return showValidation("工作目录必须是主机上的绝对路径")
            normalizedHostAgentEndpoint.isNotBlank() && !normalizedHostAgentEndpoint.startsWith("https://") ->
                return showValidation("Host Agent 端点必须使用 https://")
        }
        val normalizedName = hostName.trim().ifBlank { "主机" }
        val profiles = configStore.loadProfiles()
        val currentConfig = state.value.config
        currentHostId = when {
            currentConfig.endpoint.trimEnd('/') == normalizedEndpoint.trimEnd('/') -> currentConfig.hostId
            else -> profiles.firstOrNull {
                it.endpoint.trimEnd('/') == normalizedEndpoint.trimEnd('/') &&
                    it.hostAgentEndpoint.trimEnd('/') == normalizedHostAgentEndpoint
            }?.id ?: profiles.firstOrNull {
                it.endpoint.trimEnd('/') == normalizedEndpoint.trimEnd('/')
            }?.id ?: stableHostProfileId(normalizedEndpoint)
        }
        val savedHostAgentCredential = profiles
            .firstOrNull {
                it.id == currentHostId && it.hostAgentEndpoint.trimEnd('/') == normalizedHostAgentEndpoint
            }
            ?.hostAgentCredential
            .orEmpty()
        val activeHostAgentCredential = state.value.config
            .takeIf {
                it.hostId == currentHostId && it.hostAgentEndpoint.trimEnd('/') == normalizedHostAgentEndpoint
            }
            ?.hostAgentCredential
            .orEmpty()
        val config = ConnectionConfig(
            normalizedEndpoint,
            token.trim(),
            cwd,
            hostId = currentHostId,
            hostName = normalizedName,
            hostAgentEndpoint = normalizedHostAgentEndpoint,
            hostAgentCredential = if (pairingCode.isNotBlank()) {
                ""
            } else {
                activeHostAgentCredential.ifBlank { savedHostAgentCredential }
            },
        )
        val lastThreadId = configStore.loadProfiles().firstOrNull { it.id == currentHostId }?.lastThreadId
        configStore.save(config)
        appCoordinator.connect(config, pairingCode, configStore.deviceId(), lastThreadId) { pairedConfig ->
            configStore.save(pairedConfig)
        }
    }

    fun hostProfiles(): List<HostProfile> = configStore.loadProfiles()

    fun connectProfile(profile: HostProfile) {
        appCoordinator.disconnect()
        currentHostId = profile.id
        val config = ConnectionConfig(
            endpoint = profile.endpoint,
            token = profile.credential,
            defaultCwd = profile.defaultCwd,
            hostId = profile.id,
            hostName = profile.name,
            hostAgentEndpoint = profile.hostAgentEndpoint,
            hostAgentCredential = profile.hostAgentCredential,
        )
        configStore.save(config)
        appCoordinator.connect(config, "", configStore.deviceId(), profile.lastThreadId, configStore::save)
    }

    fun reconnect() = appCoordinator.reconnect(state.value.config)
    fun restartCodex() = appCoordinator.restartCodex()

    fun handleNotificationIntent(intent: Intent?) {
        val command = notifier.consumeCommand(intent) ?: return
        val current = state.value
        if (command.hostId != current.hostId) {
            coordinator.onFailure("通知来自其他主机，请切换主机后查看")
            return
        }
        val request = current.serverRequests.firstOrNull { it.requestId.toString() == command.requestId }
        if (request == null) {
            coordinator.onFailure("该请求已处理或已过期")
            return
        }
        if (command.action == TailCodexNotifier.ACTION_OPEN_REQUEST) return
        if (current.connection !is ConnectionState.Ready || current.stale) {
            coordinator.onFailure("连接未完成对账，未执行通知操作")
            return
        }
        val approval = request as? ServerRequest.CommandApproval
        val decision = when (command.action) {
            TailCodexNotifier.ACTION_APPROVE -> when {
                approval == null -> ApprovalDecision.ACCEPT
                ApprovalDecision.ACCEPT in approval.availableDecisions -> ApprovalDecision.ACCEPT
                ApprovalDecision.ACCEPT_FOR_SESSION in approval.availableDecisions -> ApprovalDecision.ACCEPT_FOR_SESSION
                else -> null
            }
            TailCodexNotifier.ACTION_REJECT -> when {
                approval == null -> ApprovalDecision.DECLINE
                ApprovalDecision.DECLINE in approval.availableDecisions -> ApprovalDecision.DECLINE
                ApprovalDecision.CANCEL in approval.availableDecisions -> ApprovalDecision.CANCEL
                else -> null
            }
            else -> null
        }
        if (decision == null) coordinator.onFailure("服务端未提供该审批选项")
        else coordinator.resolveApproval(request, decision)
    }

    fun disconnect(forget: Boolean = false) {
        appCoordinator.disconnect()
        if (forget) configStore.clear()
    }

    fun updateSearch(value: String) = coordinator.updateSearch(value)
    fun loadThreads() = coordinator.loadThreads()
    fun loadMoreThreads(cursor: String) = coordinator.loadThreads(cursor)
    fun openThread(thread: TailcodexThread) {
        val config = state.value.config
        configStore.saveProfile(
            HostProfile(
                id = config.hostId,
                name = config.hostName,
                endpoint = config.endpoint,
                credential = config.token,
                defaultCwd = config.defaultCwd,
                lastThreadId = thread.id,
                connectionState = state.value.connection,
                hostAgentEndpoint = config.hostAgentEndpoint,
                hostAgentCredential = config.hostAgentCredential,
            ),
        )
        coordinator.openThread(thread)
    }
    fun startThread() = coordinator.startThread()
    fun closeThread() = coordinator.closeThread()
    fun sendMessage(
        text: String,
        imageUris: List<Uri> = emptyList(),
        cameraImage: Bitmap? = null,
        fileUris: List<Uri> = emptyList(),
    ) {
        if (imageUris.isEmpty() && cameraImage == null && fileUris.isEmpty()) {
            coordinator.send(text)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val draftThreadId = state.value.currentThread?.id
            runCatching {
                var enrichedText = text
                val images = mutableListOf<ImageAttachment>()
                imageUris.forEachIndexed { index, uri ->
                        val resolver = getApplication<Application>().contentResolver
                        val mime = resolver.getType(uri) ?: "image/jpeg"
                        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_IMAGE_BYTES) }
                            ?: error("无法读取图片")
                        require(bytes.size <= MAX_IMAGE_BYTES) { "图片超过 10 MB" }
                    images += ImageAttachment("image-${index + 1}", dataUrl(mime, bytes))
                }
                cameraImage?.let { bitmap ->
                        val bytes = ByteArrayOutputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                            output.toByteArray()
                        }
                    images += ImageAttachment("camera.jpg", dataUrl("image/jpeg", bytes))
                }
                fileUris.forEach { uri ->
                    val resolver = getApplication<Application>().contentResolver
                    val mime = resolver.getType(uri).orEmpty()
                    val name = resolver.displayName(uri)
                    val limit = if (mime.startsWith("image/")) MAX_IMAGE_BYTES else MAX_TEXT_FILE_BYTES
                    val bytes = resolver.openInputStream(uri)?.use { readLimited(it, limit) }
                        ?: error("无法读取附件 $name")
                    if (mime.startsWith("image/")) {
                        images += ImageAttachment(name, dataUrl(mime, bytes))
                    } else {
                        require(bytes.none { it == 0.toByte() }) { "附件 $name 不是可读文本文件" }
                        val fileText = bytes.toString(Charsets.UTF_8).replace("```", "``\u200B`")
                        val language = name.substringAfterLast('.', "text")
                        enrichedText += "\n\n附件 `$name`：\n```$language\n$fileText\n```"
                    }
                }
                enrichedText to images.toList()
            }.onSuccess { (preparedText, images) -> coordinator.send(preparedText, images) }.onFailure {
                if (draftThreadId != null && text.isNotBlank()) draftStore.save(currentHostId, draftThreadId, text)
                coordinator.onFailure(it.message ?: "图片处理失败")
            }
        }
    }
    fun interrupt() = coordinator.interruptTurn()
    fun resolveApproval(request: ServerRequest, decision: ApprovalDecision) =
        coordinator.resolveApproval(request, decision)

    fun answerUserInput(request: ServerRequest.UserInput, answers: Map<String, List<String>>) =
        coordinator.answerUserInput(request, answers)

    fun answerMcp(request: ServerRequest.McpElicitation, action: String, values: Map<String, String>? = null) =
        coordinator.answerMcp(request, action, values)

    fun forkThread() = coordinator.forkThread()
    fun archiveThread() = coordinator.archiveThread()
    fun pinThread(pinned: Boolean) {
        val threadId = state.value.currentThread?.id ?: return
        pinStore.setPinned(currentHostId, threadId, pinned)
        coordinator.pinThreadLocal(pinned)
    }
    fun startReview(target: ReviewTarget = ReviewTarget.UncommittedChanges) = coordinator.startReview(target)
    fun clearNotice() = coordinator.clearNotice()
    fun loadDraft(threadId: String): String = draftStore.load(currentHostId, threadId)
    fun saveDraft(threadId: String, value: String) = draftStore.save(currentHostId, threadId, value)

    private fun showValidation(message: String) {
        // Validation remains domain-facing by flowing through the same reducer/listener route.
        coordinator.onFailure(message)
    }

    override fun onCleared() {
        appCoordinator.disconnect()
        super.onCleared()
    }

    private fun dataUrl(mime: String, bytes: ByteArray): String =
        "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"

    private fun readLimited(input: InputStream, maximumBytes: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximumBytes) { "附件超过 ${maximumBytes / (1024 * 1024)} MB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun android.content.ContentResolver.displayName(uri: Uri): String {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).orEmpty().ifBlank { "attachment" }
        }
        return uri.lastPathSegment ?: "attachment"
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val MAX_TEXT_FILE_BYTES = 1 * 1024 * 1024
    }
}

internal fun stableHostProfileId(codexEndpoint: String): String {
    val canonical = codexEndpoint.trim().trimEnd('/')
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    val shortHex = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "host-$shortHex"
}
