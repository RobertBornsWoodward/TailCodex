package com.woodward.tailcodex.session

import com.woodward.tailcodex.domain.ThreadLease

class LeaseManager {
    private var lease: ThreadLease = ThreadLease.NONE
    private var claimedConnectionGeneration: Long = -1
    private var leaseHostId: String? = null
    private var localReconnectCandidate = false

    fun fromRead(threadStatus: String, hostId: String = "default"): ThreadLease {
        lease = if (threadStatus == "active") ThreadLease.OTHER_CLIENT else ThreadLease.NONE
        leaseHostId = hostId
        claimedConnectionGeneration = -1
        localReconnectCandidate = false
        return lease
    }

    fun fromReconciliation(threadStatus: String, hostId: String = "default"): ThreadLease {
        lease = when {
            threadStatus != "active" -> ThreadLease.NONE
            localReconnectCandidate && leaseHostId == hostId -> ThreadLease.UNKNOWN
            else -> ThreadLease.OTHER_CLIENT
        }
        leaseHostId = hostId
        claimedConnectionGeneration = -1
        localReconnectCandidate = lease == ThreadLease.UNKNOWN
        return lease
    }

    fun claimLocal(connectionGeneration: Long, hostId: String = "default"): ThreadLease {
        lease = ThreadLease.LOCAL_PHONE
        leaseHostId = hostId
        claimedConnectionGeneration = connectionGeneration
        localReconnectCandidate = false
        return lease
    }

    fun onDisconnected(hasThread: Boolean) {
        localReconnectCandidate = hasThread && lease == ThreadLease.LOCAL_PHONE
        lease = if (hasThread) ThreadLease.UNKNOWN else ThreadLease.NONE
        claimedConnectionGeneration = -1
        if (!hasThread) leaseHostId = null
    }

    fun clear() {
        lease = ThreadLease.NONE
        claimedConnectionGeneration = -1
        leaseHostId = null
        localReconnectCandidate = false
    }

    fun current(): ThreadLease = lease

    fun isValidLocal(connectionGeneration: Long, hostId: String = "default"): Boolean =
        lease == ThreadLease.LOCAL_PHONE && claimedConnectionGeneration == connectionGeneration && leaseHostId == hostId
}
