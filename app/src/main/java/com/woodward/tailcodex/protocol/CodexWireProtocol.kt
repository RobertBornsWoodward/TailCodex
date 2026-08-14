package com.woodward.tailcodex.protocol

import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.ConversationItem
import com.woodward.tailcodex.domain.MessageRole
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.UserInputOption
import com.woodward.tailcodex.domain.UserInputQuestion
import com.woodward.tailcodex.domain.ImageAttachment
import org.json.JSONArray
import org.json.JSONObject
import com.woodward.tailcodex.protocol.generated.WireMcpElicitationParams
import com.woodward.tailcodex.protocol.generated.WireThreadReadParams
import com.woodward.tailcodex.protocol.generated.WireToolRequestUserInputParams
import com.woodward.tailcodex.protocol.generated.WireCommandApprovalParams
import com.woodward.tailcodex.protocol.generated.WireFileApprovalParams
import com.woodward.tailcodex.protocol.generated.WirePermissionsApprovalParams

data class ThreadPage(
    val threads: List<TailcodexThread>,
    val nextCursor: String?,
)

object CodexWireProtocol {
    fun initializeParams(): JSONObject = JSONObject()
        .put(
            "clientInfo",
            JSONObject()
                .put("name", "tailcodex_android")
                .put("title", "TailCodex Android")
                .put("version", "0.2.0"),
        )

    fun threadListParams(search: String, cursor: String?, limit: Int = 50): JSONObject = JSONObject()
        .put("limit", limit)
        .put("sortKey", "updated_at")
        .put("sortDirection", "desc")
        .apply {
            if (search.isNotBlank()) put("searchTerm", search.trim())
            if (cursor != null) put("cursor", cursor)
        }

    fun threadReadParams(threadId: String): JSONObject = WireThreadReadParams(threadId).toJson()

    fun threadResumeParams(threadId: String): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("approvalPolicy", "on-request")
        .put("approvalsReviewer", "user")

    fun threadStartParams(cwd: String): JSONObject = JSONObject()
        .put("cwd", cwd)
        .put("serviceName", "tailcodex_android")
        .put("approvalPolicy", "on-request")
        .put("approvalsReviewer", "user")

    fun turnStartParams(
        threadId: String,
        text: String,
        images: List<ImageAttachment> = emptyList(),
    ): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("input", turnInput(text, images))

    fun turnSteerParams(
        threadId: String,
        expectedTurnId: String,
        text: String,
        images: List<ImageAttachment> = emptyList(),
    ): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("expectedTurnId", expectedTurnId)
        .put("input", turnInput(text, images))

    fun turnInterruptParams(threadId: String, turnId: String): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("turnId", turnId)

    fun threadForkParams(threadId: String): JSONObject = JSONObject().put("threadId", threadId)
    fun threadArchiveParams(threadId: String): JSONObject = JSONObject().put("threadId", threadId)

    fun reviewStartParams(threadId: String, target: JSONObject): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("delivery", "inline")
        .put("target", target)

