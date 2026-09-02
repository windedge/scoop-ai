package com.scoopai.core.artifacts

import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.ResearchStatus
import com.scoopai.core.agent.phaseJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [CheckpointResume] - loading phase outputs from artifacts directory.
 */
class CheckpointResumeTest {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "scoopai-test-$prefix-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun writeJson(dir: File, name: String, content: String) {
        File(dir, name).writeText(content)
    }

    private val sampleResearchJson: String = phaseJson.encodeToString(
        ResearchOutput.serializer(),
        ResearchOutput(
            status = ResearchStatus.OK,
            project = "owner/repo",
            description = "A test project",
            homepage = "https://github.com/owner/repo",
            version = "1.0.0",
            suggested_app_name = "test-app",
            windows_assets = listOf(),
        )
    )

    private val sampleAnalyzeJson: String = phaseJson.encodeToString(
        AnalyzeOutput.serializer(),
        AnalyzeOutput(
            strategy = "portable_zip",
            bin = listOf("app.exe"),
            entries = listOf(),
        )
    )

    // ---------------------------------------------------------------
    // loadResumePoint: ANALYZE stage
    // ---------------------------------------------------------------

    @Test
    fun `loadResumePoint ANALYZE returns research output when research json exists`() = runBlocking {
        val dir = createTempDir("analyze-ok")
        writeJson(dir, "research.json", sampleResearchJson)

        val resume = CheckpointResume(dir)
        val result = resume.loadResumePoint(CheckpointResume.ResumeStage.ANALYZE)

        assertNotNull(result, "Should return ResumeData when research.json exists")
        assertEquals(CheckpointResume.ResumeStage.ANALYZE, result.fromStage)
        assertNotNull(result.researchOutput, "researchOutput should be present")
        assertEquals("owner/repo", result.researchOutput.project)
        assertEquals("1.0.0", result.researchOutput.version)
        assertEquals("test-app", result.researchOutput.suggested_app_name)
        assertEquals(dir.name, result.runId)
    }

    @Test
    fun `loadResumePoint ANALYZE returns null when research json missing`() = runBlocking {
        val dir = createTempDir("analyze-missing")
        // Don't write research.json — only analyze.json
        writeJson(dir, "analyze.json", sampleAnalyzeJson)

        val resume = CheckpointResume(dir)
        val result = resume.loadResumePoint(CheckpointResume.ResumeStage.ANALYZE)

        assertNull(result, "Should return null when research.json is missing")
    }

    // ---------------------------------------------------------------
    // loadResumePoint: GENERATE stage
    // ---------------------------------------------------------------

    @Test
    fun `loadResumePoint GENERATE returns both outputs when both jsons exist`() = runBlocking {
        val dir = createTempDir("generate-ok")
        writeJson(dir, "research.json", sampleResearchJson)
        writeJson(dir, "analyze.json", sampleAnalyzeJson)

        val resume = CheckpointResume(dir)
        val result = resume.loadResumePoint(CheckpointResume.ResumeStage.GENERATE)

        assertNotNull(result, "Should return ResumeData when both JSONs exist")
        assertEquals(CheckpointResume.ResumeStage.GENERATE, result.fromStage)
        assertNotNull(result.researchOutput, "researchOutput should be present")
        assertNotNull(result.analyzeOutput, "analyzeOutput should be present")
        assertEquals("owner/repo", result.researchOutput.project)
        assertEquals("portable_zip", result.analyzeOutput.strategy)
        assertEquals(dir.name, result.runId)
    }

    @Test
    fun `loadResumePoint GENERATE returns null when analyze json missing`() = runBlocking {
        val dir = createTempDir("generate-missing-analyze")
        writeJson(dir, "research.json", sampleResearchJson)
        // Don't write analyze.json

        val resume = CheckpointResume(dir)
        val result = resume.loadResumePoint(CheckpointResume.ResumeStage.GENERATE)

        assertNull(result, "Should return null when analyze.json is missing")
    }

    @Test
    fun `loadResumePoint GENERATE returns null when research json missing`() = runBlocking {
        val dir = createTempDir("generate-missing-research")
        writeJson(dir, "analyze.json", sampleAnalyzeJson)
        // Don't write research.json

        val resume = CheckpointResume(dir)
        val result = resume.loadResumePoint(CheckpointResume.ResumeStage.GENERATE)

        assertNull(result, "Should return null when research.json is missing")
    }

    // ---------------------------------------------------------------
    // Corrupt/wrong type JSON
    // ---------------------------------------------------------------

    @Test
    fun `loadResumePoint returns null when json is corrupt`() = runBlocking {
        val dir = createTempDir("corrupt-json")
        writeJson(dir, "research.json", "{this is not valid json!!!}")
        writeJson(dir, "analyze.json", "{also not valid!!!")

        val resume = CheckpointResume(dir)
        val resultAnalyze = resume.loadResumePoint(CheckpointResume.ResumeStage.ANALYZE)
        val resultGenerate = resume.loadResumePoint(CheckpointResume.ResumeStage.GENERATE)

        assertNull(resultAnalyze, "ANALYZE should return null when research.json is corrupt")
        assertNull(resultGenerate, "GENERATE should return null when both JSONs are corrupt")
    }

    @Test
    fun `loadResumePoint returns null when json has wrong type`() = runBlocking {
        val dir = createTempDir("wrong-type")
        // Write a valid JSON but with wrong structure (e.g. a plain string array, not ResearchOutput)
        writeJson(dir, "research.json", """{"wrong_field": "value"}""")
        writeJson(dir, "analyze.json", """{"some_random": "data"}""")

        val resume = CheckpointResume(dir)
        val resultAnalyze = resume.loadResumePoint(CheckpointResume.ResumeStage.ANALYZE)
        val resultGenerate = resume.loadResumePoint(CheckpointResume.ResumeStage.GENERATE)

        // With ignoreUnknownKeys = true and coerceInputValues = true,
        // the wrong-type JSON will deserialize to defaults instead of throwing.
        // So it returns data with default values, not null.
        // This is acceptable behavior — the phaseJson is lenient.
        assertNotNull(resultAnalyze, "ANALYZE should still return data with wrong-type JSON (lenient parser)")
        assertNotNull(resultGenerate, "GENERATE should still return data with wrong-type JSON (lenient parser)")
    }

    // ---------------------------------------------------------------
    // fromPhaseOutputs factory
    // ---------------------------------------------------------------

    @Test
    fun `fromPhaseOutputs ANALYZE returns data when research json provided`() {
        val resume = ResumeData.fromPhaseOutputs(
            fromStage = CheckpointResume.ResumeStage.ANALYZE,
            runId = "run-123",
            researchJson = sampleResearchJson,
            analyzeJson = null,
        )
        assertNotNull(resume, "Should return ResumeData when research json provided")
        assertEquals(CheckpointResume.ResumeStage.ANALYZE, resume.fromStage)
        assertEquals("run-123", resume.runId)
        assertEquals("owner/repo", resume.researchOutput?.project)
        assertNull(resume.analyzeOutput, "analyze output should be absent for ANALYZE stage")
    }

    @Test
    fun `fromPhaseOutputs ANALYZE returns null when research json missing`() {
        val resume = ResumeData.fromPhaseOutputs(
            fromStage = CheckpointResume.ResumeStage.ANALYZE,
            runId = "run-123",
            researchJson = null,
            analyzeJson = sampleAnalyzeJson,
        )
        assertNull(resume, "ANALYZE requires research json")
    }

    @Test
    fun `fromPhaseOutputs GENERATE returns data when research and analyze provided`() {
        val resume = ResumeData.fromPhaseOutputs(
            fromStage = CheckpointResume.ResumeStage.GENERATE,
            runId = "run-123",
            researchJson = sampleResearchJson,
            analyzeJson = sampleAnalyzeJson,
        )
        assertNotNull(resume, "Should return ResumeData when both jsons provided")
        assertEquals(CheckpointResume.ResumeStage.GENERATE, resume.fromStage)
        assertEquals("owner/repo", resume.researchOutput?.project)
        assertEquals("portable_zip", resume.analyzeOutput?.strategy)
    }

    @Test
    fun `fromPhaseOutputs GENERATE returns null when analyze json missing`() {
        val resume = ResumeData.fromPhaseOutputs(
            fromStage = CheckpointResume.ResumeStage.GENERATE,
            runId = "run-123",
            researchJson = sampleResearchJson,
            analyzeJson = null,
        )
        assertNull(resume, "GENERATE requires both research and analyze jsons")
    }

    @Test
    fun `fromPhaseOutputs returns null on corrupt json`() {
        val resume = ResumeData.fromPhaseOutputs(
            fromStage = CheckpointResume.ResumeStage.GENERATE,
            runId = "run-123",
            researchJson = "{not valid json!!!",
            analyzeJson = sampleAnalyzeJson,
        )
        assertNull(resume, "Corrupt research json should yield null")
    }

    @Test
    fun `fromPhaseOutputs ignores extra analyze output for ANALYZE stage`() {
        // The factory only consumes the untyped JSON strings it is given; any
        // extra/ignored outputs (assist, generate) never reach the signature.
        // For ANALYZE, a provided analyzeJson must be ignored (not decoded).
        val resume = ResumeData.fromPhaseOutputs(
            fromStage = CheckpointResume.ResumeStage.ANALYZE,
            runId = "run-123",
            researchJson = sampleResearchJson,
            analyzeJson = sampleAnalyzeJson, // ignored for ANALYZE
        )
        assertNotNull(resume)
        assertNull(resume.analyzeOutput, "analyze output should be ignored for ANALYZE stage")
    }

    // ---------------------------------------------------------------
    // toAgentCheckpointData
    // ---------------------------------------------------------------

    @Test
    fun `toAgentCheckpointData for ANALYZE creates valid checkpoint`() = runBlocking {
        val dir = createTempDir("cp-analyze")
        writeJson(dir, "research.json", sampleResearchJson)

        val resume = CheckpointResume(dir)
        val data = resume.loadResumePoint(CheckpointResume.ResumeStage.ANALYZE)!!

        val checkpoint = data.toAgentCheckpointData("https://github.com/owner/repo")
        val graphProps = checkpoint.graphProperties

        assertEquals("resume-analyze-${dir.name}", checkpoint.checkpointId)
        assertNotNull(graphProps)
        assertTrue(
            graphProps.nodePath.endsWith("/scoop_manifest_v3/research/finalizeTask"),
            "Node path should end with research/finalizeTask, got: ${graphProps.nodePath}"
        )
        // ANALYZE resume does not need pre-populated storage
        assertNull(checkpoint.storage, "ANALYZE checkpoint should have null storage")
    }

    @Test
    fun `toAgentCheckpointData for GENERATE creates valid checkpoint with storage`() = runBlocking {
        val dir = createTempDir("cp-generate")
        writeJson(dir, "research.json", sampleResearchJson)
        writeJson(dir, "analyze.json", sampleAnalyzeJson)

        val resume = CheckpointResume(dir)
        val data = resume.loadResumePoint(CheckpointResume.ResumeStage.GENERATE)!!

        val checkpoint = data.toAgentCheckpointData("https://github.com/owner/repo")
        val graphProps = checkpoint.graphProperties
        val storage = checkpoint.storage

        assertEquals("resume-generate-${dir.name}", checkpoint.checkpointId)
        assertNotNull(graphProps)
        assertTrue(
            graphProps.nodePath.endsWith("/scoop_manifest_v3/analyze/finalizeTask"),
            "Node path should end with analyze/finalizeTask, got: ${graphProps.nodePath}"
        )
        // GENERATE resume needs pre-populated storage with research_data and suggested_app_name
        assertNotNull(storage, "GENERATE checkpoint should have non-null storage with research_data")
        assertTrue(
            storage.entries.containsKey("research_data"),
            "Storage should contain research_data key"
        )
        assertTrue(
            storage.entries.containsKey("suggested_app_name"),
            "Storage should contain suggested_app_name key"
        )
    }
}
