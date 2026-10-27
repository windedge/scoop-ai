package com.scoopai.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [AssistHost]: the no-op host facade for the assist subgraph's
 * host-side capabilities. The core module has no JVM interaction tools, so the
 * interface only covers the callbacks the strategy-facing layer consumes.
 */
class AssistHostTest {

    @Test
    fun `NoOp returns null for every capability`() {
        val host = AssistHost.NoOp
        assertNull(host.saveToBucket)
        assertNull(host.installApp)
        assertNull(host.readManifestRef)
    }

    @Test
    fun `a wired host exposes its handlers unchanged`() = kotlinx.coroutines.runBlocking {
        val host = AssistHost(
            saveToBucket = { _, _, _ -> "saved" },
            installApp = { _, _ -> "installed" },
            readManifestRef = { _, _ -> "read" },
        )
        assertEquals("saved", host.saveToBucket?.invoke(null, "app", true))
        assertEquals("installed", host.installApp?.invoke(null, "app"))
        assertEquals("read", host.readManifestRef?.invoke("main", "app"))
    }

    private fun assertNull(x: Any?) = assertEquals(null, x)
}
