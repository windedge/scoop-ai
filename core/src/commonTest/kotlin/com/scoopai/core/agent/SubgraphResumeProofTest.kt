package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.ToolSelectionStrategy
import ai.koog.agents.core.agent.execution.path
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.SubgraphWithTaskUtils
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.JSONPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * S5 batch 1: Proof test that Koog supports checkpoint resume from
 * a node inside [subgraphWithTask].
 *
 * Strategy: nodeStart -> phase1 (subgraphWithTask) -> phase2 (subgraphWithTask) -> nodeFinish
 *
 * The checkpoint targets phase1's "finalizeTask" node.
 * On resume, phase1's subgraph should complete immediately via the enforced execution point
 * (no LLM call), and phase2 should execute normally.
 *
 * If this passes, Koog fully supports resuming after subgraphWithTask nodes.
 */
@OptIn(ExperimentalTime::class, InternalAgentToolsApi::class)
class SubgraphResumeProofTest {

    @Test
    fun `resume after subgraphWithTask skips first phase and executes second`() = runBlocking {
        val sessionId = "proof-subgraph-resume"
        val time = Clock.System.now()

        // ---------------------------------------------------------------
        // 1. Build a strategy with two sequential subgraphWithTask phases
        // ---------------------------------------------------------------
        val strategy = strategy<String, String>("proof") {
            val phase1 by subgraphWithTask<String, String>(
                name = "phase1",
                toolSelectionStrategy = ToolSelectionStrategy.NONE,
            ) { input -> "Phase 1 task: $input" }

            val phase2 by subgraphWithTask<String, String>(
                name = "phase2",
                toolSelectionStrategy = ToolSelectionStrategy.NONE,
            ) { input -> "Phase 2 task: $input" }

            nodeStart then phase1 then phase2 then nodeFinish
        }

        // ---------------------------------------------------------------
        // 2. Custom executor that counts LLM calls
        //    Always returns finalize_task_result tool call.
        // ---------------------------------------------------------------
        val executor = object : PromptExecutor() {
            var callCount = 0

            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Message.Assistant {
                callCount++
                // Use a fixed result "done" regardless of call count,
                // since the only LLM call will be from phase2 (phase1 is skipped).
                return Message.Assistant(
                    part = MessagePart.Tool.Call(
                        id = callCount.toString(),
                        tool = SubgraphWithTaskUtils.FINALIZE_SUBGRAPH_TOOL_NAME,
                        args = """{"result":"done"}""",
                    ),
                    metaInfo = ResponseMetaInfo(time),
                )
            }

            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = emptyFlow()

            override suspend fun moderate(
                prompt: Prompt,
                model: LLModel
            ): ModerationResult = ModerationResult(
                isHarmful = false,
                categories = emptyMap()
            )

            override fun close() = Unit
        }

        // ---------------------------------------------------------------
        // 3. Construct checkpoint that resumes AFTER phase1's finalizeTask
        //    Node path: {sessionId}/proof/phase1/finalizeTask
        // ---------------------------------------------------------------
        val checkpoint = AgentCheckpointData(
            checkpointId = "cp-after-phase1",
            createdAt = time,
            messageHistory = listOf(
                Message.User("test input", metaInfo = RequestMetaInfo(time)),
            ),
            version = 0,
            graphProperties = GraphCheckpointProperties(
                nodePath = path(sessionId, "proof", "phase1", "finalizeTask"),
                lastOutput = JSONPrimitive("phase1-ok"),
            ),
        )

        // ---------------------------------------------------------------
        // 4. Create agent and resume from checkpoint
        // ---------------------------------------------------------------
        val agent = AIAgent(
            promptExecutor = executor,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") {
                    system("You are a test agent.")
                },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 30,
            ),
            toolRegistry = ToolRegistry.EMPTY,
        )

        val result = Persistence.runFromCheckpoint(
            agent = agent,
            input = "test input",
            checkpoint = checkpoint,
            sessionId = sessionId,
        )

        // ---------------------------------------------------------------
        // 5. Verify: phase1 skipped (no LLM call), only phase2 executed
        // ---------------------------------------------------------------
        assertEquals(
            "done",
            result,
            "Result should come from phase2 (phase1 skipped by checkpoint resume)"
        )
        assertEquals(
            1,
            executor.callCount,
            "Only phase2 should have triggered an LLM call; " +
                "phase1's subgraph should complete via forced execution point without LLM"
        )
    }
}
