package com.woodward.tailcodex.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProtocolTest {
    @Test
    fun routesNewAndResumedThreadApprovalsToUser() {
        val startParams = CodexProtocol.threadStart(1, "/workspace").getJSONObject("params")
        val resumeParams = CodexProtocol.threadResume(2, "thread-1").getJSONObject("params")

        assertEquals("on-request", startParams.getString("approvalPolicy"))
        assertEquals("user", startParams.getString("approvalsReviewer"))
        assertEquals("on-request", resumeParams.getString("approvalPolicy"))
        assertEquals("user", resumeParams.getString("approvalsReviewer"))
    }

    @Test
    fun steerIncludesActiveTurnPrecondition() {
        val request = CodexProtocol.turnSteer(7, "thread-1", "turn-2", "continue")

        assertEquals("turn/steer", request.getString("method"))
        assertEquals("turn-2", request.getJSONObject("params").getString("expectedTurnId"))
    }

    @Test
    fun parsesThreadList() {
        val result = JSONObject(
            """
            {
              "data": [{
                "id": "019abc",
                "name": "Remote session",
                "preview": "Inspect the project",
                "cwd": "/home/Woodward/project",
                "updatedAt": 1786752000,
                "createdAt": 1786751000,
                "status": {"type": "idle"}
              }]
            }
            """.trimIndent(),
        )

        val threads = CodexProtocol.parseThreads(result)

        assertEquals(1, threads.size)
        assertEquals("Remote session", threads.single().title)
        assertEquals("idle", threads.single().status)
    }

    @Test
    fun parsesResumedMessages() {
        val result = JSONObject(
            """
            {
              "thread": {
                "id": "019abc",
                "name": null,
                "preview": "Hello",
                "cwd": "/home/Woodward",
                "updatedAt": 1786752000,
                "createdAt": 1786751000,
                "status": "idle",
                "turns": [{
                  "id": "turn-1",
                  "status": "completed",
                  "items": [
                    {"id":"u1","type":"userMessage","content":[{"type":"text","text":"Hello"}]},
                    {"id":"a1","type":"agentMessage","text":"Hi from Codex"}
                  ]
                }]
              }
            }
            """.trimIndent(),
        )

        val (_, messages) = CodexProtocol.parseThreadPayload(result)

        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("Hi from Codex", messages[1].text)
    }

    @Test
    fun parsesCommandApproval() {
        val message = JSONObject(
            """{
              "id": 42,
              "method": "item/commandExecution/requestApproval",
              "params": {
                "threadId":"t1",
                "turnId":"r1",
                "command":"git status",
                "availableDecisions":["accept","decline"]
              }
            }""",
        )

        val approval = CodexProtocol.approvalFrom(message)

        assertNotNull(approval)
        assertEquals(ApprovalKind.COMMAND, approval?.kind)
        assertEquals("git status", approval?.detail)
        assertTrue(requireNotNull(approval).supports("accept"))
        assertFalse(approval.supports("acceptForSession"))
    }

    @Test
    fun permissionDeclineReturnsEmptyGrantInsteadOfRpcError() {
        val request = ApprovalRequest(
            rpcId = 43,
            kind = ApprovalKind.PERMISSIONS,
            title = "Grant permissions?",
            detail = "Network access",
            threadId = "t1",
            turnId = "r1",
            rawPermissions = """{"network":{"enabled":true}}""",
        )

        val response = CodexProtocol.approvalResponse(request, "decline")

        assertFalse(response.has("error"))
        assertEquals(0, response.getJSONObject("result").getJSONObject("permissions").length())
        assertEquals("turn", response.getJSONObject("result").getString("scope"))
    }

    @Test
    fun permissionSessionApprovalReturnsRequestedGrant() {
        val request = ApprovalRequest(
            rpcId = "approval-44",
            kind = ApprovalKind.PERMISSIONS,
            title = "Grant permissions?",
            detail = "Network access",
            threadId = "t1",
            turnId = "r1",
            rawPermissions = """{"network":{"enabled":true}}""",
        )

        val response = CodexProtocol.approvalResponse(request, "acceptForSession")
        val result = response.getJSONObject("result")

        assertEquals("session", result.getString("scope"))
        assertTrue(result.getJSONObject("permissions").has("network"))
    }

    @Test
    fun recognizesResolvedServerRequest() {
        val params = JSONObject().put("threadId", "t1").put("requestId", 42)

        assertEquals("42", CodexProtocol.resolvedRequestId("serverRequest/resolved", params))
    }
}
