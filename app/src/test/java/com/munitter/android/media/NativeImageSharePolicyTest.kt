package com.munitter.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageSharePolicyTest {
    private val policy = NativeImageSharePolicy("dev.munitter.com")

    @Test
    fun `current origin request is accepted and metadata is normalized`() {
        val request = policy.parse(
            """{"version":1,"url":"https://dev.munitter.com/article/media/b/42?revision=7&variant=save","fileName":"photo/夏?.jpeg","contentType":"IMAGE/JPEG","quality":"hd"}""",
        )

        assertNotNull(request)
        assertEquals("photo_夏_.jpeg", request?.fileName)
        assertEquals("image/jpeg", request?.contentType)
        assertEquals("hd", request?.quality)
        assertEquals(
            "https://dev.munitter.com/article/media/b/42?revision=7&variant=save",
            request?.url,
        )
    }

    @Test
    fun `only exact internal https origin is accepted`() {
        assertTrue(policy.isAllowed("https://dev.munitter.com/dm/media/7?variant=display"))
        assertTrue(policy.isAllowed("https://dev.munitter.com:443/article/media/b/42"))
        assertFalse(policy.isAllowed("http://dev.munitter.com/image.jpg"))
        assertFalse(policy.isAllowed("https://munitter.com/image.jpg"))
        assertFalse(policy.isAllowed("https://example.com/image.jpg"))
        assertFalse(policy.isAllowed("https://user:password@dev.munitter.com/image.jpg"))
        assertFalse(policy.isAllowed("https://dev.munitter.com:444/image.jpg"))
    }

    @Test
    fun `malformed unknown version and cross origin payloads are rejected`() {
        assertNull(policy.parse("not-json"))
        assertNull(
            policy.parse(
                """{"version":2,"url":"https://dev.munitter.com/image.jpg","fileName":"image.jpg"}""",
            ),
        )
        assertNull(
            policy.parse(
                """{"version":1,"url":"https://munitter.com/image.jpg","fileName":"image.jpg"}""",
            ),
        )
    }
}
