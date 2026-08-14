package com.woodward.tailcodex.remote

import com.woodward.tailcodex.domain.RemoteDesktopTarget
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDesktopEnginesTest {
    @Test
    fun noVncRequiresHttpsAndRemainsIndependent() {
        val engine = NoVncEngine { true }
        assertTrue(engine.validate(RemoteDesktopTarget("host", "desktop", "https://host.tailnet/vnc.html")).isSuccess)
        assertTrue(engine.validate(RemoteDesktopTarget("host", "desktop", "http://host/vnc.html")).isFailure)
    }
}
