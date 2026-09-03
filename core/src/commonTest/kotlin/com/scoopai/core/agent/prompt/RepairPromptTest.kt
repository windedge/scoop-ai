package com.scoopai.core.agent.prompt

import com.scoopai.core.AssistContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prompt tests for the REPAIR phase (internal fix loop, explanatory=false):
 * - repair prompt: no explanation guidance, no Question Escape Hatch / answered mention.
 */
class RepairPromptTest {

    private fun repairContext() = AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "Remove bin, keep shortcuts",
        retryCount = 1,
        explanatory = false,
    )

    @Test
    fun `buildRepairPrompt uses the repairer identity`() {
        val prompt = buildRepairPrompt(repairContext())

        assertTrue(
            prompt.contains("You are a Scoop manifest repairer."),
            "repair prompt must open with the repairer identity, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt omits the explanation guidance when explanatory is false`() {
        val prompt = buildRepairPrompt(repairContext())

        assertFalse(
            prompt.contains("explanation"),
            "internal repair rounds must not be asked for an explanation, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt does not mention answered or the escape hatch`() {
        val prompt = buildRepairPrompt(repairContext())

        assertFalse(
            prompt.contains("answered"),
            "repair prompt must not mention the `answered` parameter, got:\n$prompt",
        )
        assertFalse(
            prompt.contains("Question Escape Hatch"),
            "repair prompt must not contain the escape hatch section, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt keeps the shared kernel sections`() {
        val prompt = buildRepairPrompt(repairContext())

        assertTrue(
            prompt.contains("Name Conflict Resolution"),
            "repair prompt must keep the name-conflict section, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("#/dl.7z"),
            "repair prompt must keep the dl-7z extraction context, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt bans calling verify_install`() {
        val prompt = buildRepairPrompt(repairContext())

        assertTrue(
            prompt.contains("Do NOT call verify_install") || prompt.contains("Do NOT call `verify_install`"),
            "repair prompt must ban the verify_install tool, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("verify phase"),
            "repair prompt must say the verify phase owns install testing, got:\n$prompt",
        )
    }
}
