package com.munitter.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmRuntimeEnvironmentTest {
    @Test
    fun `server environment names preserve the exact Development and Production boundary`() {
        assertEquals("Development", FcmRuntimeEnvironment.serverName("development"))
        assertEquals("Production", FcmRuntimeEnvironment.serverName("production"))
    }

    @Test
    fun `unexpected or case-shifted environments fail closed`() {
        assertNull(FcmRuntimeEnvironment.serverName("staging"))
        assertNull(FcmRuntimeEnvironment.serverName("Production"))
        assertFalse(FcmRuntimeEnvironment.isSupported(""))
        assertTrue(FcmRuntimeEnvironment.isSupported("production"))
    }
}
