package com.scoopai.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.SystemPrompts

class ScoopManifestGenerator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolRegistry: ToolRegistry,
    private val maxIterations: Int = 30,
) {
    suspend fun generate(url: String, outputPath: String? = null): String {
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            systemPrompt = SystemPrompts.MANIFEST_GENERATOR,
            toolRegistry = toolRegistry,
            maxIterations = maxIterations,
        )
        val input = if (outputPath != null) "$url\nOutput file: $outputPath" else url
        return agent.run(input)
    }
}
