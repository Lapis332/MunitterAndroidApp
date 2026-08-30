package com.munitter.android.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenGeometryBridgeContractTest {
    @Test
    fun `delivery is limited to the exact internal https origin`() {
        val policy = DeviceScreenGeometryOriginPolicy("dev.munitter.com")

        assertTrue(policy.isAllowedDocument("https://dev.munitter.com/home"))
        assertTrue(policy.isAllowedDocument("https://dev.munitter.com:443/home"))
        assertFalse(policy.isAllowedDocument("http://dev.munitter.com/home"))
        assertFalse(policy.isAllowedDocument("https://sub.dev.munitter.com/home"))
        assertFalse(policy.isAllowedDocument("https://user@dev.munitter.com/home"))
        assertFalse(policy.isAllowedDocument("https://dev.munitter.com:444/home"))
    }

    @Test
    fun `script uses the native setter and quotes payload as data`() {
        val script = DeviceScreenGeometryDeliveryScriptBuilder(
            "https://dev.munitter.com",
        ).build("""{"source":"value'\\\"</script>"}""")

        assertTrue(script.contains("window.location.origin !== \"https://dev.munitter.com\""))
        assertTrue(script.contains("receiver.setNativeGeometry(payload) === true"))
        assertTrue(script.contains("window.__munitterPendingDeviceScreenGeometry = payload"))
        assertTrue(script.contains("if (!accepted)"))
        assertTrue(script.contains("JSON.parse("))
        assertFalse(script.contains("const payload = {\"source\""))
    }

    @Test
    fun `delivery state deduplicates a document but permits a forced new document`() {
        val state = DeviceScreenGeometryDeliveryState()

        assertTrue(state.begin("document-a", force = false))
        assertFalse(state.begin("document-a", force = false))
        state.finish("document-a", delivered = true)
        assertFalse(state.begin("document-a", force = false))
        assertTrue(state.begin("document-a", force = true))
        state.finish("document-a", delivered = true)
        state.resetDocument()
        assertTrue(state.begin("document-a", force = false))
    }
}
