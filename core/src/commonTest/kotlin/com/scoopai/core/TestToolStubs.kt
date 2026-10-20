package com.scoopai.core

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken

/** Minimal tool stub with a controllable Koog schema name. */
internal fun namedTool(name: String): Tool<String, String> = object : Tool<String, String>(
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
