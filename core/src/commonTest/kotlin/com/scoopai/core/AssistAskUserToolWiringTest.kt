package com.scoopai.core

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the assist-subgraph `__ask_user__` wiring: the assist subgraph's
 * `tools` whitelist replaces the agent registry, so `__ask_user__` must be
 * added explicitly (alongside verify_install / save_to_bucket / install_app)
 * for a save without a bucket to be resolvable by asking the user.
 */
class AssistAskUserToolWiringTest {

    /** Minimal tool stub with a controllable Koog schema name. */
    private fun namedTool(name: String): Tool<String, String> = object : Tool<String, String>(
        argsType = typeToken<String>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(
            name = name,
            description = "test stub",
            requiredParameters = listOf(
                ToolParameterDescriptor(name = "result", description = "result", type = ToolParameterType.String),
            ),
        ),
    ) {
        override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): String = ""
        override fun encodeArgs(args: String, serializer: JSONSerializer): JSONObject =
            JSONObject(emptyMap())
        override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement =
            JSONLiteral(result, isString = true)
        override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): String = ""
        override suspend fun execute(args: String): String = args
    }

    // -----------------------------------------------------------------------
    // assistOnly gating
    // -----------------------------------------------------------------------

    @Test
    fun `assistOnly true picks the ask_user tool from the registry`() {
        val askUser = namedTool("__ask_user__")
        val result = assistAskUserTool(assistOnly = true, tools = listOf(askUser))

        assertNotNull(result, "an Assist session must wire __ask_user__ into the assist subgraph")
        assertEquals("__ask_user__", result.name)
    }

    @Test
    fun `assistOnly false never returns the ask_user tool`() {
        val askUser = namedTool("__ask_user__")
        val result = assistAskUserTool(assistOnly = false, tools = listOf(askUser))

        assertNull(result, "a Generate session's repair loop must not ask the user questions")
    }

    // -----------------------------------------------------------------------
    // Lookup and absence
    // -----------------------------------------------------------------------

    @Test
    fun `picks ask_user by name even when other tools come first`() {
        val askUser = namedTool("__ask_user__")
        val others = listOf(namedTool("verify_install"), namedTool("save_to_bucket"), askUser, namedTool("install_app"))

        assertEquals(
            askUser,
            assistAskUserTool(assistOnly = true, tools = others),
            "the lookup must match by the __ask_user__ schema name",
        )
    }

    @Test
    fun `registry without ask_user yields null so the tool is simply not wired`() {
        val result = assistAskUserTool(assistOnly = true, tools = listOf(namedTool("verify_install")))

        assertNull(result, "a host without an ask_user tool must degrade to no ask_user in the subgraph")
    }

    // -----------------------------------------------------------------------
    // Fold assembly: duplicate schema names across registries stay safe
    // -----------------------------------------------------------------------

    @Test
    fun `folding the ask_user tool into a registry that already has it keeps one instance`() {
        val askUser = namedTool("__ask_user__")
        val base = ToolRegistry { tool(askUser) }

        val merged = assistToolsFold(base, listOf(askUser))

        assertEquals(
            1,
            merged.tools.count { it.name == "__ask_user__" },
            "ToolRegistry.plus dedupes by name (first wins), so a duplicate ask_user is safe",
        )
        assertTrue(merged.tools.any { it.name == "__ask_user__" })
    }
}
