package com.woodward.tailcodex.ui.workspace

enum class WorkspaceSurface { GIT, FILES, LOGS, METRICS, TERMINAL }

data class WorkspaceAvailability(
    val surface: WorkspaceSurface,
    val available: Boolean,
    val reason: String? = null,
)

/**
 * I5 presentation boundary. A future shared Web Workspace can implement selected surfaces without
 * replacing native Codex chat, approvals, notifications, attachments, or connection state.
 */
interface WorkspacePresentation {
    fun availability(): List<WorkspaceAvailability>
}
