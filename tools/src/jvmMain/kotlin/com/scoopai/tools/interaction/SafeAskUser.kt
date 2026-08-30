package com.scoopai.tools.interaction

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

@Serializable
data class QuestionOption(
    @LLMDescription("Display label for the option")
    val label: String,
    @LLMDescription("The value returned when selected. If omitted, defaults to label")
    val value: String? = null,
    @LLMDescription("Optional description shown below label")
    val description: String? = null,
) {
    fun resolvedValue(): String = value?.ifBlank { null } ?: label
}

@Serializable
data class Question(
    @LLMDescription("Unique identifier for this question")
    val id: String = UUID.randomUUID().toString(),
    @LLMDescription("Short contextual label for tab bar, e.g. 'Scope', 'Architecture' (defaults to Q1, Q2)")
    val label: String? = null,
    @LLMDescription("The full question text to display")
    val prompt: String,
    @LLMDescription("Available options to choose from")
    val options: List<QuestionOption> = emptyList(),
    @LLMDescription("Allow 'Type something / Other' write-in option (default: true)")
    val allowOther: Boolean = true,
)

@Serializable
data class Answer(
    val id: String,
    val value: String,
    val label: String,
    val wasCustom: Boolean = false,
    val index: Int? = null,
)

/**
 * Safe replacement for Koog's built-in AskUser tool (`__ask_user__`) implementing
 * the questionnaire pattern (mirroring pi-coding-agent questionnaire).
 */
