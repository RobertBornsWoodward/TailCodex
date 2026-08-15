package com.woodward.tailcodex.terminal

/** Reserved application boundary for the I3 tmux + PTY capability. */
interface TerminalCoordinator {
    val available: Boolean
}

object UnavailableTerminalCoordinator : TerminalCoordinator {
    override val available: Boolean = false
}
