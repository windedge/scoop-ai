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
 * the engine owns no install logic, the host does). [InstallAppArgs.bucket]
 * is optional: bucket+name installs from the saved bucket manifest, while a
 * null/blank bucket direct-installs the current manifest without saving it
 * to a bucket.
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
    fun `tool descriptor requires only name, bucket is optional`() {
        val tool = InstallAppTool { _, _ -> """{"success":true}""" }

        val descriptor = tool.descriptor
        assertEquals(1, descriptor.requiredParameters.size, "only name is required")
        assertTrue(
            descriptor.requiredParameters.any { it.name == "name" },
            "descriptor must expose the required name parameter",
        )
        assertTrue(
            descriptor.optionalParameters.any { it.name == "bucket" },
            "bucket must be optional so the model can omit it for direct install",
        )
    }

    // -----------------------------------------------------------------------
    // Execute: handler forwarding and bucket normalization
    // -----------------------------------------------------------------------

    @Test
    fun `execute forwards bucket and name to the host handler and returns its JSON verbatim`() = runBlocking {
        var calls = 0
        var lastBucket: String? = "sentinel"
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

    @Test
    fun `execute normalizes empty bucket to null for direct install`() = runBlocking {
        var lastBucket: String? = "sentinel"
        val tool = InstallAppTool { bucket, _ ->
            lastBucket = bucket
            """{"success":true}"""
        }

        tool.execute(InstallAppArgs(bucket = "", name = "fd"))

        assertEquals(null, lastBucket, "empty-string bucket must reach the handler as null")
    }

    @Test
    fun `execute normalizes whitespace-only bucket to null`() = runBlocking {
        var lastBucket: String? = "sentinel"
        val tool = InstallAppTool { bucket, _ ->
            lastBucket = bucket
            """{"success":true}"""
        }

        tool.execute(InstallAppArgs(bucket = "   ", name = "fd"))

        assertEquals(null, lastBucket, "whitespace-only bucket must reach the handler as null")
    }

    @Test
    fun `execute passes missing bucket through as null`() = runBlocking {
        var lastBucket: String? = "sentinel"
        val tool = InstallAppTool { bucket, _ ->
            lastBucket = bucket
            """{"success":true}"""
        }

        tool.execute(InstallAppArgs(name = "fd"))

        assertEquals(null, lastBucket, "omitted bucket must reach the handler as null")
    }

    // -----------------------------------------------------------------------
    // normalizeInstallBucket
    // -----------------------------------------------------------------------

    @Test
    fun `normalizeInstallBucket keeps and trims non-blank values`() {
        assertEquals("extras", InstallAppTool.normalizeInstallBucket("  extras  "))
    }

    @Test
    fun `normalizeInstallBucket maps null and blank inputs to null`() {
        assertEquals(null, InstallAppTool.normalizeInstallBucket(null))
        assertEquals(null, InstallAppTool.normalizeInstallBucket(""))
        assertEquals(null, InstallAppTool.normalizeInstallBucket(" \t "))
    }

    // -----------------------------------------------------------------------
    // Args serialization
    // -----------------------------------------------------------------------

    @Test
    fun `InstallAppArgs round-trips with a bucket`() {
        val json = Json.encodeToString(InstallAppArgs(bucket = "extras", name = "app"))
        val decoded = Json.decodeFromString<InstallAppArgs>(json)

        assertEquals(InstallAppArgs(bucket = "extras", name = "app"), decoded)
    }

    @Test
    fun `InstallAppArgs omits bucket by default and round-trips without it`() {
        val json = Json.encodeToString(InstallAppArgs(name = "app"))

        assertTrue(!json.contains("bucket"), "default bucket must not be encoded: $json")
        assertEquals(InstallAppArgs(bucket = null, name = "app"), Json.decodeFromString<InstallAppArgs>(json))
    }

    @Test
    fun `InstallAppArgs accepts explicit null bucket from the model`() {
        val decoded = Json.decodeFromString<InstallAppArgs>("""{"bucket":null,"name":"fd"}""")

        assertEquals(InstallAppArgs(bucket = null, name = "fd"), decoded)
    }

    @Test
    fun `InstallAppArgs keeps legacy required-bucket JSON decodable`() {
        val decoded = Json.decodeFromString<InstallAppArgs>("""{"bucket":"main","name":"fd"}""")

        assertEquals(InstallAppArgs(bucket = "main", name = "fd"), decoded)
    }

    @Test
    fun `InstallAppArgs still rejects missing name`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<InstallAppArgs>("{}")
        }
    }
}
