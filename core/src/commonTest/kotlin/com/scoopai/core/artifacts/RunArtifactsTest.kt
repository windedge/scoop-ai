package com.scoopai.core.artifacts

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.File

class RunArtifactsTest {

    private val tmpDir: File get() {
        val dir = File(System.getProperty("java.io.tmpdir"), "scoopai-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // ---------------------------------------------------------------
    // writePhase
    // ---------------------------------------------------------------

    @Test
    fun `writePhase creates directory and file`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "test-run", baseDir = dir.path)
            artifacts.writePhase("research", """{"status":"ok"}""")

            val phaseFile = File(dir, ".scoopai/runs/test-run/research.json")
            assertTrue(phaseFile.exists(), "research.json should exist")
            assertTrue(phaseFile.readText().contains("ok"), "Content should be written")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writePhase multiple phases create separate files`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "multi-phase", baseDir = dir.path)
            artifacts.writePhase("research", """{"phase":"research"}""")
            artifacts.writePhase("analyze", """{"phase":"analyze"}""")
            artifacts.writePhase("generate", """{"phase":"generate"}""")

            val base = File(dir, ".scoopai/runs/multi-phase")
            assertTrue(File(base, "research.json").exists())
            assertTrue(File(base, "analyze.json").exists())
            assertTrue(File(base, "generate.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writePhase uses atomic write via temp file rename`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "atomic", baseDir = dir.path)
            artifacts.writePhase("research", "content")

            val runDir = File(dir, ".scoopai/runs/atomic")
            // Temp file should NOT exist after successful write
            val tempFile = File(runDir, ".research.tmp")
            assertTrue(!tempFile.exists(), "Temp file should be deleted after rename")
            // Target file should exist
            assertTrue(File(runDir, "research.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writePhase overwrites existing phase file with new content`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "overwrite", baseDir = dir.path)
            artifacts.writePhase("config", "{\"version\":1}")
            artifacts.writePhase("config", "{\"version\":2}")

            val phaseFile = File(dir, ".scoopai/runs/overwrite/config.json")
            assertTrue(phaseFile.exists(), "config.json should exist")
            assertEquals("{\"version\":2}", phaseFile.readText(), "Second write should overwrite first")

            val runDir = File(dir, ".scoopai/runs/overwrite")
            val tempFile = File(runDir, ".config.tmp")
            assertTrue(!tempFile.exists(), "Temp file should be cleaned up after overwrite")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // finalize
    // ---------------------------------------------------------------

    @Test
    fun `finalize uses atomic write no temp file left`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "finalize-atomic", baseDir = dir.path)
            artifacts.finalize()

            val runDir = File(dir, ".scoopai/runs/finalize-atomic")
            val doneFile = File(runDir, "done.marker")
            assertTrue(doneFile.exists(), "done.marker should exist")
            assertEquals("completed", doneFile.readText())
            // No temp file should remain after atomic write
            assertTrue(!File(runDir, ".done.marker.tmp").exists(), "Temp file should not exist after finalize")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // markFailed
    // ---------------------------------------------------------------

    @Test
    fun `markFailed uses atomic write no temp file left`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "fail-atomic", baseDir = dir.path)
            artifacts.markFailed("critical error")

            val runDir = File(dir, ".scoopai/runs/fail-atomic")
            val errorFile = File(runDir, "error.txt")
            assertTrue(errorFile.exists(), "error.txt should exist")
            assertTrue(errorFile.readText().contains("critical"), "Error text should be written")
            // No temp file should remain after atomic write
            assertTrue(!File(runDir, ".error.txt.tmp").exists(), "Temp file should not exist after markFailed")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // finalize

    @Test
    fun `finalize writes done marker`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "finalize-test", baseDir = dir.path)
            artifacts.writePhase("research", "data")
            artifacts.finalize()

            val doneFile = File(dir, ".scoopai/runs/finalize-test/done.marker")
            assertTrue(doneFile.exists(), "done.marker should exist")
            assertEquals("completed", doneFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // markFailed
    // ---------------------------------------------------------------

    @Test
    fun `markFailed writes error file`() {
        val dir = tmpDir
        try {
            val artifacts = RunArtifacts(runId = "fail-test", baseDir = dir.path)
            artifacts.writePhase("research", "data")
            artifacts.markFailed("LLM error: timeout")

            val errorFile = File(dir, ".scoopai/runs/fail-test/error.txt")
            assertTrue(errorFile.exists(), "error.txt should exist")
            assertTrue(errorFile.readText().contains("timeout"), "Error text should be written")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `runId is exposed`() {
        val artifacts = RunArtifacts(runId = "custom-id-123")
        assertEquals("custom-id-123", artifacts.runId)
    }

    @Test
    fun `runDir path is correct`() {
        val artifacts = RunArtifacts(runId = "my-run", baseDir = "/tmp")
        assertEquals(
            File("/tmp/.scoopai/runs/my-run"),
            artifacts.runDir
        )
    }
}
