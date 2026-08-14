package com.woodward.tailcodex.data

import org.json.JSONArray
import org.json.JSONObject

object CodexProtocol {
    fun request(id: Long, method: String, params: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("id", id).put("method", method).put("params", params)

    fun notification(method: String, params: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("method", method).put("params", params)

    fun initialize(id: Long): JSONObject = request(
        id,
        "initialize",
        JSONObject().put(
            "clientInfo",
            JSONObject()
                .put("name", "tailcodex_android")
                .put("title", "TailCodex Android")
                .put("version", "0.1.2"),
        ),
    )

    fun threadList(id: Long, search: String): JSONObject {
        val params = JSONObject()
            .put("limit", 50)
            .put("sortKey", "updated_at")
            .put("sortDirection", "desc")
        if (search.isNotBlank()) params.put("searchTerm", search.trim())
        return request(id, "thread/list", params)
    }

    fun threadResume(id: Long, threadId: String): JSONObject = request(
        id,
        "thread/resume",
        JSONObject()
            .put("threadId", threadId)
            .put("approvalPolicy", "on-request")
            .put("approvalsReviewer", "user"),
    )

    fun threadStart(id: Long, cwd: String): JSONObject = request(
        id,
        "thread/start",
        JSONObject()
            .put("cwd", cwd)
            .put("serviceName", "tailcodex_android")
            .put("approvalPolicy", "on-request")
            .put("approvalsReviewer", "user"),
    )

    fun turnStart(id: Long, threadId: String, text: String): JSONObject = request(
        id,
        "turn/start",
        JSONObject()
            .put("threadId", threadId)
            .put(
                "input",
                JSONArray().put(JSONObject().put("type", "text").put("text", text)),
            ),
    )

    fun turnSteer(
        id: Long,
        threadId: String,
        expectedTurnId: String,
        text: String,
    ): JSONObject = request(
        id,
        "turn/steer",
        JSONObject()
            .put("threadId", threadId)
            .put("expectedTurnId", expectedTurnId)
            .put(
                "input",
                JSONArray().put(JSONObject().put("type", "text").put("text", text)),
            ),
    )

    fun turnInterrupt(id: Long, threadId: String, turnId: String): JSONObject = request(
        id,
        "turn/interrupt",
        JSONObject().put("threadId", threadId).put("turnId", turnId),
    )

    fun parseThreads(result: JSONObject): List<ThreadSummary> {
        val data = result.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let(::parseThread)?.let(::add)
            }
        }
    }

    fun parseThreadPayload(result: JSONObject): Pair<ThreadSummary, List<ChatEntry>> {
        val thread = result.getJSONObject("thread")
        val summary = requireNotNull(parseThread(thread))
        val messages = buildList {
            val turns = thread.optJSONArray("turns") ?: JSONArray()
            for (turnIndex in 0 until turns.length()) {
                val items = turns.optJSONObject(turnIndex)?.optJSONArray("items") ?: continue
                for (itemIndex in 0 until items.length()) {
                    items.optJSONObject(itemIndex)?.let(::parseItem)?.let(::add)
                }
            }
        }
        return summary to messages
    }

    fun parseThread(thread: JSONObject): ThreadSummary? {
        val id = thread.optString("id")
        if (id.isBlank()) return null
        val name = thread.optString("name").takeIf { it.isNotBlank() && it != "null" }
        val preview = thread.optString("preview")
        return ThreadSummary(
            id = id,
            title = name ?: preview.lineSequence().firstOrNull()?.take(72).orEmpty().ifBlank { "未命名会话" },
            preview = preview,
            cwd = thread.optString("cwd"),
            updatedAt = thread.optLong("updatedAt", thread.optLong("createdAt")),
            status = parseStatus(thread.opt("status")),
        )
    }

