package com.scoopai.core.artifacts

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.io.File

class SensitiveArtifactWriterTest {

    // ---------------------------------------------------------------
    // masking is applied
    // ---------------------------------------------------------------

    @Test
    fun `writePhase applies masker to content`() {
        val dir = createTempDir()
        try {
            val delegate = RunArtifacts(runId = "mask-test", baseDir = dir.path)
            val masker: (String) -> String = { it.replace("sk-secret123", "***REDACTED***") }
            val writer = SensitiveArtifactWriter(delegate, masker)

            writer.writePhase("research", """{"key":"sk-secret123"}""")

            val content = File(dir, ".scoopai/runs/mask-test/research.json").readText()
            assertTrue(content.contains("REDACTED"), "API key should be masked")
            assertTrue(!content.contains("sk-secret123"), "Original key should not appear")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `masker is always called even without sensitive content`() {
        var callCount = 0
        val dir = createTempDir()
        try {
            val delegate = RunArtifacts(runId = "mask-count", baseDir = dir.path)
            val masker: (String) -> String = {
                callCount++
                it
            }
            val writer = SensitiveArtifactWriter(delegate, masker)

            writer.writePhase("research", "no secrets here")
            assertEquals(1, callCount, "masker should be called once")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `finalize delegates to underlying RunArtifacts`() {
        val dir = createTempDir()
        try {
            val delegate = RunArtifacts(runId = "del-final", baseDir = dir.path)
            val writer = SensitiveArtifactWriter(delegate) { it }
            writer.finalize()

            assertTrue(File(dir, ".scoopai/runs/del-final/done.marker").exists(),
                "finalize should delegate to RunArtifacts")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `markFailed applies masker to error`() {
        val dir = createTempDir()
        try {
            val delegate = RunArtifacts(runId = "mask-err", baseDir = dir.path)
            val masker: (String) -> String = { it.replace("token=abc123", "token=***REDACTED***") }
            val writer = SensitiveArtifactWriter(delegate, masker)

            writer.markFailed("token=abc123 timeout")

            val content = File(dir, ".scoopai/runs/mask-err/error.txt").readText()
            assertTrue(content.contains("REDACTED"), "Token should be masked")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "scoopai-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
