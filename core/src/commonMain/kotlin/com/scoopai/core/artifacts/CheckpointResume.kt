@file:OptIn(ExperimentalTime::class)

package com.scoopai.core.artifacts

import ai.koog.agents.core.agent.execution.path
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.SCOOP_MANIFEST_STRATEGY_NAME
import com.scoopai.core.agent.phaseJson
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Loads phase outputs from .scoopai/runs/<runId>/ artifacts directory
 * and constructs Koog checkpoint data for resuming generation from a specific stage.
 *
 * Usage:
 * ```
 * val resume = CheckpointResume(File(".scoopai/runs/abc123"))
 * val data = resume.loadResumePoint(ResumeStage.ANALYZE)
 * if (data != null) {
 *     generator.generate(url, resumeData = data)
 * }
 * ```
 *
 * @param runDir The directory containing phase JSON artifacts (research.json, analyze.json).
 */
class CheckpointResume(val runDir: File) {

    /**
     * Load phase outputs from the artifacts directory for the given stage.
     *
     * Returns null if a required phase output file is missing or corrupt.
     *
     * Stage requirements:
     * - [ResumeStage.ANALYZE]: needs research.json
     * - [ResumeStage.GENERATE]: needs research.json + analyze.json
     */
    suspend fun loadResumePoint(fromStage: ResumeStage): ResumeData? {
        val runId = runDir.name
        val researchFile = File(runDir, "research.json")
        val analyzeFile = File(runDir, "analyze.json")

        return try {
            when (fromStage) {
                ResumeStage.ANALYZE -> {
                    if (!researchFile.isFile) return null
                    val researchOutput = phaseJson.decodeFromString<ResearchOutput>(researchFile.readText())
                    ResumeData(
                        fromStage = fromStage,
                        runId = runId,
                        researchOutput = researchOutput,
                    )
                }
                ResumeStage.GENERATE -> {
                    if (!researchFile.isFile || !analyzeFile.isFile) return null
                    val researchOutput = phaseJson.decodeFromString<ResearchOutput>(researchFile.readText())
                    val analyzeOutput = phaseJson.decodeFromString<AnalyzeOutput>(analyzeFile.readText())
                    ResumeData(
                        fromStage = fromStage,
                        runId = runId,
                        researchOutput = researchOutput,
                        analyzeOutput = analyzeOutput,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stages we can resume from.
     */
    enum class ResumeStage {
        /** Skip research phase, start from analyze (needs research.json). */
        ANALYZE,
        /** Skip research + analyze phases, start from generate (needs both JSONs). */
        GENERATE,
    }
}

/**
 * Data loaded from artifacts for checkpoint resume.
 *
 * @property fromStage The stage to resume from.
 * @property runId The run identifier (directory name).
 * @property researchOutput Research output (always present).
 * @property analyzeOutput Analyze output (present only when [fromStage] is [CheckpointResume.ResumeStage.GENERATE]).
 */
data class ResumeData(
    val fromStage: CheckpointResume.ResumeStage,
    val runId: String,
    val researchOutput: ResearchOutput? = null,
    val analyzeOutput: AnalyzeOutput? = null,
) {
    companion object {
        /**
         * Builds a [ResumeData] from raw in-memory phase output JSON strings
         * (research / analyze), as delivered by [ScoopManifestGenerator.phaseOutputListener]
         * or captured from artifacts.
         *
         * The factory only consumes the JSON strings it actually needs for the
         * requested [fromStage]; any additional/ignored outputs (e.g. assist,
         * repair, generate) are simply not part of the signature and are discarded.
         *
         * Stage requirements:
         * - [CheckpointResume.ResumeStage.ANALYZE]: needs research
         * - [CheckpointResume.ResumeStage.GENERATE]: needs research + analyze
         *
         * Returns null when a required input is missing or fails to decode.
         *
         * @param fromStage The stage to resume from.
         * @param runId The run identifier.
         * @param researchJson Serialized [ResearchOutput] JSON, or null.
         * @param analyzeJson Serialized [AnalyzeOutput] JSON, or null.
         */
        fun fromPhaseOutputs(
            fromStage: CheckpointResume.ResumeStage,
            runId: String,
            researchJson: String?,
            analyzeJson: String?,
        ): ResumeData? {
            return try {
                when (fromStage) {
                    CheckpointResume.ResumeStage.ANALYZE -> {
                        val research = researchJson ?: return null
                        val researchOutput = phaseJson.decodeFromString<ResearchOutput>(research)
                        ResumeData(
                            fromStage = fromStage,
                            runId = runId,
                            researchOutput = researchOutput,
                        )
                    }
                    CheckpointResume.ResumeStage.GENERATE -> {
                        val research = researchJson ?: return null
                        val analyze = analyzeJson ?: return null
                        val researchOutput = phaseJson.decodeFromString<ResearchOutput>(research)
                        val analyzeOutput = phaseJson.decodeFromString<AnalyzeOutput>(analyze)
                        ResumeData(
                            fromStage = fromStage,
                            runId = runId,
                            researchOutput = researchOutput,
                            analyzeOutput = analyzeOutput,
                        )
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Convert this [ResumeData] into a Koog [AgentCheckpointData] for [Persistence.runFromCheckpoint].
     *
     * The checkpoint targets the finalizeTask node of the phase before [fromStage],
     * so Koog's graph engine skips all completed phases.
     *
     * @param url The original URL being generated.
     */
    fun toAgentCheckpointData(url: String): AgentCheckpointData {
        val time = Clock.System.now()

        val (lastOutput, storageMap) = when (fromStage) {
            CheckpointResume.ResumeStage.ANALYZE -> {
                val researchOutput = requireNotNull(researchOutput) { "researchOutput required for ANALYZE resume" }
                val json = serializeToKoogElement(researchOutput, ResearchOutput.serializer())
                Pair(
                    json,
                    emptyMap<String, JSONElement>(),
                )
            }
            CheckpointResume.ResumeStage.GENERATE -> {
                val analyzeOutput = requireNotNull(analyzeOutput) { "analyzeOutput required for GENERATE resume" }
                val researchOutput = requireNotNull(researchOutput) { "researchOutput required for GENERATE resume" }
                val json = serializeToKoogElement(analyzeOutput, AnalyzeOutput.serializer())
                // Pre-populate storage with research data so prepareManifest can read it
                val researchJson = serializeToKoogElement(researchOutput, ResearchOutput.serializer())
                Pair(
                    json,
                    mapOf(
                        "research_data" to researchJson,
                        "suggested_app_name" to JSONPrimitive(researchOutput.suggested_app_name ?: ""),
                    ),
                )
            }
        }

        val nodePath = path(runId, resumeNodePath(fromStage))

        return AgentCheckpointData(
            checkpointId = "resume-${fromStage.name.lowercase()}-${runId}",
            createdAt = time,
            messageHistory = listOf(
                Message.User(url, metaInfo = RequestMetaInfo(time)),
            ),
            version = 0,
            graphProperties = GraphCheckpointProperties(
                nodePath = nodePath,
                lastOutput = lastOutput,
            ),
            storage = if (storageMap.isEmpty()) null else JSONObject(storageMap),
        )
    }

    private fun <T> serializeToKoogElement(value: T, serializer: kotlinx.serialization.KSerializer<T>): JSONElement {
        val jsonStr = phaseJson.encodeToString(serializer, value)
        val jsonElement = Json.parseToJsonElement(jsonStr)
        return jsonElement.toKoogJSONElement()
    }
}

/**
 * Builds the graph node path (minus the leading run/session id segment) at which a
 * checkpoint for [stage] resumes execution. The path must name a node that actually
 * exists in the strategy Koog executes, otherwise resume fails with "Node ... not found".
 *
 * Semantics per stage:
 * - [CheckpointResume.ResumeStage.ANALYZE]: skip the research LLM phase and start from
 *   analyze. The checkpoint targets `<strategy>/research/finalizeTask`, the finish node
 *   inside the `research` subgraph; Koog re-runs that finish node with the saved research
 *   output and resumes from the following `analyze` subgraph.
 * - [CheckpointResume.ResumeStage.GENERATE]: skip research + analyze and start from
 *   generate. The checkpoint targets `<strategy>/analyze/finalizeTask`, the finish node
 *   inside the `analyze` subgraph; Koog resumes from the following `prepareManifest` node.
 *
 * Shape: `<strategyName>/<phaseSubgraph>/finalizeTask`. The first component is the
 * [SCOOP_MANIFEST_STRATEGY_NAME] the strategy registers under (single source of truth
 * shared with the strategy). The middle component is the property name of the
 * `subgraphWithTask` delegate (research / analyze), which doubles as the Koog subgraph
 * name because no explicit `name=` is passed (Koog falls back to the Kotlin property
 * name). The leaf is always `finalizeTask`: every `subgraphWithTask` unconditionally
 * declares a finish node with that name (Koog AIAgentSubgraphExt.setupSubgraphWithTask).
 *
 * Repair rounds run inside the same `assist` subgraph as user assists, and no resume
 * stage targets the assist/repair path, so a crash in the middle of a repair loop
 * cannot be checkpoint-resumed (accepted limitation).
 */
internal fun resumeNodePath(stage: CheckpointResume.ResumeStage): String = path(
    SCOOP_MANIFEST_STRATEGY_NAME,
    when (stage) {
        CheckpointResume.ResumeStage.ANALYZE -> "research"
        CheckpointResume.ResumeStage.GENERATE -> "analyze"
    },
    "finalizeTask",
)
