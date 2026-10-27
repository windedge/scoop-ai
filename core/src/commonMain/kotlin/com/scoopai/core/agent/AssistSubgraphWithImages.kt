package com.scoopai.core.agent

import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.environment.toSafeResult
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.SubgraphWithTaskUtils
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.AttachmentContent
import ai.koog.serialization.JSONObject
import ai.koog.serialization.TypeToken
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.serialization.typeToken
import kotlin.coroutines.cancellation.CancellationException
import com.scoopai.core.agent.FinalizePayload

/** Line separator used to join multiple text parts of an assistant reply. */
private const val NEWLINE: String = "\n"

/**
 * Builds [MessagePart.Attachment] list from a list of [BinaryImage]s.
 *
 * Each image becomes an [AttachmentSource.Image] with [AttachmentContent.Binary.Bytes]
 * content. The format is derived from the MIME type (e.g. "image/png" -> "png").
 *
 * Pure function, exported for testing.
 */
internal fun buildImageParts(images: List<BinaryImage>): List<MessagePart.Attachment> =
    images.map { img ->
        MessagePart.Attachment(
            AttachmentSource.Image(
                content = AttachmentContent.Binary.Bytes(img.bytes),
                format = img.mimeType.substringAfter('/'),
                mimeType = img.mimeType,
            )
        )
    }

/**
 * Vendored copy of Koog [ai.koog.agents.ext.agent.setupSubgraphWithTask] (1.1.1)
 * with two Scoop-AI changes. Part of the [com.scoopai.core.llm.KoogCompat]
 * compatibility family; kept in this file because it needs the internal graph
 * DSL surface (subgraph builder nodes/edges) that KoogCompat's llm package does
 * not import.
 *
 * Trigger: two gaps in Koog 1.1.1's subgraphWithTask:
 * 1. no way to attach binary image parts to the task message (assist sessions
 *    accept user screenshots); and
 * 2. a model answer that is plain text instead of a finish-tool call hard-throws
 *    when the model carries ToolChoice capability (the assist round must be able
 *    to answer pure questions conversationally).
 *
 * Changes over the original:
 * - first hop replaced to attach user images, attach gated by [shouldAttach] to
 *   keep repair rounds text-only (all other nodes: tool execution, finish,
 *   retry loop are unchanged from the original);
 * - text-only escape on the decide node: when created with
 *   [enableTextOnlyAnswer] (the user-facing assist round) and the model answers
 *   with plain text instead of calling the finish tool, the text is recovered as
 *   [FinalizePayload.manifest] = "" with [FinalizePayload.explanation] = the
 *   reply; the caller's graph routes an empty manifest into its answer/escape
 *   path instead of normalize/verify.
 *
 * Retire when Koog's setupSubgraphWithTask grows an image-attach hook and a
 * text-only answer escape; then delete this copy and use the upstream function.
 *
 * Key change over the original: the first LLM call node is a custom node that appends
 * both the task text and image parts to the prompt when [shouldAttach] returns true
 * for the current input. All other nodes (tool execution, finish, retry loop) are
 * unchanged from the original.
 *
 * Second change over the original: a text-only escape on the decide node. When the
 * subgraph is created with [enableTextOnlyAnswer] (the user-facing assist round) and
 * the model answers with plain text instead of calling the finish tool, the text is
 * recovered as [FinalizePayload.manifest] = "" with [FinalizePayload.explanation] =
 * the reply. The caller's graph routes an empty manifest into its answer/escape path
 * instead of normalize/verify.
 */