    fun parseThreadPage(result: JSONObject): ThreadPage {
        val data = result.optJSONArray("data") ?: JSONArray()
        val threads = buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let(::parseThread)?.let(::add)
            }
        }
        return ThreadPage(threads, result.optNullableString("nextCursor"))
    }

    fun parseThreadSnapshot(result: JSONObject): ThreadSnapshot {
        val thread = result.getJSONObject("thread")
        val summary = requireNotNull(parseThread(thread)) { "Thread response has no id" }
        val items = mutableListOf<ConversationItem>()
        var turnState: TurnState = TurnState.Idle
        val turns = thread.optJSONArray("turns") ?: JSONArray()
        for (turnIndex in 0 until turns.length()) {
            val turn = turns.optJSONObject(turnIndex) ?: continue
            val turnId = turn.optString("id")
            turnState = parseTurnState(turnId, turn.opt("status"), turn.optJSONObject("error"))
            val turnItems = turn.optJSONArray("items") ?: continue
            for (itemIndex in 0 until turnItems.length()) {
                turnItems.optJSONObject(itemIndex)?.let(::parseItem)?.let(items::add)
            }
        }
        if (turns.length() == 0 && summary.status == "active") {
            turnState = TurnState.Running("unknown", TurnState.Phase.EXECUTING)
        }
        return ThreadSnapshot(summary, items, turnState)
    }

    fun parseThread(thread: JSONObject): TailcodexThread? {
        val id = thread.optString("id")
        if (id.isBlank()) return null
        val name = thread.optNullableString("name")
        val preview = thread.optString("preview")
        return TailcodexThread(
            id = id,
            title = name ?: preview.lineSequence().firstOrNull()?.take(72).orEmpty().ifBlank { "未命名会话" },
            preview = preview,
            cwd = thread.optString("cwd"),
            updatedAt = thread.optLong("updatedAt", thread.optLong("createdAt")),
            status = parseStatus(thread.opt("status")),
            pinned = thread.optBoolean("isPinned", false),
        )
    }

    fun parseItem(item: JSONObject): ConversationItem? {
        val id = item.optString("id").ifBlank { "event-${item.toString().hashCode()}" }
        return when (item.optString("type")) {
            "userMessage" -> ConversationItem.Message(id, MessageRole.USER, parseUserContent(item.optJSONArray("content")))
            "agentMessage" -> ConversationItem.Message(id, MessageRole.ASSISTANT, item.optString("text"))
            "commandExecution" -> ConversationItem.CommandExecution(
                id = id,
                command = item.optString("command").ifBlank { "命令执行" },
                cwd = item.optNullableString("cwd"),
                status = item.optString("status", "inProgress"),
                output = item.optString("aggregatedOutput"),
            )
            "fileChange" -> ConversationItem.FileChange(
                id = id,
                files = parseFileChanges(item.optJSONArray("changes")),
                status = item.optString("status", "inProgress"),
                unifiedDiff = item.optNullableString("diff"),
            )
            "mcpToolCall" -> ConversationItem.McpCall(
                id = id,
                server = item.optString("server"),
                tool = item.optString("tool"),
                status = item.optString("status", "inProgress"),
                output = item.opt("result")?.toString(),
            )
            "enteredReviewMode", "exitedReviewMode" -> ConversationItem.Review(
                id = id,
                title = item.optString("review").ifBlank { "代码审查" },
                status = item.optString("type"),
            )
            else -> null
        }
    }

    fun parseServerRequest(requestId: RpcId, method: String, params: JSONObject): ServerRequest = when (method) {
        "item/commandExecution/requestApproval" -> {
            WireCommandApprovalParams.fromJson(params).let { wire -> ServerRequest.CommandApproval(
                requestId = requestId,
                threadId = wire.threadId,
                turnId = wire.turnId,
                itemId = wire.itemId,
                command = wire.command,
                cwd = wire.cwd,
                reason = wire.reason,
                networkHost = wire.networkHost,
                networkProtocol = wire.networkProtocol,
                availableDecisions = parseDecisions(wire.availableDecisions),
            ) }
        }
        "item/fileChange/requestApproval" -> WireFileApprovalParams.fromJson(params).let { wire ->
            ServerRequest.FileApproval(requestId, wire.threadId, wire.turnId, wire.itemId, wire.reason, wire.grantRoot)
        }
        "item/permissions/requestApproval" -> WirePermissionsApprovalParams.fromJson(params).let { wire ->
            ServerRequest.PermissionsApproval(
                requestId, wire.threadId, wire.turnId, wire.itemId, wire.cwd, wire.reason, wire.permissions.toString(),
            )
        }
        "item/tool/requestUserInput" -> WireToolRequestUserInputParams.fromJson(params).let { wire ->
            ServerRequest.UserInput(
                requestId = requestId,
                threadId = wire.threadId,
                turnId = wire.turnId,
                itemId = wire.itemId,
                questions = wire.questions.map { question ->
                    UserInputQuestion(
                        question.id,
                        question.header,
                        question.question,
                        question.options.map { UserInputOption(it.label, it.description) },
                        question.isOther,
                        question.isSecret,
                    )
                },
                isBlocking = wire.isBlocking,
            )
        }
        "mcpServer/elicitation/request" -> WireMcpElicitationParams.fromJson(params).let { wire ->
            ServerRequest.McpElicitation(
                requestId = requestId,
                threadId = wire.threadId,
                turnId = wire.turnId,
                serverName = wire.serverName,
                mode = wire.mode,
                message = wire.message,
                requestedSchemaJson = wire.requestedSchema?.toString(),
                url = wire.url,
            )
        }
        "item/tool/call" -> ServerRequest.DynamicToolCall(
            requestId = requestId,
            threadId = params.optNullableString("threadId"),
            turnId = params.optNullableString("turnId"),
            tool = params.optString("tool"),
            argumentsJson = params.opt("arguments")?.toString() ?: "{}",
        )
        else -> ServerRequest.Unknown(
            requestId = requestId,
            method = method,
            rawPayload = params.toString(),
            threadId = params.optNullableString("threadId"),
            turnId = params.optNullableString("turnId"),
        )
    }

    fun approvalResponse(request: ServerRequest, decision: ApprovalDecision): JSONObject = when (request) {
        is ServerRequest.CommandApproval,
        is ServerRequest.FileApproval,
        -> JSONObject().put("decision", decision.wireValue)
        is ServerRequest.PermissionsApproval -> {
            val accepted = decision == ApprovalDecision.ACCEPT || decision == ApprovalDecision.ACCEPT_FOR_SESSION
            JSONObject()
                .put("permissions", if (accepted) JSONObject(request.permissionsJson) else JSONObject())
                .put("scope", if (decision == ApprovalDecision.ACCEPT_FOR_SESSION) "session" else "turn")
        }
        else -> error("Not an approval request: ${request.method}")
    }

    fun userInputResponse(answers: Map<String, List<String>>): JSONObject {
        val values = JSONObject()
        answers.forEach { (questionId, answer) ->
            values.put(questionId, JSONObject().put("answers", JSONArray(answer)))
        }
        return JSONObject().put("answers", values)
    }

    fun mcpElicitationResponse(action: String, content: JSONObject?): JSONObject = JSONObject()
        .put("action", action)
        .apply { if (content != null) put("content", content) else put("content", JSONObject.NULL) }

    fun parseTurnState(turnId: String?, status: Any?, error: JSONObject? = null): TurnState {
        val id = turnId?.takeIf(String::isNotBlank)
        return when (parseStatus(status)) {
            "inProgress", "active", "running" -> TurnState.Running(id ?: "unknown")
            "completed" -> TurnState.Completed(id)
            "interrupted" -> TurnState.Interrupted(id)
            "failed" -> TurnState.Failed(id, error?.optString("message"))
            else -> TurnState.Idle
        }
    }

    private fun turnInput(text: String, images: List<ImageAttachment>): JSONArray = JSONArray().apply {
        if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
        images.forEach { image ->
            put(JSONObject().put("type", "image").put("url", image.dataUrl).put("detail", "auto"))
        }
    }

    private fun parseDecisions(values: Set<String>): Set<ApprovalDecision> =
        if (values.isEmpty()) ApprovalDecision.entries.toSet()
        else values.mapNotNullTo(mutableSetOf()) { wire -> ApprovalDecision.entries.firstOrNull { it.wireValue == wire } }

    private fun parseUserContent(content: JSONArray?): String = buildList {
        if (content == null) return@buildList
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "text" -> add(part.optString("text"))
                "image" -> add("[图片]")
                "localImage" -> add("[本地图片]")
            }
        }
    }.joinToString("\n")

    private fun parseFileChanges(changes: JSONArray?): List<String> = buildList {
        if (changes == null) return@buildList
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            change.optString("path").ifBlank { change.optString("filePath") }
                .takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun parseStatus(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> value.optString("type").ifBlank { value.toString() }
        else -> "unknown"
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
}
