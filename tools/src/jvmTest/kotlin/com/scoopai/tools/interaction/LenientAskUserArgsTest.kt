package com.scoopai.tools.interaction

import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TDD coverage for lenient `__ask_user__` argument parsing.
 *
 * Models send malformed shapes observed in the field (flat `question`/`prompt`,
 * `questions` as object/string, invented keys like `isMultiSelect`, option objects
 * with `name`/`choice` keys). Canonical structured format must pass through
 * unchanged; unrecognizable input must still fail closed.
 */
class LenientAskUserArgsTest {

    private fun canon(json: String): JsonObject {
        val koog: JSONObject =
            Json.parseToJsonElement(json).let { it as JsonObject }.toKoogJSONObject()
        return canonicalizeAskUserArgs(koog).toKotlinxJsonObject()
    }

    // ---------------------------------------------------------------
    // canonical structured format: unchanged
    // ---------------------------------------------------------------

    @Test
    fun `canonical questions format passes through unchanged`() {
        val raw = """{"questions":[{"id":"q1","label":"Scope","prompt":"Which asset?","options":[{"label":"A","value":"a","description":"first"}],"allowOther":false}]}"""
        assertEquals(Json.parseToJsonElement(raw), canon(raw))
    }

    @Test
    fun `empty object passes through for default response`() {
        assertEquals(buildJsonObject { }, canon("{}"))
    }

    // ---------------------------------------------------------------
    // flat single-question formats: wrapped into questions array
    // ---------------------------------------------------------------

    @Test
    fun `flat question wraps into single question`() {
        val out = canon("""{"question":"Which asset?"}""")
        assertEquals("""{"questions":[{"prompt":"Which asset?"}]}""", out.toString())
    }

    @Test
    fun `root prompt key is accepted as single question`() {
        val out = canon("""{"prompt":"Which asset?"}""")
        assertEquals("""{"questions":[{"prompt":"Which asset?"}]}""", out.toString())
    }

    @Test
    fun `legacy message key is accepted as single question`() {
        val out = canon("""{"message":"Which asset?"}""")
        assertEquals("""{"questions":[{"prompt":"Which asset?"}]}""", out.toString())
    }

    @Test
    fun `flat string options carry over to the wrapped question`() {
        val out = canon("""{"question":"Which asset?","options":["a.zip","b.exe"],"allowOther":false}""")
        assertEquals(
            """{"questions":[{"prompt":"Which asset?","options":[{"label":"a.zip"},{"label":"b.exe"}],"allowOther":false}]}""",
            out.toString(),
        )
    }

    // ---------------------------------------------------------------
    // questions in wrong shape
    // ---------------------------------------------------------------

    @Test
    fun `questions as single object becomes one-element array`() {
        val out = canon("""{"questions":{"prompt":"Which asset?"}}""")
        assertEquals("""{"questions":[{"prompt":"Which asset?"}]}""", out.toString())
    }

    @Test
    fun `questions as string becomes single question`() {
        val out = canon("""{"questions":"Which asset?"}""")
        assertEquals("""{"questions":[{"prompt":"Which asset?"}]}""", out.toString())
    }

    // ---------------------------------------------------------------
    // question element key aliases and option shapes
    // ---------------------------------------------------------------

    @Test
    fun `nested question key maps to prompt`() {
        val out = canon("""{"questions":[{"question":"Which asset?"}]}""")
        assertEquals("""{"questions":[{"prompt":"Which asset?"}]}""", out.toString())
    }

    @Test
    fun `string options become label objects`() {
        val out = canon("""{"questions":[{"prompt":"Q","options":["a.zip","b.exe"]}]}""")
        assertEquals(
            """{"questions":[{"prompt":"Q","options":[{"label":"a.zip"},{"label":"b.exe"}]}]}""",
            out.toString(),
        )
    }

