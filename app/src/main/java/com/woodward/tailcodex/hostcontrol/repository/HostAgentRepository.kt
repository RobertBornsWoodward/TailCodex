package com.woodward.tailcodex.hostcontrol.repository

import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceSnapshot
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConfig
import com.woodward.tailcodex.hostcontrol.protocol.HostHello
import com.woodward.tailcodex.hostcontrol.protocol.HostOperation
import com.woodward.tailcodex.hostcontrol.protocol.HostPairingResult
import com.woodward.tailcodex.hostcontrol.protocol.HostLogSummaryEntry

interface HostAgentRepository {
    suspend fun hello(endpoint: String): HostHello
    suspend fun pair(endpoint: String, code: String, deviceId: String, deviceName: String): HostPairingResult
    suspend fun capabilities(config: HostAgentConfig): Pair<Set<String>, Set<String>>
    suspend fun services(config: HostAgentConfig): CodexServiceSnapshot
    suspend fun logSummary(config: HostAgentConfig): List<HostLogSummaryEntry>
    suspend fun ensureCodexRunning(config: HostAgentConfig, requestId: String, idempotencyKey: String): String
    suspend fun restartCodex(config: HostAgentConfig, requestId: String, idempotencyKey: String): String
    suspend fun operation(config: HostAgentConfig, operationId: String): HostOperation
}
