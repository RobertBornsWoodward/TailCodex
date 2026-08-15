package com.woodward.tailcodex.protocol

import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.domain.ServerRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexWireProtocolTest {
    @Test
    fun readAlwaysIncludesTurnsAndResumeRoutesApprovalsToUser() {
        assertTrue(CodexWireProtocol.threadReadParams("t1").getBoolean("includeTurns"))
        val resume = CodexWireProtocol.threadResumeParams("t1")
        assertEquals("on-request", resume.getString("approvalPolicy"))
        assertEquals("user", resume.getString("approvalsReviewer"))
    }

    @Test
    fun parsesApprovalUserInputMcpAndUnknownRequests() {
        val approval = CodexWireProtocol.parseServerRequest(
            RpcId(1), "item/commandExecution/requestApproval",
            JSONObject("""{"threadId":"t","turnId":"r","itemId":"i","availableDecisions":["accept","decline"]}"""),
        ) as ServerRequest.CommandApproval
        assertEquals(setOf(ApprovalDecision.ACCEPT, ApprovalDecision.DECLINE), approval.availableDecisions)

        val input = CodexWireProtocol.parseServerRequest(
            RpcId(2), "item/tool/requestUserInput",
            JSONObject("""{"threadId":"t","turnId":"r","itemId":"i","isBlocking":true,"questions":[{"id":"q","header":"H","question":"Q"}]}"""),
        ) as ServerRequest.UserInput
        assertEquals("q", input.questions.single().id)

        assertTrue(CodexWireProtocol.parseServerRequest(
            RpcId(3), "mcpServer/elicitation/request",
            JSONObject("""{"threadId":"t","serverName":"s","mode":"url","message":"open","url":"https://example.test"}"""),
        ) is ServerRequest.McpElicitation)
        assertTrue(CodexWireProtocol.parseServerRequest(RpcId(4), "future/request", JSONObject()) is ServerRequest.Unknown)
    }

    @Test
    fun imageInputUsesSchemaDataUrlShapeWithoutLeakingIntoUi() {
        val params = CodexWireProtocol.turnStartParams(
            "t",
            "inspect",
            listOf(com.woodward.tailcodex.domain.ImageAttachment("screen.png", "data:image/png;base64,AA==")),
        )
        val input = params.getJSONArray("input")
        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertEquals("image", input.getJSONObject(1).getString("type"))
        assertTrue(input.getJSONObject(1).getString("url").startsWith("data:image/png;base64,"))
        assertFalse(params.toString().contains("screen.png"))
    }
}
