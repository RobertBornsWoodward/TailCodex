package com.woodward.tailcodex.hostcontrol.transport

import com.woodward.tailcodex.hostcontrol.protocol.CodexOwnership
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConfig
import com.woodward.tailcodex.hostcontrol.protocol.HostOperationStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpHostAgentTransportTest {
    @Test
    fun parsesServiceAndUsesAuthorizationHeader() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {"ok":true,"services":[
              {"id":"host-agent","state":"RUNNING"},
              {"id":"codex-app-server","snapshot":{"ownership":"MANAGED_SYSTEMD","state":"LOCAL_READY","portOpen":true,"ready":true,"detail":"200 OK"}}
            ]}
        """.trimIndent()).setHeader("Content-Type", "application/json"))
        server.start()
        try {
            val endpoint = server.url("/").toString().trimEnd('/')
            val snapshot = OkHttpHostAgentTransport().services(HostAgentConfig(endpoint, "secret-device-token"))
            assertEquals(CodexOwnership.MANAGED_SYSTEMD, snapshot.ownership)
            assertEquals(CodexServiceState.LOCAL_READY, snapshot.state)
            assertTrue(snapshot.ready)
            val request = server.takeRequest()
            assertEquals("Bearer secret-device-token", request.getHeader("Authorization"))
            assertFalse(request.requestUrl?.query.orEmpty().contains("secret-device-token"))
        } finally {
            server.close()
        }
    }

    @Test
    fun parsesAuthoritativeOperationEnvelope() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {"ok":true,"operation":{"operationId":"op_123","kind":"codex.ensure-running","status":"FAILED","error":{"code":"CODEX_PORT_CONFLICT","message":"port occupied"}}}
        """.trimIndent()).setHeader("Content-Type", "application/json"))
        server.start()
        try {
            val endpoint = server.url("/").toString().trimEnd('/')
            val operation = OkHttpHostAgentTransport().operation(HostAgentConfig(endpoint, "credential"), "op_123")
            assertEquals("op_123", operation.id)
            assertEquals(HostOperationStatus.FAILED, operation.status)
            assertEquals("CODEX_PORT_CONFLICT", operation.errorCode)
        } finally {
            server.close()
        }
    }

    @Test
    fun parsesMetadataOnlyLogSummary() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {"ok":true,"entries":[{"timestamp":"2026-08-15T00:00:00Z","actor":"phone-1","action":"codex.ensure-running","riskLevel":"PROCESS_CONTROL","outcome":"succeeded"}]}
        """.trimIndent()).setHeader("Content-Type", "application/json"))
        server.start()
        try {
            val endpoint = server.url("/").toString().trimEnd('/')
            val entries = OkHttpHostAgentTransport().logSummary(HostAgentConfig(endpoint, "credential"))
            assertEquals(1, entries.size)
            assertEquals("codex.ensure-running", entries.single().action)
            assertEquals("PROCESS_CONTROL", entries.single().riskLevel)
        } finally {
            server.close()
        }
    }
}
