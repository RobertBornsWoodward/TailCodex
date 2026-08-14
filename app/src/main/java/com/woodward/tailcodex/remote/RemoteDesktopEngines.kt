package com.woodward.tailcodex.remote

import com.woodward.tailcodex.domain.RemoteDesktopEngine
import com.woodward.tailcodex.domain.RemoteDesktopTarget
import java.net.URI

/** noVNC remains a separate workstation surface; it never shares Codex RPC state. */
class NoVncEngine(private val availability: () -> Boolean) : RemoteDesktopEngine {
    override val id = "novnc"
    override val displayName = "noVNC + wayvnc"
    override fun isAvailable() = availability()
    override fun validate(target: RemoteDesktopTarget): Result<Unit> = runCatching {
        val uri = URI(target.launchUri)
        require(uri.scheme == "https") { "noVNC must use HTTPS inside the Tailnet" }
        require(!uri.host.isNullOrBlank()) { "noVNC URL requires a host" }
        Unit
    }
}

/** The launcher URI is supplied by the installed Moonlight client instead of guessed here. */
class MoonlightEngine(private val availability: () -> Boolean) : RemoteDesktopEngine {
    override val id = "moonlight"
    override val displayName = "Moonlight + Sunshine"
    override fun isAvailable() = availability()
    override fun validate(target: RemoteDesktopTarget): Result<Unit> = runCatching {
        val uri = URI(target.launchUri)
        require(uri.scheme !in setOf(null, "http")) { "Moonlight launch URI must be an app link or HTTPS" }
        Unit
    }
}
