package com.scoopai.core.agent

import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SaveToBucketTool]: the SimpleTool wrapper that forwards
 * `save_to_bucket` calls to the host-provided save callback (architecture A:
 * the engine owns no save logic, the host does).
 */
class SaveToBucketToolTest {

    // -----------------------------------------------------------------------
    // Schema name
    // -----------------------------------------------------------------------

    @Test
    fun `tool schema name is save_to_bucket`() {
        val tool = SaveToBucketTool { """{"success":true}""" }

        assertEquals("save_to_bucket", tool.name, "Koog schema name must be the snake_case save_to_bucket")
        assertEquals("save_to_bucket", SaveToBucketTool.TOOL_NAME)
    }

    @Test
    fun `tool descriptor has no required parameters`() {
        val tool = SaveToBucketTool { """{"success":true}""" }

        val descriptor = tool.descriptor
        assertEquals(0, descriptor.requiredParameters.size, "bucket and name are optional, nothing is required")
        assertTrue(
            descriptor.optionalParameters.any { it.name == "bucket" },
            "descriptor must expose the optional bucket parameter",
        )
        assertTrue(
            descriptor.optionalParameters.any { it.name == "name" },
            "descriptor must expose the optional name parameter",
        )
        assertTrue(
            descriptor.optionalParameters.any { it.name == "overwrite" },
            "descriptor must expose the optional overwrite parameter",
        )
        assertEquals(
            ToolParameterType.Boolean,
            descriptor.optionalParameters.first { it.name == "overwrite" }.type,
            "overwrite must be a boolean parameter",
        )
    }

    // -----------------------------------------------------------------------
    // Execute: handler forwarding
    // -----------------------------------------------------------------------

    @Test
    fun `execute forwards args to the host handler and returns its JSON verbatim`() = runBlocking {
        var calls = 0
        var lastArgs: SaveToBucketArgs? = null
        val handlerJson = """{"success":true,"path":"C:/scoop/buckets/main/fd.json","bucket":"main","name":"fd"}"""
        val tool = SaveToBucketTool { args ->
            calls++
            lastArgs = args
            handlerJson
        }

        val result = tool.execute(SaveToBucketArgs(bucket = "main", name = "fd", overwrite = false))

        assertEquals(1, calls, "handler must be invoked exactly once")
        assertEquals(SaveToBucketArgs(bucket = "main", name = "fd", overwrite = false), lastArgs, "handler must receive the full args")
        assertEquals(handlerJson, result, "tool result must be the handler JSON verbatim")
    }

    @Test
    fun `execute passes null bucket and name through untouched`() = runBlocking {
        var lastArgs: SaveToBucketArgs? = null
        val tool = SaveToBucketTool { args ->
            lastArgs = args
            """{"success":false,"error":"No Scoop buckets found"}"""
        }

        val result = tool.execute(SaveToBucketArgs(bucket = null, name = null))

        assertNull(lastArgs?.bucket, "a null bucket must reach the host so it applies its default rules")
        assertNull(lastArgs?.name, "a null name must reach the host so it applies its default rules")
        assertTrue(result.contains("success"), "tool result must be the handler JSON verbatim")
    }

    // -----------------------------------------------------------------------
    // Args serialization
    // -----------------------------------------------------------------------

    @Test
    fun `SaveToBucketArgs round-trips all fields`() {
        val json = Json.encodeToString(SaveToBucketArgs(bucket = "extras", name = "app", overwrite = true))
        val decoded = Json.decodeFromString<SaveToBucketArgs>(json)

        assertEquals(SaveToBucketArgs(bucket = "extras", name = "app", overwrite = true), decoded)
    }

    @Test
    fun `SaveToBucketArgs decodes with all fields omitted`() {
        val decoded = Json.decodeFromString<SaveToBucketArgs>("{}")

        assertNull(decoded.bucket, "bucket must default to null so the host applies its default rules")
        assertNull(decoded.name, "name must default to null so the host applies its default rules")
        assertEquals(false, decoded.overwrite, "overwrite must default to false")
    }
}
