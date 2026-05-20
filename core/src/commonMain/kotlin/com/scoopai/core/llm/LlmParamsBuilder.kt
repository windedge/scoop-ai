package com.scoopai.core.llm

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds [LLMParams] based on the model's capabilities and the requested
 * thinking level.
 *
 * Extracted from [ScoopManifestGenerator][com.scoopai.core.ScoopManifestGenerator]
 * to isolate LLM parameter construction logic.
 */
object LlmParamsBuilder {

    /**
     * Builds LLM params appropriate for the given [model] and [thinkingLevel].
     *
     * If the model does not support [LLMCapability.Thinking], returns empty params.
     * Otherwise, configures thinking parameters based on the level string.
     */
    fun build(model: LLModel, thinkingLevel: String?): LLMParams {
        if (!model.supports(LLMCapability.Thinking)) {
            return LLMParams()
        }

        val additionalProps = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        when (thinkingLevel) {
            null -> {}
            "disabled" -> additionalProps["thinking"] =
                buildJsonObject { put("type", "disabled") }
            "enabled" -> additionalProps["thinking"] =
                buildJsonObject { put("type", "enabled") }
            "minimal", "low", "medium", "high", "xhigh" -> {
                additionalProps["thinking"] = buildJsonObject { put("type", "enabled") }
                additionalProps["reasoning_effort"] = JsonPrimitive(thinkingLevel)
            }
        }
        return if (additionalProps.isEmpty()) LLMParams()
        else LLMParams(additionalProperties = additionalProps)
    }
}
