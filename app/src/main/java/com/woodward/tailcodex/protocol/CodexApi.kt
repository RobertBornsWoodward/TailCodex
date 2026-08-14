package com.woodward.tailcodex.protocol

import com.woodward.tailcodex.domain.ReviewTarget
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.ImageAttachment
import com.woodward.tailcodex.rpc.JsonRpcSession
import com.woodward.tailcodex.rpc.RpcCall
import com.woodward.tailcodex.rpc.RpcFailure
import com.woodward.tailcodex.rpc.RpcRequestOptions
import org.json.JSONObject

class CodexApi(
    private val rpc: JsonRpcSession,
    private val isReady: () -> Boolean,
) {
    fun listThreads(
        search: String,
        cursor: String? = null,
        callback: (Result<ThreadPage>) -> Unit,
    ): RpcCall? = requestReady(
        method = "thread/list",
        params = CodexWireProtocol.threadListParams(search, cursor),
        callback = { result -> callback(result.mapCatching(CodexWireProtocol::parseThreadPage)) },
    )

    fun readThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit): RpcCall? = requestReady(
        method = "thread/read",
        params = CodexWireProtocol.threadReadParams(threadId),
        callback = { result -> callback(result.mapCatching(CodexWireProtocol::parseThreadSnapshot)) },
    )

    fun resumeThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit): RpcCall? = requestReady(
        method = "thread/resume",
        params = CodexWireProtocol.threadResumeParams(threadId),
        callback = { result -> callback(result.mapCatching(CodexWireProtocol::parseThreadSnapshot)) },
    )

    fun startThread(cwd: String, callback: (Result<ThreadSnapshot>) -> Unit): RpcCall? = requestReady(
        method = "thread/start",
        params = CodexWireProtocol.threadStartParams(cwd),
        callback = { result -> callback(result.mapCatching(CodexWireProtocol::parseThreadSnapshot)) },
    )

    fun startTurn(
        threadId: String,
        text: String,
        images: List<ImageAttachment>,
        callback: (Result<String?>) -> Unit,
    ): RpcCall? = requestReady(
        method = "turn/start",
        params = CodexWireProtocol.turnStartParams(threadId, text, images),
        callback = { result -> callback(result.map { it.optJSONObject("turn")?.optString("id") }) },
    )

    fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        text: String,
        images: List<ImageAttachment>,
        callback: (Result<String?>) -> Unit,
    ): RpcCall? = requestReady(
        method = "turn/steer",
        params = CodexWireProtocol.turnSteerParams(threadId, expectedTurnId, text, images),
        callback = { result -> callback(result.map { it.optString("turnId").takeIf(String::isNotBlank) }) },
    )

    fun interruptTurn(threadId: String, turnId: String, callback: (Result<Unit>) -> Unit): RpcCall? = requestReady(
        method = "turn/interrupt",
        params = CodexWireProtocol.turnInterruptParams(threadId, turnId),
        callback = { result -> callback(result.map { Unit }) },
    )

    fun forkThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit): RpcCall? = requestReady(
        method = "thread/fork",
        params = CodexWireProtocol.threadForkParams(threadId),
        callback = { result -> callback(result.mapCatching(CodexWireProtocol::parseThreadSnapshot)) },
    )

    fun archiveThread(threadId: String, callback: (Result<Unit>) -> Unit): RpcCall? = requestReady(
        method = "thread/archive",
        params = CodexWireProtocol.threadArchiveParams(threadId),
        callback = { result -> callback(result.map { Unit }) },
    )

    fun startReview(threadId: String, target: ReviewTarget, callback: (Result<String?>) -> Unit): RpcCall? {
        val wireTarget = when (target) {
            ReviewTarget.UncommittedChanges -> JSONObject().put("type", "uncommittedChanges")
            is ReviewTarget.BaseBranch -> JSONObject().put("type", "baseBranch").put("branch", target.branch)
            is ReviewTarget.Commit -> JSONObject().put("type", "commit").put("sha", target.sha)
                .apply { target.title?.let { put("title", it) } }
            is ReviewTarget.Custom -> JSONObject().put("type", "custom").put("instructions", target.instructions)
        }
        return requestReady(
            method = "review/start",
            params = CodexWireProtocol.reviewStartParams(threadId, wireTarget),
            callback = { result -> callback(result.map { it.optString("reviewThreadId").takeIf(String::isNotBlank) }) },
        )
    }

    fun respond(request: ServerRequest, result: JSONObject): Boolean = rpc.respond(request.requestId, result)
    fun rejectUnsupported(request: ServerRequest): Boolean =
        rpc.respondError(request.requestId, -32601, "TailCodex does not support server request ${request.method}")

    private fun requestReady(
        method: String,
        params: JSONObject,
        options: RpcRequestOptions = RpcRequestOptions(),
        callback: (Result<JSONObject>) -> Unit,
    ): RpcCall? {
        if (!isReady()) {
            callback(Result.failure(RpcFailure.Disconnected("Codex session is not ready")))
            return null
        }
        return rpc.request(method, params, options, callback)
    }
}