    @Test
    fun `object options with name or choice keys map to label`() {
        val out = canon(
            """{"questions":[{"prompt":"Q","options":[
               {"name":"A","value":"a"},
               {"choice":"B"},
               {"value":"c"},
               {"label":"D","description":"dee"}
            ]}]}""".replace("\n", "").replace(" ", ""),
        )
        assertEquals(
            """{"questions":[{"prompt":"Q","options":[{"label":"A","value":"a"},{"label":"B"},{"label":"c"},{"label":"D","description":"dee"}]}]}""",
            out.toString(),
        )
    }

    // ---------------------------------------------------------------
    // unknown keys are dropped, garbage fails closed
    // ---------------------------------------------------------------

    @Test
    fun `invented keys like isMultiSelect are dropped`() {
        val out = canon("""{"question":"Q","isMultiSelect":true}""")
        assertEquals("""{"questions":[{"prompt":"Q"}]}""", out.toString())
    }

    @Test
    fun `unrecognized only root keys fail closed`() {
        try {
            canon("""{"ask":"Which asset?"}""")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ask"))
        }
    }

    @Test
    fun `question element without any prompt text fails closed`() {
        try {
            canon("""{"questions":[{}]}""")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("prompt"))
        }
    }

    @Test
    fun `non object question element fails closed`() {
        try {
            canon("""{"questions":[42]}""")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    // ---------------------------------------------------------------
    // decodeArgs integration through the real Koog path
    // ---------------------------------------------------------------

    private val serializer = KotlinxSerializer()

    private fun decode(json: String): SafeAskUser.Args {
        val koogObject: JSONObject = Json.parseToJsonElement(json).jsonObject.toKoogJSONObject()
        return AskUserTool().decodeArgs(koogObject, serializer)
    }

    @Test
    fun `decodeArgs parses flat question with allowOther default true`() {
        val args = decode("""{"question":"Which asset?"}""")
        val qs = args.resolvedQuestions()
        assertEquals(1, qs.size)
        assertEquals("Which asset?", qs[0].prompt)
        assertTrue(qs[0].allowOther)
    }

    @Test
    fun `decodeArgs parses canonical structured format with full options`() {
        val args = decode(
            """{"questions":[{"id":"q1","label":"Scope","prompt":"Which asset?",
               "options":[{"label":"A","value":"a","description":"first"},{"label":"B"}],"allowOther":false}]}""",
        )
        val qs = args.resolvedQuestions()
        assertEquals(1, qs.size)
        assertEquals("q1", qs[0].id)
        assertEquals(2, qs[0].options.size)
        assertEquals("a", qs[0].options[0].resolvedValue())
        assertEquals(false, qs[0].allowOther)
    }

    @Test
    fun `decodeArgs drops isMultiSelect taught by outdated prompts`() {
        val args = decode("""{"question":"Q","options":["x"],"isMultiSelect":true}""")
        val qs = args.resolvedQuestions()
        assertEquals(1, qs.size)
        assertEquals(listOf("x"), qs[0].options.map { it.label })
    }

    @Test
    fun `decodeArgs accepts prompt key sent instead of question`() {
        val args = decode("""{"prompt":"Which license?"}""")
        assertEquals("Which license?", args.resolvedQuestions()[0].prompt)
    }

    @Test
    fun `decodeArgs accepts questions sent as bare string`() {
        val args = decode("""{"questions":"Which asset?"}""")
        assertEquals("Which asset?", args.resolvedQuestions()[0].prompt)
    }

    @Test
    fun `decodeArgs with empty args yields no questions`() {
        val args = decode("{}")
        assertTrue(args.resolvedQuestions().isEmpty())
    }

    @Test
    fun `decodeArgs fails closed on unrecognizable args`() {
        try {
            decode("""{"ask":"Q"}""")
            fail("expected failure")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("ask"))
        }
    }

    // ---------------------------------------------------------------
    // descriptor alignment guard
    // ---------------------------------------------------------------

    @Test
    fun `descriptor keeps canonical questions as the primary parameter`() {
        val descriptor = AskUserTool().descriptor
        val questions = descriptor.optionalParameters.firstOrNull { it.name == "questions" }
        assertNotNull(questions)
        assertIs<ai.koog.agents.core.tools.ToolParameterType.List>(questions.type)
    }
}
