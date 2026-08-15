package com.woodward.tailcodex.hostcontrol.session

import com.woodward.tailcodex.hostcontrol.protocol.CodexOwnership
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceSnapshot
import com.woodward.tailcodex.hostcontrol.protocol.CodexServiceState
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentConfig
import com.woodward.tailcodex.hostcontrol.protocol.HostAgentException
import com.woodward.tailcodex.hostcontrol.protocol.HostHello
import com.woodward.tailcodex.hostcontrol.protocol.HostOperation
import com.woodward.tailcodex.hostcontrol.protocol.HostOperationStatus
import com.woodward.tailcodex.hostcontrol.protocol.HostPairingResult
import com.woodward.tailcodex.hostcontrol.protocol.HostLogSummaryEntry
import com.woodward.tailcodex.hostcontrol.repository.HostAgentRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostControlCoordinatorTest {
    @Test
    fun healthyExternalCodexIsAcceptedWithoutLifecycleMutation() = runBlocking {
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(listOf(snapshot(CodexOwnership.EXTERNAL, CodexServiceState.EXTERNAL, ready = true))),
        )
        val coordinator = HostControlCoordinator(api)
        coordinator.configure("https://host.example:8444", "credential")

        val ready = coordinator.ensureCodexReady()

        assertEquals(CodexServiceState.EXTERNAL, ready.state)
        assertEquals(0, api.ensureCalls)
        assertTrue(coordinator.state.value.codexLocallyReady)
    }

    @Test
    fun stoppedManagedCodexUsesOperationGetAsAuthority() = runBlocking {
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.STOPPED),
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.LOCAL_READY, ready = true),
                ),
            ),
            operations = ArrayDeque(
                listOf(
                    HostOperation("op_1", "codex.ensure-running", HostOperationStatus.RUNNING),
                    HostOperation("op_1", "codex.ensure-running", HostOperationStatus.SUCCEEDED),
                ),
            ),
        )
        val coordinator = HostControlCoordinator(api)
        coordinator.configure("https://host.example:8444", "credential")

        val ready = coordinator.ensureCodexReady()

        assertEquals(CodexServiceState.LOCAL_READY, ready.state)
        assertEquals(1, api.ensureCalls)
        assertEquals(2, api.operationCalls)
        assertEquals(HostOperationStatus.SUCCEEDED, coordinator.state.value.operation?.status)
    }

    @Test
    fun processRecreationResumesPersistedOperationWithoutPostingAgain() = runBlocking {
        val checkpoints = InMemoryHostOperationCheckpointStore().apply {
            save(
                "host-1",
                HostOperationCheckpoint(
                    kind = "codex.ensure-running",
                    requestId = "android-existing",
                    idempotencyKey = "android-existing",
                    operationId = "op_existing",
                ),
            )
        }
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.STARTING),
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.LOCAL_READY, ready = true),
                ),
            ),
            operations = ArrayDeque(
                listOf(
                    HostOperation("op_existing", "codex.ensure-running", HostOperationStatus.RUNNING),
                    HostOperation("op_existing", "codex.ensure-running", HostOperationStatus.SUCCEEDED),
                ),
            ),
        )

        val recreated = HostControlCoordinator(api, checkpoints)
        recreated.configure("https://host.example:8444", "credential", "host-1")
        val ready = recreated.ensureCodexReady()

        assertEquals(CodexServiceState.LOCAL_READY, ready.state)
        assertEquals(0, api.ensureCalls)
        assertEquals(2, api.operationCalls)
        assertEquals(null, checkpoints.load("host-1"))
    }

    @Test
    fun checkpointBeforePostReusesOriginalIdempotencyKeyAfterRecreation() = runBlocking {
        val checkpoints = InMemoryHostOperationCheckpointStore().apply {
            save(
                "host-1",
                HostOperationCheckpoint(
                    kind = "codex.ensure-running",
                    requestId = "android-before-post",
                    idempotencyKey = "android-before-post",
                ),
            )
        }
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.STOPPED),
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.LOCAL_READY, ready = true),
                ),
            ),
            operations = ArrayDeque(
                listOf(HostOperation("op_1", "codex.ensure-running", HostOperationStatus.SUCCEEDED)),
            ),
        )

        val recreated = HostControlCoordinator(api, checkpoints)
        recreated.configure("https://host.example:8444", "credential", "host-1")
        recreated.ensureCodexReady()

        assertEquals(1, api.ensureCalls)
        assertEquals("android-before-post", api.lastRequestId)
        assertEquals("android-before-post", api.lastIdempotencyKey)
        assertEquals(null, checkpoints.load("host-1"))
    }

    @Test
    fun lifecycleFeatureAndGrantAreCheckedBeforeMutation() = runBlocking {
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(listOf(snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.STOPPED))),
            features = setOf("codex.lifecycle"),
            grants = emptySet(),
        )
        val coordinator = HostControlCoordinator(api)
        coordinator.configure("https://host.example:8444", "credential")

        try {
            coordinator.ensureCodexReady()
            fail("missing grant was accepted")
        } catch (error: HostAgentException) {
            assertEquals("GRANT_REQUIRED", error.code)
        }
        assertEquals(0, api.ensureCalls)
    }

    @Test
    fun hostAgentCanRefreshAfterIndependentNetworkFailure() = runBlocking {
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(listOf(snapshot(CodexOwnership.EXTERNAL, CodexServiceState.EXTERNAL, ready = true))),
            serviceFailuresBeforeSuccess = 1,
        )
        val coordinator = HostControlCoordinator(api)
        coordinator.configure("https://host.example:8444", "credential")

        try {
            coordinator.refresh()
            fail("network failure was swallowed")
        } catch (_: IOException) {
            assertTrue(coordinator.state.value.connection is com.woodward.tailcodex.hostcontrol.protocol.HostAgentConnectionState.Disconnected)
        }

        val recovered = coordinator.refresh()
        assertTrue(recovered.connection is com.woodward.tailcodex.hostcontrol.protocol.HostAgentConnectionState.Ready)
        assertEquals(CodexServiceState.EXTERNAL, recovered.service?.state)
    }

    @Test
    fun reverseProxyUnavailableIsAHostDisconnectNotAnAuthFailure() = runBlocking {
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(listOf(snapshot(CodexOwnership.EXTERNAL, CodexServiceState.EXTERNAL, ready = true))),
            serviceFailuresBeforeSuccess = 1,
            serviceFailure = HostAgentException("HTTP_503", "upstream unavailable", 503),
        )
        val coordinator = HostControlCoordinator(api)
        coordinator.configure("https://host.example:8444", "credential")

        try {
            coordinator.refresh()
            fail("proxy failure was swallowed")
        } catch (_: HostAgentException) {
            assertTrue(coordinator.state.value.connection is com.woodward.tailcodex.hostcontrol.protocol.HostAgentConnectionState.Disconnected)
        }
    }

    @Test
    fun missingOldDeviceOperationIsClearedAfterRepairAndReplaced() = runBlocking {
        val checkpoints = InMemoryHostOperationCheckpointStore().apply {
            save(
                "host-1",
                HostOperationCheckpoint(
                    kind = "codex.ensure-running",
                    requestId = "old-device-request",
                    idempotencyKey = "old-device-request",
                    operationId = "op_old_device",
                ),
            )
        }
        val api = FakeHostAgentApi(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.STOPPED),
                    snapshot(CodexOwnership.MANAGED_SYSTEMD, CodexServiceState.LOCAL_READY, ready = true),
                ),
            ),
            operations = ArrayDeque(
                listOf(HostOperation("op_1", "codex.ensure-running", HostOperationStatus.SUCCEEDED)),
            ),
            operationNotFoundBeforeSuccess = 1,
        )
        val coordinator = HostControlCoordinator(api, checkpoints)
        coordinator.configure("https://host.example:8444", "new-credential", "host-1")

        val ready = coordinator.ensureCodexReady()

        assertEquals(CodexServiceState.LOCAL_READY, ready.state)
        assertEquals(1, api.ensureCalls)
        assertEquals(null, checkpoints.load("host-1"))
    }

    private class FakeHostAgentApi(
        private val snapshots: ArrayDeque<CodexServiceSnapshot>,
        private val operations: ArrayDeque<HostOperation> = ArrayDeque(),
        private val features: Set<String> = setOf("codex.lifecycle"),
        private val grants: Set<String> = setOf("codex.lifecycle"),
        private var serviceFailuresBeforeSuccess: Int = 0,
        private val serviceFailure: Throwable = IOException("host offline"),
        private var operationNotFoundBeforeSuccess: Int = 0,
    ) : HostAgentRepository {
        var ensureCalls = 0
        var operationCalls = 0
        var lastRequestId: String? = null
        var lastIdempotencyKey: String? = null

        override suspend fun hello(endpoint: String) = HostHello(1, "0.1.0", "0.3.0")
        override suspend fun pair(endpoint: String, code: String, deviceId: String, deviceName: String) =
            HostPairingResult(deviceId, "paired", setOf("codex.lifecycle"))
        override suspend fun capabilities(config: HostAgentConfig) =
            features to grants
        override suspend fun services(config: HostAgentConfig): CodexServiceSnapshot {
            if (serviceFailuresBeforeSuccess > 0) {
                serviceFailuresBeforeSuccess--
                throw serviceFailure
            }
            return snapshots.removeFirst()
        }
        override suspend fun logSummary(config: HostAgentConfig): List<HostLogSummaryEntry> = emptyList()
        override suspend fun ensureCodexRunning(
            config: HostAgentConfig,
            requestId: String,
            idempotencyKey: String,
        ): String {
            ensureCalls++
            lastRequestId = requestId
            lastIdempotencyKey = idempotencyKey
            return "op_1"
        }
        override suspend fun restartCodex(config: HostAgentConfig, requestId: String, idempotencyKey: String) = "op_restart"
        override suspend fun operation(config: HostAgentConfig, operationId: String): HostOperation {
            operationCalls++
            if (operationNotFoundBeforeSuccess > 0) {
                operationNotFoundBeforeSuccess--
                throw HostAgentException("OPERATION_NOT_FOUND", "operation was not found", 404)
            }
            return operations.removeFirst()
        }
    }

    companion object {
        fun snapshot(
            ownership: CodexOwnership,
            state: CodexServiceState,
            ready: Boolean = false,
        ) = CodexServiceSnapshot(ownership, state, portOpen = ready, ready = ready, detail = null)
    }
}
