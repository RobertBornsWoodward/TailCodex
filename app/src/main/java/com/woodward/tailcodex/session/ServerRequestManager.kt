package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.repository.CodexRepository
import org.json.JSONObject
import java.util.logging.Logger

class ServerRequestManager(
    private val repository: CodexRepository,
    private val connectionGeneration: () -> Long,
    private val hostIdentity: () -> String = { "default" },
    private val connectionReady: () -> Boolean = { true },
    private val writableContext: (ServerRequest) -> Boolean = { true },
) {
    interface Listener {
        fun onRequestsChanged(requests: List<ServerRequest>)
        fun onUnsupportedRequest(request: ServerRequest)
        fun onFailure(message: String)
    }

    private data class Entry(val request: ServerRequest, val generation: Long, val hostId: String)
    private val requests = mutableListOf<Entry>()

    @Volatile
    private var listener: Listener? = null

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    @Synchronized
    fun handle(request: ServerRequest) {
        if (request is ServerRequest.Unknown || request is ServerRequest.DynamicToolCall) {
            logger.warning(
                "Rejecting unsupported server request method=${request.method} id=${request.requestId}",
            )
            listener?.onUnsupportedRequest(request)
            if (!repository.rejectUnsupported(request)) {
                listener?.onFailure("Unable to reject unsupported server request ${request.method}")
            }
            return
        }
        requests.removeAll { it.request.requestId.toString() == request.requestId.toString() }
        requests += Entry(request, connectionGeneration(), hostIdentity())
        publish()
    }

    @Synchronized
    fun resolveApproval(request: ServerRequest, decision: ApprovalDecision) {
        if (request !is ServerRequest.CommandApproval &&
            request !is ServerRequest.FileApproval &&
            request !is ServerRequest.PermissionsApproval
        ) {
            listener?.onFailure("Request is not an approval")
            return
        }
        if (request is ServerRequest.CommandApproval && decision !in request.availableDecisions) {
            listener?.onFailure("Approval decision is not offered by the server")
            return
        }
        if (!isCurrent(request)) return
        if (repository.respondApproval(request, decision)) remove(request) else listener?.onFailure("Approval response send failed")
    }

    @Synchronized
    fun answerUserInput(request: ServerRequest.UserInput, answers: Map<String, List<String>>) {
        val requiredIds = request.questions.map { it.id }.toSet()
        if (!answers.keys.containsAll(requiredIds)) {
            listener?.onFailure("Every user-input question requires an answer")
            return
        }
        if (!isCurrent(request)) return
        if (repository.respondUserInput(request, answers)) remove(request) else listener?.onFailure("User-input response send failed")
    }

    @Synchronized
    fun answerMcp(request: ServerRequest.McpElicitation, action: String, content: JSONObject?) {
        if (action !in setOf("accept", "decline", "cancel")) {
            listener?.onFailure("Invalid MCP elicitation action")
            return
        }
        if (action == "accept" && request.mode.endsWith("form") && content == null) {
            listener?.onFailure("Accepted MCP form requires content")
            return
        }
        if (!isCurrent(request)) return
        if (repository.respondMcp(request, action, content)) remove(request) else listener?.onFailure("MCP response send failed")
    }

    @Synchronized
    fun resolved(requestId: String) {
        requests.removeAll { it.request.requestId.toString() == requestId }
        publish()
    }

    @Synchronized
    fun turnCompleted(turnId: String?) {
        if (turnId == null) return
        requests.removeAll { it.request.turnId == turnId }
        publish()
    }

    @Synchronized
    fun clear() {
        requests.clear()
        publish()
    }

    @Synchronized
    fun current(): List<ServerRequest> = requests.map(Entry::request)

    private fun isCurrent(request: ServerRequest): Boolean {
        val entry = requests.firstOrNull { it.request.requestId.toString() == request.requestId.toString() }
        if (!connectionReady() || entry == null || entry.generation != connectionGeneration() ||
            entry.hostId != hostIdentity() || !writableContext(request)
        ) {
            listener?.onFailure("Server request is stale after disconnect; reopen or interrupt the turn")
            return false
        }
        return true
    }

    private fun remove(request: ServerRequest) {
        requests.removeAll { it.request.requestId.toString() == request.requestId.toString() }
        publish()
    }

    private fun publish() {
        listener?.onRequestsChanged(requests.map(Entry::request))
    }

    private companion object {
        val logger: Logger = Logger.getLogger(ServerRequestManager::class.java.name)
    }
}
