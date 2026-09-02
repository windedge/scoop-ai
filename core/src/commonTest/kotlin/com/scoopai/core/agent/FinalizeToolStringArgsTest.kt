package com.scoopai.core.agent

import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONNull
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the P0 bug: `finalize_task_result` failed with
 * "Error index -1 out of bounds" in the generate/assist/repair phases whenever the
 * `result` parameter was passed as a JSON object (models stringify the manifest
 * object instead of escaping it into a string).
 *
 * The tests drive the exact Koog 1.0.0 execution path used by
 * `executeFinishTool`: the OpenAI client builds `MessagePart.Tool.Call` from the
 * raw `function.arguments` string, then the finish tool's `decodeArgs` runs on
 * `toolCall.argsJson.toKoogJSONObject()` with the default serializer.
 */
@OptIn(InternalAgentToolsApi::class)
class FinalizeToolStringArgsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = KotlinxSerializer()

    /** Mirrors AbstractOpenAILLMClient.toolCallPartsOrNull() building the Call from arguments. */
    private fun toolCallFrom(arguments: String): MessagePart.Tool.Call =
        MessagePart.Tool.Call(
            id = "call-1",
            tool = "finalize_task_result",
            args = Json.parseToJsonElement(arguments).jsonObject,
        )

    private val koogFinishTool = FinishTool<String>(typeToken<String>())
    private val lenientFinishTool = LenientStringFinishTool()

    private fun decodeWithKoog(arguments: String): String =
        koogFinishTool.decodeArgs(toolCallFrom(arguments).argsJson.toKoogJSONObject(), serializer)

    private fun decodeWithLenient(arguments: String): FinalizePayload =
        lenientFinishTool.decodeArgs(toolCallFrom(arguments).argsJson.toKoogJSONObject(), serializer)

    private val manifestJson =
        """{"version":"0.2.0","description":"PeekDown markdown viewer","url":"https://github.com/windedge/peekdown/releases/download/v0.2.0/peekdown-setup.exe","innosetup":true}"""

    /** Correctly escaped string form of {"result": "<manifest json>"}. */
    private val stringFormArguments: String =
        """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)}}"""

    // ---------------------------------------------------------------
    // String-form result (the intended form): both tools must succeed
    // ---------------------------------------------------------------

    @Test
    fun `koog finish tool string result with escaped quotes and braces succeeds`() {
        assertEquals(manifestJson, decodeWithKoog(stringFormArguments))
    }

    @Test
    fun `lenient finish tool string result with escaped quotes and braces succeeds`() {
        assertEquals(manifestJson, decodeWithLenient(stringFormArguments).manifest)
    }

    @Test
    fun `lenient finish tool plain text result still succeeds`() {
        assertEquals("test", decodeWithLenient("""{"result":"test"}""").manifest)
    }

    // ---------------------------------------------------------------
    // Object-form result (what models actually send): Koog 1.0.0
    // FinishTool crashes with IndexOutOfBoundsException; the lenient
    // replacement must recover the manifest JSON text as a string.
    // ---------------------------------------------------------------

    @Test
    fun `koog finish tool object form crashes with index error - root cause`() {
        val arguments = """{"result":{"version":"0.2.0","url":"https://example.com/x.exe"}}"""
        val outcome = runCatching { decodeWithKoog(arguments) }
        val error = outcome.exceptionOrNull()
        assertTrue(
            error is IndexOutOfBoundsException,
            "Koog FinishTool<String> must fail with IndexOutOfBoundsException on object-form result, got: $error",
        )
        assertTrue(
            error?.message?.contains("-1") == true,
            "the failure message must mention index -1, got: ${error?.message}",
        )
    }

    @Test
    fun `lenient finish tool object form recovers manifest json as string`() {
        val arguments = """{"result":{"version":"0.2.0","url":"https://example.com/x.exe"}}"""
        assertEquals(
            """{"version":"0.2.0","url":"https://example.com/x.exe"}""",
            decodeWithLenient(arguments).manifest,
        )
    }

    @Test
    fun `lenient finish tool empty object result returns empty json`() {
        assertEquals("{}", decodeWithLenient("""{"result":{}}""").manifest)
    }

    @Test
    fun `lenient finish tool null result returns empty string`() {
        assertEquals("", decodeWithLenient("""{"result":null}""").manifest)
    }

    // ---------------------------------------------------------------
    // Result encode/decode round trip used by the finalizeTask node
    // (toSafeResult -> decodeResult)
    // ---------------------------------------------------------------

    @Test
    fun `lenient finish tool result round trip preserves string`() {
        val encoded = lenientFinishTool.encodeResult(FinalizePayload(manifest = manifestJson), serializer)
        val decoded = lenientFinishTool.decodeResult(encoded, serializer)
        assertEquals(manifestJson, decoded.manifest)
        assertNull(decoded.explanation)
    }

    // ---------------------------------------------------------------
    // decodeResult safety: the result delivered by Koog's toSafeResult may
    // be a JSON string literal, null, number, or array (not always the
    // object form encodeResult produces). A blind `as JSONObject` cast
    // throws ClassCastException; the lenient tool must accept them all.
    // ---------------------------------------------------------------

    @Test
    fun `lenient finish tool decodeResult accepts JSONLiteral string without CCE`() {
        val literal = JSONLiteral(manifestJson, isString = true)
        val payload = lenientFinishTool.decodeResult(literal, serializer)
        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation)
    }

    @Test
    fun `lenient finish tool decodeResult accepts JSONLiteral plain text without CCE`() {
        val literal = JSONLiteral("manifest text", isString = true)
        assertEquals("manifest text", lenientFinishTool.decodeResult(literal, serializer).manifest)
    }

    @Test
    fun `lenient finish tool decodeResult accepts JSONNull without CCE`() {
        val payload = lenientFinishTool.decodeResult(JSONNull, serializer)
        assertEquals("", payload.manifest)
        assertNull(payload.explanation)
    }
}
