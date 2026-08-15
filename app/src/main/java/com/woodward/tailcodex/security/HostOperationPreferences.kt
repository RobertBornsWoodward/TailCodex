package com.woodward.tailcodex.security

import android.content.Context
import androidx.core.content.edit
import com.woodward.tailcodex.hostcontrol.session.HostOperationCheckpoint
import com.woodward.tailcodex.hostcontrol.session.HostOperationCheckpointStore
import java.security.MessageDigest

class HostOperationPreferences(context: Context) : HostOperationCheckpointStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(profileId: String): HostOperationCheckpoint? {
        val prefix = prefix(profileId)
        val kind = preferences.getString(prefix + KIND, null) ?: return null
        val requestId = preferences.getString(prefix + REQUEST_ID, null) ?: return null
        val idempotencyKey = preferences.getString(prefix + IDEMPOTENCY_KEY, null) ?: return null
        return HostOperationCheckpoint(
            kind = kind,
            requestId = requestId,
            idempotencyKey = idempotencyKey,
            operationId = preferences.getString(prefix + OPERATION_ID, null),
        )
    }

    override fun save(profileId: String, checkpoint: HostOperationCheckpoint) {
        val prefix = prefix(profileId)
        preferences.edit(commit = true) {
            putString(prefix + KIND, checkpoint.kind)
            putString(prefix + REQUEST_ID, checkpoint.requestId)
            putString(prefix + IDEMPOTENCY_KEY, checkpoint.idempotencyKey)
            if (checkpoint.operationId == null) remove(prefix + OPERATION_ID)
            else putString(prefix + OPERATION_ID, checkpoint.operationId)
        }
    }

    override fun clear(profileId: String) {
        val prefix = prefix(profileId)
        preferences.edit(commit = true) {
            remove(prefix + KIND)
            remove(prefix + REQUEST_ID)
            remove(prefix + IDEMPOTENCY_KEY)
            remove(prefix + OPERATION_ID)
        }
    }

    private fun prefix(profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profileId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "operation.$digest."
    }

    private companion object {
        const val PREFERENCES = "tailcodex_host_operations"
        const val KIND = "kind"
        const val REQUEST_ID = "request_id"
        const val IDEMPOTENCY_KEY = "idempotency_key"
        const val OPERATION_ID = "operation_id"
    }
}
