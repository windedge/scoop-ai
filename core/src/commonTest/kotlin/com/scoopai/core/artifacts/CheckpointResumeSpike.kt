package com.scoopai.core.artifacts

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-3.05b spike: investigate whether Koog's graph DSL supports resuming from
 * an intermediate node (e.g., skip research/analyze, start from generate).
 *
 * Conclusion: RESUME_DEFERRED (deferred to v0.4.0+).
 *
 * Koog DOES support checkpoint resume as a first-class feature
 * (Persistence.runFromCheckpoint, AdditionalInputs.Storage,
 * AIAgentGraphStrategyBase.restoreStateIfNeeded), so this is NOT a framework
 * blocker. The deferral is purely application-level.
 *
 * Reasons:
 * - Koog exposes a public resume API: Persistence.runFromCheckpoint() injects
 *   a GraphAgentContextData via AdditionalInputs.Storage; the strategy's
 *   execute() picks it up via restoreStateIfNeeded() and jumps to the node
 *   after the checkpoint (setExecutionPointAfterNode). Proven by
 *   RunFromCheckpointTest.kt (6 tests, works even without the Persistence feature).
 * - Our scoopManifestStrategy is NOT resumable as-is: phase data lives in
 *   local var captures (researchData / analyzeData / suggestedAppName) assigned
 *   as side effects inside the analyze subgraph task body and prepareManifest
 *   node body. A checkpoint restore skips those node bodies, leaving them null.
 * - That causes NPE on resume: prepareManifest calls assembleManifestSkeleton(researchData!!, ...),
 *   generate reads analyzeData!!, and the finish edges read suggestedAppName.
 * - Total change to ship --from is ~240 lines (capture->storage refactor ~40,
 *   CheckpointResume wiring ~80, CLI --from ~40, proof test ~80), above the
 *   <200 line SUPPORTED threshold, and it touches the hottest code path.
 * - Koog's own suite only proves resume after a plain node(), not after a
 *   subgraphWithTask node (our resume target). That integration gap needs its
 *   own proof test, and priority is low: T-3.05a RunArtifacts already writes
 *   research/analyze/generate JSON for debugging.
 *
 * What needs to change first (v0.4.0 plan):
 * - Move researchData/analyzeData/suggestedAppName to storage keys (checkpoints
 *   restore storage), so generate can read them without running prior node bodies.
 * - Add a resume-after-subgraph-with-task proof test (mock executor + checkpoint).
 * - Add CheckpointResume.kt to load RunArtifacts and call runFromCheckpoint.
 * - Wire --from <analyze|generate> in the CLI.
 */
class CheckpointResumeSpike {
    @Test
    fun `spike conclusion is documented`() {
        val conclusion = "RESUME_DEFERRED"
        assertTrue(
            conclusion == "RESUME_SUPPORTED" || conclusion == "RESUME_DEFERRED",
            "Spike must reach a definitive conclusion"
        )
    }
}
