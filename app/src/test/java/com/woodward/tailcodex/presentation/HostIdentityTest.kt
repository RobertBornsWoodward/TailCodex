package com.woodward.tailcodex.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostIdentityTest {
    @Test
    fun identityIsStableButSeparatesSameNameDifferentEndpoints() {
        val first = stableHostProfileId("wss://first.example:8443")
        val repeated = stableHostProfileId("wss://first.example:8443/")
        val second = stableHostProfileId("wss://second.example:8443")

        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertEquals(29, first.length)
    }
}
