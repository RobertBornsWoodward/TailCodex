package com.woodward.tailcodex.workstation

/** Reserved application boundary for desktop and remote-workstation adapters. */
interface WorkstationCoordinator {
    val available: Boolean
}

object UnavailableWorkstationCoordinator : WorkstationCoordinator {
    override val available: Boolean = false
}
