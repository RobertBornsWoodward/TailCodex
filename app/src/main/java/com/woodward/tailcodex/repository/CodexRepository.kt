package com.woodward.tailcodex.repository

import com.woodward.tailcodex.domain.ReviewTarget
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.ThreadSnapshot
import com.woodward.tailcodex.domain.ImageAttachment
import com.woodward.tailcodex.protocol.CodexApi
import com.woodward.tailcodex.protocol.CodexWireProtocol
import com.woodward.tailcodex.protocol.ThreadPage
import org.json.JSONObject

interface CodexRepository {
    fun listThreads(search: String, cursor: String?, callback: (Result<ThreadPage>) -> Unit)
    fun readThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit)
    fun resumeThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit)
    fun startThread(cwd: String, callback: (Result<ThreadSnapshot>) -> Unit)
    fun startTurn(threadId: String, text: String, images: List<ImageAttachment>, callback: (Result<String?>) -> Unit)
    fun steerTurn(threadId: String, turnId: String, text: String, images: List<ImageAttachment>, callback: (Result<String?>) -> Unit)
    fun interruptTurn(threadId: String, turnId: String, callback: (Result<Unit>) -> Unit)
    fun forkThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit)
    fun archiveThread(threadId: String, callback: (Result<Unit>) -> Unit)
    fun startReview(threadId: String, target: ReviewTarget, callback: (Result<String?>) -> Unit)
    fun respondApproval(request: ServerRequest, decision: com.woodward.tailcodex.domain.ApprovalDecision): Boolean
    fun respondUserInput(request: ServerRequest.UserInput, answers: Map<String, List<String>>): Boolean
    fun respondMcp(request: ServerRequest.McpElicitation, action: String, content: JSONObject?): Boolean
    fun rejectUnsupported(request: ServerRequest): Boolean
}

class DefaultCodexRepository(private val api: CodexApi) : CodexRepository {
    override fun listThreads(search: String, cursor: String?, callback: (Result<ThreadPage>) -> Unit) {
        api.listThreads(search, cursor, callback)
    }

    override fun readThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit) {
        api.readThread(threadId, callback)
    }

    override fun resumeThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit) {
        api.resumeThread(threadId, callback)
    }

    override fun startThread(cwd: String, callback: (Result<ThreadSnapshot>) -> Unit) {
        api.startThread(cwd, callback)
    }

    override fun startTurn(threadId: String, text: String, images: List<ImageAttachment>, callback: (Result<String?>) -> Unit) {
        api.startTurn(threadId, text, images, callback)
    }

    override fun steerTurn(threadId: String, turnId: String, text: String, images: List<ImageAttachment>, callback: (Result<String?>) -> Unit) {
        api.steerTurn(threadId, turnId, text, images, callback)
    }

    override fun interruptTurn(threadId: String, turnId: String, callback: (Result<Unit>) -> Unit) {
        api.interruptTurn(threadId, turnId, callback)
    }

    override fun forkThread(threadId: String, callback: (Result<ThreadSnapshot>) -> Unit) {
        api.forkThread(threadId, callback)
    }

    override fun archiveThread(threadId: String, callback: (Result<Unit>) -> Unit) {
        api.archiveThread(threadId, callback)
    }

    override fun startReview(threadId: String, target: ReviewTarget, callback: (Result<String?>) -> Unit) {
        api.startReview(threadId, target, callback)
    }

    override fun respondApproval(
        request: ServerRequest,
        decision: com.woodward.tailcodex.domain.ApprovalDecision,
    ): Boolean = api.respond(request, CodexWireProtocol.approvalResponse(request, decision))

    override fun respondUserInput(
        request: ServerRequest.UserInput,
        answers: Map<String, List<String>>,
    ): Boolean = api.respond(request, CodexWireProtocol.userInputResponse(answers))

    override fun respondMcp(
        request: ServerRequest.McpElicitation,
        action: String,
        content: JSONObject?,
    ): Boolean = api.respond(request, CodexWireProtocol.mcpElicitationResponse(action, content))

    override fun rejectUnsupported(request: ServerRequest): Boolean = api.rejectUnsupported(request)
}
