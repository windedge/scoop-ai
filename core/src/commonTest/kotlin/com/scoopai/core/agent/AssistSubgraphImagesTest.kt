package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.agent.entity.ToolSelectionStrategy
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import com.scoopai.core.AssistContext
import com.scoopai.core.agent.FinalizePayload as Payload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)
class AssistSubgraphImagesTest {

    /** Minimal finalize payload with the required manifest field. */
    private fun payloadOf(manifest: String, explanation: String? = null) = Payload(manifest, explanation)

    @Test
    fun `attachImages helper builds parts with Bytes content and correct format`() {
        val images = listOf(
            BinaryImage(byteArrayOf(1, 2, 3), "image/png"),
            BinaryImage(byteArrayOf(4, 5, 6), "image/jpeg"),
        )

        val parts = buildImageParts(images)

        assertEquals(2, parts.size)

        val img1 = parts[0].source as AttachmentSource.Image
        assertTrue(img1.content is AttachmentContent.Binary.Bytes)
        assertEquals("png", img1.format)
        assertEquals("image/png", img1.mimeType)
        assertTrue((img1.content as AttachmentContent.Binary.Bytes).data.contentEquals(byteArrayOf(1, 2, 3)))

        val img2 = parts[1].source as AttachmentSource.Image
        assertTrue(img2.content is AttachmentContent.Binary.Bytes)
        assertEquals("jpeg", img2.format)
        assertEquals("image/jpeg", img2.mimeType)
        assertTrue((img2.content as AttachmentContent.Binary.Bytes).data.contentEquals(byteArrayOf(4, 5, 6)))
    }

    @Test
    fun `assistModelWithVision returns null for empty images`() {
        val model = DeepSeekModels.DeepSeekV4Flash

        assertNull(assistModelWithVision(model, emptyList()))
    }

    @Test
    fun `assistModelWithVision adds Vision Image capability and keeps ToolChoice stripped`() {
        // Simulate the agent-level model: ToolChoice already stripped at runtime.
        val base = DeepSeekModels.DeepSeekV4Flash.copy(
            capabilities = DeepSeekModels.DeepSeekV4Flash.capabilities?.filter { it != LLMCapability.ToolChoice },
        )

        val result = assistModelWithVision(base, listOf(BinaryImage(byteArrayOf(1), "image/png")))

        assertNotNull(result)
        assertTrue(
            result.capabilities?.contains(LLMCapability.Vision.Image) == true,
            "Vision.Image must be present when images are attached",
        )
        assertTrue(
            result.capabilities?.contains(LLMCapability.ToolChoice) != true,
            "ToolChoice must stay stripped on the vision-capable model",
        )
        assertEquals(base.id, result.id)
    }

    @Test
    fun `assistModelWithVision returns null for null model with images`() {
        assertNull(assistModelWithVision(null, listOf(BinaryImage(byteArrayOf(1), "image/png"))))
    }

    /** Capturing delegating executor: records each request and forwards to the mock. */
    private class RecordingPromptExecutor(
        private val delegate: PromptExecutor,
    ) : PromptExecutor() {
        val requests = mutableListOf<Pair<Prompt, LLModel>>()

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            requests.add(prompt to model)
            return delegate.execute(prompt, model, tools)
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            delegate.executeStreaming(prompt, model, tools)

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            delegate.moderate(prompt, model)

        override fun close() = delegate.close()
    }

    /** Runs the assist subgraph in isolation and returns the recorded executor. */
    private fun runAssistProbe(images: List<BinaryImage>, explanatory: Boolean): RecordingPromptExecutor {
        val executor = RecordingPromptExecutor(
            getMockExecutor {
                mockLLMToolCall(LenientStringFinishTool(), payloadOf("{}")) onRequestContains "manifest assistant task"
            },
        )

        val visionModel = assistModelWithVision(DeepSeekModels.DeepSeekV4Flash, images)

        val strategy = strategy<AssistContext, Payload>("assist_images_probe") {
            val assist by subgraph<AssistContext, Payload>(
                name = "assist",
                toolSelectionStrategy = ToolSelectionStrategy.Tools(emptyList()),
                llmModel = visionModel,
            ) {
                setupAssistSubgraphWithTask(
                    finishTool = LenientStringFinishTool(),
                    inputType = typeToken<AssistContext>(),
                    outputTransformedType = typeToken<Payload>(),
                    parallelTools = false,
                    images = images,
                    shouldAttach = { it.explanatory },
                ) { _ -> "manifest assistant task" }
            }
            nodeStart then assist then nodeFinish
        }

        val agent = AIAgent(
            promptExecutor = executor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = ai.koog.prompt.dsl.prompt("test") { system("test") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 10,
            ),
        ) {
            withTesting()
        }

        runBlocking {
            agent.run(AssistContext(manifest = "{}", feedback = "test", explanatory = explanatory))
        }
        return executor
    }

    @Test
    fun `assist with images attaches parts to first user message`() {
        val images = listOf(
            BinaryImage(byteArrayOf(1, 2, 3), "image/png"),
            BinaryImage(byteArrayOf(4, 5, 6), "image/jpeg"),
        )

        val executor = runAssistProbe(images, explanatory = true)
        assertTrue(executor.requests.isNotEmpty(), "The LLM should have been called at least once")

        val firstCall = executor.requests.first()
        val userMsg = firstCall.first.messages.filterIsInstance<Message.User>().first()
        val textParts = userMsg.parts.filterIsInstance<MessagePart.Text>()
        val attachmentParts = userMsg.parts.filterIsInstance<MessagePart.Attachment>()

        assertTrue(textParts.isNotEmpty(), "Should have at least one text part")
        assertEquals(images.size, attachmentParts.size, "Should have one attachment part per image")

        val attachmentSources = attachmentParts.map { it.source }
        attachmentSources.forEachIndexed { index, source ->
            val img = source as AttachmentSource.Image
            assertTrue(img.content is AttachmentContent.Binary.Bytes, "Attachment content must be Bytes")
            val expectedFormat = images[index].mimeType.substringAfter('/')
            assertEquals(expectedFormat, img.format)
            assertEquals(images[index].mimeType, img.mimeType)
            assertTrue(
                (img.content as AttachmentContent.Binary.Bytes).data.contentEquals(images[index].bytes),
                "Attachment bytes must match the source image",
            )
        }
    }

    @Test
    fun `assist without images or with explanatory false sends text only user message`() {
        // explanatory = true but no images: the attach branch is skipped.
        val noImages = runAssistProbe(emptyList(), explanatory = true)
        val userNoImages = noImages.requests.first().first.messages.filterIsInstance<Message.User>().first()
        assertTrue(
            userNoImages.parts.none { it is MessagePart.Attachment },
            "No attachments expected when there are no images",
        )

        // Images present but explanatory = false: shouldAttach keeps the message text-only.
        val repairRound = runAssistProbe(
            listOf(BinaryImage(byteArrayOf(1, 2, 3), "image/png")),
            explanatory = false,
        )
        val userRepair = repairRound.requests.first().first.messages.filterIsInstance<Message.User>().first()
        assertTrue(
            userRepair.parts.none { it is MessagePart.Attachment },
            "Repair rounds must stay text-only even when images are provided",
        )
    }
}