    fun parseItem(item: JSONObject): ChatEntry? {
        val id = item.optString("id").ifBlank { "event-${item.hashCode()}" }
        return when (item.optString("type")) {
            "userMessage" -> ChatEntry(
                id,
                MessageRole.USER,
                parseUserContent(item.optJSONArray("content")),
            )
            "agentMessage" -> ChatEntry(id, MessageRole.ASSISTANT, item.optString("text"))
            "commandExecution" -> ChatEntry(
                id,
                MessageRole.EVENT,
                item.optString("command").ifBlank { "命令执行" },
                "命令 · ${item.optString("status", "进行中")}",
            )
            "fileChange" -> ChatEntry(
                id,
                MessageRole.EVENT,
                parseFileChanges(item.optJSONArray("changes")),
                "文件修改 · ${item.optString("status", "进行中")}",
            )
            "mcpToolCall" -> ChatEntry(
                id,
                MessageRole.EVENT,
                "${item.optString("server")} / ${item.optString("tool")}",
                "工具调用 · ${item.optString("status", "进行中")}",
            )
            else -> null
        }
    }

    fun approvalFrom(message: JSONObject): ApprovalRequest? {
        if (!message.has("id")) return null
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val rpcId = message.get("id")
        return when (method) {
            "item/commandExecution/requestApproval" -> ApprovalRequest(
                rpcId = rpcId,
                kind = ApprovalKind.COMMAND,
                title = if (params.has("networkApprovalContext")) "允许网络访问？" else "允许运行命令？",
                detail = params.optString("command")
                    .ifBlank { params.optString("reason") }
                    .ifBlank { params.optJSONObject("networkApprovalContext")?.toString(2).orEmpty() },
                threadId = params.optString("threadId").takeIf(String::isNotBlank),
                turnId = params.optString("turnId").takeIf(String::isNotBlank),
                availableDecisions = parseAvailableDecisions(params),
            )
            "item/fileChange/requestApproval" -> ApprovalRequest(
                rpcId = rpcId,
                kind = ApprovalKind.FILE_CHANGE,
                title = "允许修改文件？",
                detail = params.optString("reason").ifBlank { "Codex 请求写入工作区文件。" },
                threadId = params.optString("threadId").takeIf(String::isNotBlank),
                turnId = params.optString("turnId").takeIf(String::isNotBlank),
            )
            "item/permissions/requestApproval" -> ApprovalRequest(
                rpcId = rpcId,
                kind = ApprovalKind.PERMISSIONS,
                title = "授予额外权限？",
                detail = params.optString("reason").ifBlank { "Codex 请求临时扩大权限。" },
                threadId = params.optString("threadId").takeIf(String::isNotBlank),
                turnId = params.optString("turnId").takeIf(String::isNotBlank),
                rawPermissions = params.optJSONObject("permissions")?.toString(),
            )
            else -> null
        }
    }

    fun approvalResponse(request: ApprovalRequest, decision: String): JSONObject {
        require(request.supports(decision)) { "Unsupported approval decision: $decision" }
        val result = if (request.kind == ApprovalKind.PERMISSIONS) {
            val accepted = decision == "accept" || decision == "acceptForSession"
            JSONObject()
                .put(
                    "permissions",
                    if (accepted && request.rawPermissions != null) {
                        JSONObject(request.rawPermissions)
                    } else {
                        JSONObject()
                    },
                )
                .put("scope", if (decision == "acceptForSession") "session" else "turn")
        } else {
            JSONObject().put("decision", decision)
        }
        return JSONObject().put("id", request.rpcId).put("result", result)
    }

    fun resolvedRequestId(method: String, params: JSONObject): String? =
        if (method == "serverRequest/resolved" && params.has("requestId")) {
            params.get("requestId").toString()
        } else {
            null
        }

    private fun parseAvailableDecisions(params: JSONObject): Set<String> {
        val values = params.optJSONArray("availableDecisions") ?: return setOf(
            "accept",
            "acceptForSession",
            "decline",
            "cancel",
        )
        return buildSet {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun parseUserContent(content: JSONArray?): String {
        if (content == null) return ""
        return buildList {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "text" -> add(part.optString("text"))
                    "image" -> add("[图片]")
                    "localImage" -> add("[本地图片]")
                }
            }
        }.joinToString("\n")
    }

    private fun parseFileChanges(changes: JSONArray?): String {
        if (changes == null || changes.length() == 0) return "文件修改"
        return buildList {
            for (index in 0 until changes.length()) {
                val change = changes.optJSONObject(index) ?: continue
                add(change.optString("path").ifBlank { change.optString("filePath") })
            }
        }.filter(String::isNotBlank).joinToString("\n").ifBlank { "文件修改" }
    }

    private fun parseStatus(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> value.optString("type").ifBlank { value.toString() }
        else -> "unknown"
    }
}
