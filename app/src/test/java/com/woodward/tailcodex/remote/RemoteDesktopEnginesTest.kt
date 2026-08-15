package com.woodward.tailcodex.remote

import com.woodward.tailcodex.domain.RemoteDesktopTarget
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteDesktopEnginesTest {
    @Test
    fun noVncRequiresHttpsAndRemainsIndependent() {
        val engine = NoVncEngine { true }
        assertTrue(engine.validate(RemoteDesktopTarget("host", "desktop", "https://host.tailnet/vnc.html")).isSuccess)
        assertTrue(engine.validate(RemoteDesktopTarget("host", "desktop", "http://host/vnc.html")).isFailure)
    }

    @Test
    fun remoteDesktopUnavailableRemainsAWorkstationOnlyState() {
        val noVnc = NoVncEngine { false }
        val moonlight = MoonlightEngine { false }

        assertFalse(noVnc.isAvailable())
        assertFalse(moonlight.isAvailable())
        assertTrue(moonlight.validate(RemoteDesktopTarget("host", "desktop", "moonlight://host")).isSuccess)
        assertTrue(moonlight.validate(RemoteDesktopTarget("host", "desktop", "http://host")).isFailure)
    }
}