@InternalAgentsApi
public fun <Input : Any, OutputTransformed : Any> AIAgentSubgraphBuilderBase<Input, OutputTransformed>.setupAssistSubgraphWithTask(
    finishTool: Tool<*, OutputTransformed>,
    inputType: TypeToken,
    outputTransformedType: TypeToken,
    parallelTools: Boolean,
    assistantResponseRepeatMax: Int? = null,
    images: List<BinaryImage> = emptyList(),
    shouldAttach: (Input) -> Boolean = { true },
    enableTextOnlyAnswer: Boolean = false,
    defineTask: suspend AIAgentGraphContextBase.(Input) -> String,
) {
    val originalToolsKey = createStorageKey<List<ai.koog.agents.core.tools.ToolDescriptor>>("all-available-tools")
    val askAssistantToFinishCounterKey = createStorageKey<Int>("ask-assistant-to-finish-counter")
    val attachKey = createStorageKey<Boolean>("assist_attach_images")
    val textOnlyAnswerKey = createStorageKey<Boolean>("assist_text_only_answer")

    val maxAssistantResponses = assistantResponseRepeatMax ?: SubgraphWithTaskUtils.ASSISTANT_RESPONSE_REPEAT_MAX

    val setupTask by node<Input, String>(inputType = inputType, outputType = typeToken<String>()) { input ->
        llm.writeSession {
            storage.set(originalToolsKey, tools)
            if (finishTool.descriptor !in tools) {
                this.tools += finishTool.descriptor
            }
            setToolChoiceRequired()
        }
        storage.set(attachKey, shouldAttach(input))
        storage.set(textOnlyAnswerKey, enableTextOnlyAnswer)
        defineTask(input)
    }

    val finalizeTask by node<ReceivedToolResults, OutputTransformed>(
        inputType = typeToken<ReceivedToolResult>(),
        outputType = outputTransformedType,
    ) {
        llm.writeSession {
            appendPrompt {
                user {
                    it.toolResults.forEach { toolResult ->
                        toolResult(toolResult.toMessagePart())
                    }
                }
            }
            tools = storage.get(originalToolsKey)!!
        }

        it.toolResults
            .first { it.tool == finishTool.name && it.resultKind is ToolResultKind.Success }
            .toSafeResult(finishTool, config.serializer)
            .asSuccessful()
            .result
    }

    val nodeDecide by node<Message.Assistant, Message.Assistant> { it }

    val nodeFirstCall by node<String, Message.Assistant> { task ->
        val attach = storage.get(attachKey) ?: false
        llm.writeSession {
            appendPrompt {
                if (attach && images.isNotEmpty()) {
                    user(buildList {
                        add(MessagePart.Text(task))
                        addAll(buildImageParts(images))
                    })
                } else {
                    user(task)
                }
            }
            requestLLM()
        }
    }

    val callToolsHacked by node<ToolCalls, ReceivedToolResults> { message ->
        val (finishToolCalls, regularToolCalls) = message.toolCalls.partition { it.tool == finishTool.name }

        val regularToolsResults = if (parallelTools) {
            environment.executeTools(regularToolCalls)
        } else {
            regularToolCalls.map { toolCall ->
                environment.executeTool(toolCall)
            }
        }

        val finishToolResult = finishToolCalls.firstOrNull()?.let { toolCall ->
            executeFinishTool(toolCall, finishTool)
        }

        ReceivedToolResults(
            buildList {
                finishToolResult?.let { add(it) }
                addAll(regularToolsResults)
            }
        )
    }

    @OptIn(ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI::class)
    val handleAssistantMessage by node<String, Message.Assistant> { response ->
        val textOnlyAnswer = storage.get(textOnlyAnswerKey) ?: false
        if (llm.model.supports(ai.koog.prompt.llm.LLMCapability.ToolChoice) && !textOnlyAnswer) {
            error(
                "Subgraph with task must always call tools, but no ${MessagePart.Tool.Call::class.simpleName} was generated, " + "got instead: $response"
            )
        }

        val currentAskAssistantToFinishCounter = storage.get(askAssistantToFinishCounterKey) ?: 1
        storage.set(askAssistantToFinishCounterKey, currentAskAssistantToFinishCounter + 1)

        if (currentAskAssistantToFinishCounter > maxAssistantResponses) {
            error(
                "Unable to finish subgraph with task. Reason: the model '${llm.model.id}' does not support tool choice, " + "and was not able to call `${finishTool.name}` tool after " + "<$maxAssistantResponses> attempts."
            )
        }

        llm.writeSession {
            appendPrompt {
                user(
                    "# DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD.\n" + "## IF YOU HAVE FINISHED, CALL `${finishTool.name}` TOOL!"
                )
            }
            requestLLM()
        }
    }

    val sendToolsResults by nodeLLMSendToolResults()

    nodeStart then setupTask
    edge(setupTask forwardTo nodeFirstCall)
    edge(nodeFirstCall forwardTo nodeDecide)

    edge(nodeDecide forwardTo callToolsHacked onToolCalls { true })
    // Text-only escape: when enabled and the model answers with plain text instead
    // of calling the finish tool, surface the text as a FinalizePayload with an
    // empty manifest and the reply as explanation. Intentionally fires only on the
    // first assistant response: the loop through handleAssistantMessage below keeps
    // the finish-tool requirement for subsequent turns.
    if (enableTextOnlyAnswer) {
        val textOnlyAnswerEscape by node<Message.Assistant, OutputTransformed>(
            inputType = typeToken<Message.Assistant>(),
            outputType = outputTransformedType,
        ) { message ->
            val enabled = storage.get(textOnlyAnswerKey) == true
            val text = message.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString(separator = NEWLINE) { it.text }
            if (!enabled || text.isBlank()) {
                error(
                    "Subgraph with task must always call tools, but no ${MessagePart.Tool.Call::class.simpleName} was generated, got instead: $message"
                )
            }
            @Suppress("UNCHECKED_CAST")
            FinalizePayload(manifest = "", explanation = text) as OutputTransformed
        }
        edge(nodeDecide forwardTo textOnlyAnswerEscape onCondition {
            it.parts.filterIsInstance<MessagePart.Text>().any { part -> part.text.isNotBlank() }
        })
        edge(textOnlyAnswerEscape forwardTo nodeFinish)
    }
    edge(nodeDecide forwardTo handleAssistantMessage onTextMessage { true })
    edge(handleAssistantMessage forwardTo nodeDecide)

    edge(
        nodeDecide forwardTo nodeFinish transformed {
            throw IllegalStateException(
                "Unhandled response from LLM. Subgraph with task must always call tools, " + "but no ${MessagePart.Tool.Call::class.simpleName} was generated, got instead: $it"
            )
        }
    )

    edge(
        callToolsHacked forwardTo finalizeTask
            onCondition {
                it.toolResults.any { toolResult ->
                    toolResult.tool == finishTool.name &&
                        toolResult.resultKind is ToolResultKind.Success
                }
            }
    )

    callToolsHacked then sendToolsResults then nodeDecide
    edge(finalizeTask forwardTo nodeFinish)
}