@LLMDescription("Ask the user one or more questions (questionnaire format). Single question shows option list; multiple questions show tabbed interface. Supports selectable options and write-in input.")
object SafeAskUser : SimpleTool<SafeAskUser.Args>(
    typeToken<Args>(),
    "__ask_user__",
    "Ask the user one or more questions with options and get their answers"
) {

    @Serializable
    @LLMDescription("Arguments for questionnaire questions")
    data class Args(
        @LLMDescription("List of questions to ask the user")
        val questions: List<Question> = emptyList(),
        @LLMDescription("Legacy/shorthand field for single question prompt")
        val question: String? = null,
        @LLMDescription("Legacy field for question text")
        val message: String? = null,
        @LLMDescription("Legacy/shorthand list of simple option labels")
        val options: List<String> = emptyList(),
        @LLMDescription("Whether to allow write-in option (default: true)")
        val allowOther: Boolean = true,
    ) {
        fun resolvedQuestions(): List<Question> {
            if (questions.isNotEmpty()) {
                return questions.mapIndexed { idx, q ->
                    q.copy(
                        id = q.id.ifBlank { "q_${idx + 1}" },
                        label = q.label?.ifBlank { null } ?: "Q${idx + 1}",
                    )
                }
            }
            val promptText = question?.ifBlank { null } ?: message?.ifBlank { null } ?: ""
            if (promptText.isBlank()) return emptyList()
            val questionOptions = options.map { opt ->
                QuestionOption(label = opt, value = opt)
            }
            return listOf(
                Question(
                    id = "q_1",
                    label = "Question",
                    prompt = promptText,
                    options = questionOptions,
                    allowOther = allowOther,
                )
            )
        }
    }

    internal const val DEFAULT_RESPONSE = "Please make your best judgment and proceed autonomously."

    /**
     * When non-null, overrides property / env for non-interactive mode.
     * Set by [com.scoopai.tools.DefaultToolRegistryFactory] (or hosts directly).
     */
    @Volatile
    var nonInteractiveOverride: Boolean? = null

    /**
     * Questionnaire suspend handler receiving the normalized list of [Question]s.
     * Must return the formatted answer string (or JSON / answer lines).
     */
    @Volatile
    var questionnaireHandler: (suspend (questions: List<Question>) -> String)? = null

    /**
     * Legacy simple string suspend handler.
     */
    @Volatile
    var customHandler: (suspend (String) -> String)? = null

    fun isNonInteractive(): Boolean {
        nonInteractiveOverride?.let { return it }
        val flag = System.getProperty("SCOOP_AI_NON_INTERACTIVE")
            ?: System.getenv("SCOOP_AI_NON_INTERACTIVE")
            ?: return false
        return flag.isNotBlank() && flag != "false" && flag != "0"
    }

    private fun isStdinAvailable(): Boolean = try {
        java.lang.System.`in`.available() >= 0
    } catch (_: Exception) {
        false
    }

    override suspend fun execute(args: Args): String {
        val qs = args.resolvedQuestions()
        if (qs.isEmpty()) return DEFAULT_RESPONSE

        questionnaireHandler?.let { handler ->
            return handler.invoke(qs)
        }

        customHandler?.let { handler ->
            return handler.invoke(qs.firstOrNull()?.prompt ?: "")
        }

        if (isNonInteractive()) return DEFAULT_RESPONSE

        // Console / Stdin interactive execution
        val answers = mutableListOf<Answer>()
        val console = java.lang.System.console()
        val isConsole = console != null
        val reader = if (!isConsole && isStdinAvailable()) BufferedReader(InputStreamReader(java.lang.System.`in`)) else null

        for (q in qs) {
            val prompt = buildString {
                appendLine(q.prompt)
                q.options.forEachIndexed { i, opt ->
                    append("  ${i + 1}) ${opt.label}")
                    if (!opt.description.isNullOrBlank()) {
                        append(" - ${opt.description}")
                    }
                    appendLine()
                }
                if (q.allowOther) {
                    appendLine("  ${q.options.size + 1}) Other / Type custom answer")
                }
                append("> ")
            }

            if (isConsole) {
                console.printf("%s", prompt)
                val line = console.readLine()?.trim()
                if (line.isNullOrBlank()) {
                    answers.add(Answer(id = q.id, value = DEFAULT_RESPONSE, label = DEFAULT_RESPONSE))
                } else {
                    val index = line.toIntOrNull()
                    if (index != null && index in 1..q.options.size) {
                        val opt = q.options[index - 1]
                        answers.add(Answer(id = q.id, value = opt.resolvedValue(), label = opt.label, wasCustom = false, index = index))
                    } else if (index == q.options.size + 1 && q.allowOther) {
                        console.printf("Your answer > ")
                        val custom = console.readLine()?.trim().orEmpty().ifBlank { DEFAULT_RESPONSE }
                        answers.add(Answer(id = q.id, value = custom, label = custom, wasCustom = true))
                    } else {
                        answers.add(Answer(id = q.id, value = line, label = line, wasCustom = true))
                    }
                }
            } else if (reader != null) {
                java.lang.System.out.print(prompt)
                java.lang.System.out.flush()
                val line = reader.readLine()?.trim()
                if (line.isNullOrBlank()) {
                    answers.add(Answer(id = q.id, value = DEFAULT_RESPONSE, label = DEFAULT_RESPONSE))
                } else {
                    val index = line.toIntOrNull()
                    if (index != null && index in 1..q.options.size) {
                        val opt = q.options[index - 1]
                        answers.add(Answer(id = q.id, value = opt.resolvedValue(), label = opt.label, wasCustom = false, index = index))
                    } else if (index == q.options.size + 1 && q.allowOther) {
                        java.lang.System.out.print("Your answer > ")
                        java.lang.System.out.flush()
                        val custom = reader.readLine()?.trim().orEmpty().ifBlank { DEFAULT_RESPONSE }
                        answers.add(Answer(id = q.id, value = custom, label = custom, wasCustom = true))
                    } else {
                        answers.add(Answer(id = q.id, value = line, label = line, wasCustom = true))
                    }
                }
            } else {
                return DEFAULT_RESPONSE
            }
        }

        return answers.joinToString("\n") { a ->
            val qLabel = qs.find { it.id == a.id }?.label ?: a.id
            if (a.wasCustom) {
                "$qLabel: user wrote: ${a.label}"
            } else {
                val indexPrefix = if (a.index != null) "${a.index}. " else ""
                "$qLabel: user selected: $indexPrefix${a.label}"
            }
        }
    }
}
