package com.scoopai.core.agent

import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the finalize two-parameter protocol (architecture proposal 4.2):
 * `finalize_task_result(result, explanation?)` decodes into a [FinalizePayload].
 *
 * The lenient decoding of `result` (string verbatim / object-array serialized
 * back to text / number-boolean literal / null-missing as empty string) is the
 * historical P0 behavior and must not regress.
 */
@OptIn(InternalAgentToolsApi::class)
class LenientStringFinishToolTest {

    private val serializer: JSONSerializer = KotlinxSerializer()
    private val json = Json { ignoreUnknownKeys = true }
    private val tool = LenientStringFinishTool()

    /** Mirrors AbstractOpenAILLMClient building the tool call from raw arguments. */
    private fun decodeArgs(arguments: String): FinalizePayload =
        tool.decodeArgs(Json.parseToJsonElement(arguments).jsonObject.toKoogJSONObject(), serializer)

    private val manifestJson =
        """{"version":"0.2.0","description":"PeekDown markdown viewer","url":"https://example.com/peekdown-setup.exe","innosetup":true}"""

    // ---------------------------------------------------------------
    // Old format: result only (explanation absent)
    // ---------------------------------------------------------------

    @Test
    fun `result only as string decodes to payload with null explanation`() {
        val arguments = """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)}}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "old-format calls without explanation must decode explanation=null")
    }

    @Test
    fun `result only as object form recovers compact json text with null explanation`() {
        val arguments = """{"result":{"version":"0.2.0","url":"https://example.com/x.exe"}}"""

        val payload = decodeArgs(arguments)

        assertEquals("""{"version":"0.2.0","url":"https://example.com/x.exe"}""", payload.manifest)
        assertNull(payload.explanation)
    }

    // ---------------------------------------------------------------
    // New format: result + explanation two-parameter protocol
    // ---------------------------------------------------------------

    @Test
    fun `result and explanation decode into full payload`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},""" +
                """"explanation":"Added shortcuts because you asked for a start menu entry."}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertEquals("Added shortcuts because you asked for a start menu entry.", payload.explanation)
    }

    @Test
    fun `explanation with non-ascii text survives decoding`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},""" +
                """"explanation":"为你生成了 portable zip 清单，并添加了快捷方式。"}"""

        val payload = decodeArgs(arguments)

        assertEquals("为你生成了 portable zip 清单，并添加了快捷方式。", payload.explanation)
    }

    // ---------------------------------------------------------------
    // Missing / null result: empty manifest, explanation still parsed
    // ---------------------------------------------------------------

    @Test
    fun `missing result decodes to empty manifest and null explanation`() {
        val payload = decodeArgs("""{}""")

        assertEquals("", payload.manifest)
        assertNull(payload.explanation)
    }

    @Test
    fun `null result decodes to empty manifest and null explanation`() {
        val payload = decodeArgs("""{"result":null}""")

        assertEquals("", payload.manifest)
        assertNull(payload.explanation)
    }

    @Test
    fun `null result with present explanation keeps the explanation`() {
        val payload = decodeArgs("""{"result":null,"explanation":"Manifest was empty."}""")

        assertEquals("", payload.manifest)
        assertEquals("Manifest was empty.", payload.explanation)
    }

    // ---------------------------------------------------------------
    // Non-string explanation values decode to null: explanation is a
    // natural-language text parameter. An object/array/number/boolean
    // (model misuse) or an empty string must never become non-null text
    // that would overwrite a valid explanation from an earlier round.
    // ---------------------------------------------------------------

    @Test
    fun `explanation as json object decodes to null`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},""" +
                """"explanation":{"summary":"Added shortcuts"}}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "object explanation must decode to null, not stringified JSON text")
    }

    @Test
    fun `explanation as json array decodes to null`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},""" +
                """"explanation":["point one","point two"]}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "array explanation must decode to null, not stringified JSON text")
    }

    @Test
    fun `explanation as number decodes to null`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},"explanation":42}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "number explanation must decode to null, not the literal text")
    }

    @Test
    fun `explanation as boolean decodes to null`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},"explanation":true}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "boolean explanation must decode to null, not the literal text")
    }

    @Test
    fun `explanation as empty string decodes to null`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},"explanation":""}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "empty-string explanation must decode to null, not an empty string")
    }

    @Test
    fun `null explanation value decodes to null`() {
        val arguments =
            """{"result":${json.encodeToString(kotlinx.serialization.serializer<String>(), manifestJson)},"explanation":null}"""

        val payload = decodeArgs(arguments)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation, "explicit JSON null explanation must decode to null, not empty string")
    }

    // ---------------------------------------------------------------
    // encodeArgs / encodeResult / decodeResult adaptation
    // ---------------------------------------------------------------

    @Test
    fun `encode and decode args round trip preserves the full payload`() {
        val payload = FinalizePayload(manifest = manifestJson, explanation = "Used portable zip strategy.")

        val encoded = tool.encodeArgs(payload, serializer)
        val decoded = tool.decodeArgs(encoded, serializer)

        assertEquals(payload, decoded)
    }

    @Test
    fun `encode and decode result round trip preserves the full payload`() {
        val payload = FinalizePayload(manifest = manifestJson, explanation = null)

        val encoded = tool.encodeResult(payload, serializer)
        val decoded = tool.decodeResult(encoded, serializer)

        assertEquals(payload, decoded)
    }

    @Test
    fun `encodeArgs omits explanation entry when null`() {
        val encoded = tool.encodeArgs(FinalizePayload(manifest = manifestJson), serializer)

        assertNull(
            encoded.entries["explanation"],
            "null explanation must not be written to the wire format",
        )
    }

    // ---------------------------------------------------------------
    // decodeResult safety: Koog's toSafeResult may deliver a non-container
    // element (string literal, null, number, array). A blind container
    // extraction would throw; the tool must fall back to treating the
    // element itself as the manifest (historical IndexOutOfBounds regression).
    // ---------------------------------------------------------------

    @Test
    fun `decodeResult accepts JSONLiteral string element without CCE`() {
        val literal = JSONLiteral(manifestJson, isString = true)

        val payload = tool.decodeResult(literal, serializer)

        assertEquals(manifestJson, payload.manifest)
        assertNull(payload.explanation)
    }

    @Test
    fun `decodeResult accepts JSONNull element`() {
        val payload = tool.decodeResult(JSONNull, serializer)

        assertEquals("", payload.manifest)
        assertNull(payload.explanation)
    }

    // ---------------------------------------------------------------
    // FinalizePayload serialization (Koog graph state / storage requires it)
    // ---------------------------------------------------------------

    @Test
    fun `finalize payload serializes and deserializes`() {
        val payload = FinalizePayload(manifest = manifestJson, explanation = "Short summary.")

        val encoded = json.encodeToString(FinalizePayload.serializer(), payload)
        val decoded = json.decodeFromString(FinalizePayload.serializer(), encoded)

        assertEquals(payload, decoded)
    }

    @Test
    fun `finalize payload deserializes without explanation field`() {
        val decoded = json.decodeFromString(FinalizePayload.serializer(), """{"manifest":"{}"}""")

        assertEquals(FinalizePayload(manifest = "{}", explanation = null), decoded)
    }
}
