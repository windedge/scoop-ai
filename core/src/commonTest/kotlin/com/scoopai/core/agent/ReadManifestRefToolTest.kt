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
 * Unit tests for [ReadManifestRefTool]: the SimpleTool wrapper that forwards
 * `read_manifest_reference` calls to the host-provided read callback
 * (architecture A: the engine owns no local-manifest lookup, the host does).
 * Both [ReadManifestRefArgs.bucket] and [ReadManifestRefArgs.name] are required;
 * blank inputs never reach the handler and produce an error JSON instead.
 */
class ReadManifestRefToolTest {

    // -----------------------------------------------------------------------
    // Schema name
    // -----------------------------------------------------------------------

    @Test
    fun `tool schema name is read_manifest_reference`() {
        val tool = ReadManifestRefTool { _, _ -> """{"success":true}""" }

        assertEquals(
            "read_manifest_reference",
            tool.name,
            "Koog schema name must be the snake_case read_manifest_reference",
        )
        assertEquals("read_manifest_reference", ReadManifestRefTool.TOOL_NAME)
    }

    // -----------------------------------------------------------------------
    // Descriptor: both parameters required
    // -----------------------------------------------------------------------

    @Test
    fun `tool descriptor requires bucket and name`() {
        val tool = ReadManifestRefTool { _, _ -> """{"success":true}""" }

        val descriptor = tool.descriptor
        assertEquals(2, descriptor.requiredParameters.size, "bucket and name are both required")
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
            "the tool has no optional parameters",
        )
    }

    // -----------------------------------------------------------------------
    // Execute: handler forwarding and trimming
    // -----------------------------------------------------------------------

    @Test
    fun `execute forwards bucket and name to the host handler and returns its JSON verbatim`() = runBlocking {
        var calls = 0
        var lastBucket: String? = null
        var lastName: String? = null
        val handlerJson = """{"bucket":"main","name":"golang","path":"main/golang.json","content":"{}"}"""
        val tool = ReadManifestRefTool { bucket, name ->
            calls++
            lastBucket = bucket
            lastName = name
            handlerJson
        }

        val result = tool.execute(ReadManifestRefArgs(bucket = "main", name = "golang"))

        assertEquals(1, calls, "handler must be invoked exactly once")
        assertEquals("main", lastBucket, "handler must receive the bucket")
        assertEquals("golang", lastName, "handler must receive the name")
        assertEquals(handlerJson, result, "tool result must be the handler JSON verbatim")
    }

    @Test
    fun `execute trims bucket and name before forwarding`() = runBlocking {
        var lastBucket: String? = null
        var lastName: String? = null
        val tool = ReadManifestRefTool { bucket, name ->
            lastBucket = bucket
            lastName = name
            """{"success":true}"""
        }

        tool.execute(ReadManifestRefArgs(bucket = "  main  ", name = "\tgolang\n"))

        assertEquals("main", lastBucket, "bucket must be trimmed before reaching the handler")
        assertEquals("golang", lastName, "name must be trimmed before reaching the handler")
    }

    // -----------------------------------------------------------------------
    // Blank arguments: error JSON, handler never called
    // -----------------------------------------------------------------------

    @Test
    fun `execute returns error JSON without calling the handler when bucket is blank`() = runBlocking {
        var calls = 0
        val tool = ReadManifestRefTool { _, _ ->
            calls++
            """{"success":true}"""
        }

        val result = tool.execute(ReadManifestRefArgs(bucket = "   ", name = "golang"))

        assertEquals(0, calls, "a blank bucket must never reach the host handler")
        assertTrue(result.contains("\"success\":false"), "blank args must yield a failure JSON, got: $result")
        assertTrue(result.contains("error"), "failure JSON must carry an error message, got: $result")
    }

    @Test
    fun `execute returns error JSON without calling the handler when name is blank`() = runBlocking {
        var calls = 0
        val tool = ReadManifestRefTool { _, _ ->
            calls++
            """{"success":true}"""
        }

        val result = tool.execute(ReadManifestRefArgs(bucket = "main", name = ""))

        assertEquals(0, calls, "a blank name must never reach the host handler")
        assertTrue(result.contains("\"success\":false"), "blank args must yield a failure JSON, got: $result")
    }

    // -----------------------------------------------------------------------
    // Args serialization
    // -----------------------------------------------------------------------

    @Test
    fun `ReadManifestRefArgs round-trips`() {
        val json = Json.encodeToString(ReadManifestRefArgs(bucket = "extras", name = "app"))
        val decoded = Json.decodeFromString<ReadManifestRefArgs>(json)

        assertEquals(ReadManifestRefArgs(bucket = "extras", name = "app"), decoded)
    }

    @Test
    fun `ReadManifestRefArgs rejects missing bucket`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ReadManifestRefArgs>("""{"name":"fd"}""")
        }
    }

    @Test
    fun `ReadManifestRefArgs rejects missing name`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ReadManifestRefArgs>("""{"bucket":"main"}""")
        }
    }
}
