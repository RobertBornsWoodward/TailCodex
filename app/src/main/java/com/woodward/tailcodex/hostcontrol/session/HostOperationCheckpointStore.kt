package com.woodward.tailcodex.hostcontrol.session

data class HostOperationCheckpoint(
    val kind: String,
    val requestId: String,
    val idempotencyKey: String,
    val operationId: String? = null,
)

/**
 * Keeps enough non-secret operation identity to recover after Android process recreation.
 * Host Agent's GET operation endpoint remains authoritative; this store never persists results.
 */
interface HostOperationCheckpointStore {
    fun load(profileId: String): HostOperationCheckpoint?
    fun save(profileId: String, checkpoint: HostOperationCheckpoint)
    fun clear(profileId: String)
}

class InMemoryHostOperationCheckpointStore : HostOperationCheckpointStore {
    private val checkpoints = mutableMapOf<String, HostOperationCheckpoint>()

    @Synchronized
    override fun load(profileId: String): HostOperationCheckpoint? = checkpoints[profileId]

    @Synchronized
    override fun save(profileId: String, checkpoint: HostOperationCheckpoint) {
        checkpoints[profileId] = checkpoint
    }

    @Synchronized
    override fun clear(profileId: String) {
        checkpoints.remove(profileId)
    }
}
