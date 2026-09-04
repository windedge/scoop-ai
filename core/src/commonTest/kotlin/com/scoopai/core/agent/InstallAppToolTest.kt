package com.scoopai.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [InstallAppTool]: the SimpleTool wrapper that forwards
 * `install_app` calls to the host-provided install callback (architecture A:
 * the engine owns no install logic, the host does). Unlike save_to_bucket,
 * both parameters are required: the model installs a manifest that was
 * already saved under a concrete bucket and name.
 */
class InstallAppToolTest {

    // -----------------------------------------------------------------------
    // Schema name
    // -----------------------------------------------------------------------

    @Test
    fun `tool schema name is install_app`() {
        val tool = InstallAppTool { _, _ -> """{"success":true}""" }

        assertEquals("install_app", tool.name, "Koog schema name must be the snake_case install_app")
        assertEquals("install_app", InstallAppTool.TOOL_NAME)
    }

    @Test
    fun `tool descriptor requires bucket and name with no optional parameters`() {
        val tool = InstallAppTool { _, _ -> """{"success":true}""" }

        val descriptor = tool.descriptor
        assertEquals(2, descriptor.requiredParameters.size, "bucket and name are required, nothing else")
        assertTrue(
            descriptor.requiredParameters.any { it.name == "bucket" },
            "descriptor must expose the required bucket parameter",
        )
        assertTrue(
            descriptor.requiredParameters.any { it.name == "name" },
            "descriptor must expose the required name parameter",
        )
        assertTrue(
            descriptor.optionalParameters.isEmpty(),
            "install has no defaults: no parameter may be optional",
        )
    }

    // -----------------------------------------------------------------------
    // Execute: handler forwarding
    // -----------------------------------------------------------------------

    @Test
    fun `execute forwards bucket and name to the host handler and returns its JSON verbatim`() = runBlocking {
        var calls = 0
        var lastBucket: String? = null
        var lastName: String? = null
        val handlerJson = """{"success":true,"app":"fd","bucket":"main"}"""
        val tool = InstallAppTool { bucket, name ->
            calls++
            lastBucket = bucket
            lastName = name
            handlerJson
        }

        val result = tool.execute(InstallAppArgs(bucket = "main", name = "fd"))

        assertEquals(1, calls, "handler must be invoked exactly once")
        assertEquals("main", lastBucket, "handler must receive the bucket")
        assertEquals("fd", lastName, "handler must receive the name")
        assertEquals(handlerJson, result, "tool result must be the handler JSON verbatim")
    }

    // -----------------------------------------------------------------------
    // Args serialization
    // -----------------------------------------------------------------------

    @Test
    fun `InstallAppArgs round-trips all fields`() {
        val json = Json.encodeToString(InstallAppArgs(bucket = "extras", name = "app"))
        val decoded = Json.decodeFromString<InstallAppArgs>(json)

        assertEquals(InstallAppArgs(bucket = "extras", name = "app"), decoded)
    }

    @Test
    fun `InstallAppArgs rejects missing fields because install has no defaults`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<InstallAppArgs>("{}")
        }
    }
}
