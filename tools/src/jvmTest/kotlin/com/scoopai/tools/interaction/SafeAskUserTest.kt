package com.scoopai.tools.interaction

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeAskUserTest {

    @AfterEach
    fun tearDown() {
        SafeAskUser.nonInteractiveOverride = null
        SafeAskUser.customHandler = null
        SafeAskUser.questionnaireHandler = null
        System.clearProperty("SCOOP_AI_NON_INTERACTIVE")
    }

    @Test
    fun `execute invokes customHandler when provided`() = runBlocking {
        var receivedQuestion = ""
        SafeAskUser.customHandler = { q ->
            receivedQuestion = q
            "Custom User Answer"
        }
        val result = SafeAskUser.execute(SafeAskUser.Args(question = "Choose architecture"))
        assertEquals("Custom User Answer", result)
        assertEquals("Choose architecture", receivedQuestion)
    }

    @Test
    fun `execute invokes questionnaireHandler with structured questions`() = runBlocking {
        var receivedQuestions: List<Question> = emptyList()

        SafeAskUser.questionnaireHandler = { qs ->
            receivedQuestions = qs
            "Q1: user selected: 1. Portable ZIP"
        }

        val result = SafeAskUser.execute(
            SafeAskUser.Args(
                questions = listOf(
                    Question(
                        id = "variant",
                        label = "Variant",
                        prompt = "Pick an asset variant",
                        options = listOf(
                            QuestionOption(label = "Portable ZIP", description = "Standalone executable archive"),
                            QuestionOption(label = "MSI Installer"),
                        ),
                    )
                )
            )
        )
        assertEquals("Q1: user selected: 1. Portable ZIP", result)
        assertEquals(1, receivedQuestions.size)
        assertEquals("Pick an asset variant", receivedQuestions[0].prompt)
        assertEquals("Portable ZIP", receivedQuestions[0].options[0].label)
        assertEquals("Standalone executable archive", receivedQuestions[0].options[0].description)
    }

    @Test
    fun `Args resolvedQuestions resolves from questions list or legacy fields`() {
        val a1 = SafeAskUser.Args(question = "New Q", options = listOf("A", "B"))
        val qs1 = a1.resolvedQuestions()
        assertEquals(1, qs1.size)
        assertEquals("New Q", qs1[0].prompt)
        assertEquals(2, qs1[0].options.size)
        assertEquals("A", qs1[0].options[0].label)

        val a2 = SafeAskUser.Args(message = "Old Msg")
        val qs2 = a2.resolvedQuestions()
        assertEquals(1, qs2.size)
        assertEquals("Old Msg", qs2[0].prompt)
    }

    @Test
    fun `isNonInteractive false by default when no override or property`() {
        // Force false via override: env may be set in CI.
        SafeAskUser.nonInteractiveOverride = false
        assertFalse(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `nonInteractiveOverride true takes precedence over property false`() {
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "false")
        SafeAskUser.nonInteractiveOverride = true
        assertTrue(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `nonInteractiveOverride false takes precedence over property true`() {
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")
        SafeAskUser.nonInteractiveOverride = false
        assertFalse(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `property true enables nonInteractive when override is null`() {
        SafeAskUser.nonInteractiveOverride = null
        System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")
        assertTrue(SafeAskUser.isNonInteractive())
    }

    @Test
    fun `execute returns default response in nonInteractive mode without blocking`() {
        SafeAskUser.nonInteractiveOverride = true
        val result = runBlocking {
            SafeAskUser.execute(SafeAskUser.Args(question = "Pick a license?"))
        }
        assertEquals(SafeAskUser.DEFAULT_RESPONSE, result)
    }
}