/**
 * Inlined copy of Koog's internal [ai.koog.agents.ext.agent.executeFinishTool] (1.1.1).
 * The original is `internal` and cannot be called from outside the Koog package.
 */
@InternalAgentsApi
internal suspend fun <Output, OutputTransformed> AIAgentContext.executeFinishTool(
    toolCall: MessagePart.Tool.Call,
    finishTool: Tool<Output, OutputTransformed>,
): ReceivedToolResult {
    val toolDescription = finishTool.descriptor.description
    val (encodedResult, resultObject) = try {
        val args = finishTool.decodeArgs(toolCall.argsJson.toKoogJSONObject(), config.serializer)
        val toolResult = finishTool.execute(args = args)
        finishTool.encodeResult(toolResult, config.serializer) to toolResult
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ReceivedToolResult(
            id = toolCall.id,
            tool = finishTool.name,
            toolArgs = runCatching { toolCall.argsJson.toKoogJSONObject() }.getOrElse { JSONObject(emptyMap()) },
            toolDescription = toolDescription,
            output = "Failed to execute '${finishTool.name}' with error: ${e.message}'",
            resultKind = ToolResultKind.Failure(e),
            result = null,
            resultObject = null,
        )
    }

    return ReceivedToolResult(
        id = toolCall.id,
        tool = finishTool.name,
        toolArgs = toolCall.argsJson.toKoogJSONObject(),
        output = toolCall.args,
        resultKind = ToolResultKind.Success,
        toolDescription = toolDescription,
        result = encodedResult,
        resultObject = resultObject,
    )
}
