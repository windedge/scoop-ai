package com.scoopai.tools.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class UsedAppNameScannerTest {

    // ---------------------------------------------------------------
    // scan
    // ---------------------------------------------------------------

    @Test
    fun `scan collects json names from all bucket subdirs`() {
        val tmpDir = createTempDir("scoop-buckets")
        try {
            val mainBucket = File(tmpDir, "main/bucket").also { it.mkdirs() }
            File(mainBucket, "micro.json").writeText("{}")
            File(mainBucket, "fd.json").writeText("{}")
            val extrasBucket = File(tmpDir, "extras/bucket").also { it.mkdirs() }
            File(extrasBucket, "tine.json").writeText("{}")

            val names = UsedAppNameScanner.scan(tmpDir)
            assertEquals(setOf("micro", "fd", "tine"), names)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `scan ignores non-json files`() {
        val tmpDir = createTempDir("scoop-buckets")
        try {
            val bucket = File(tmpDir, "main/bucket").also { it.mkdirs() }
            File(bucket, "README.md").writeText("")
            File(bucket, "micro.json").writeText("{}")

            val names = UsedAppNameScanner.scan(tmpDir)
            assertEquals(setOf("micro"), names)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `scan returns empty when buckets dir missing`() {
        val names = UsedAppNameScanner.scan(File("nonexistent-12345-test"))
        assertTrue(names.isEmpty())
    }

    @Test
    fun `scan handles bucket without bucket subdir`() {
        val tmpDir = createTempDir("scoop-buckets")
        try {
            val mainBucket = File(tmpDir, "main").also { it.mkdirs() }
            // No "bucket" subdir under main

            val names = UsedAppNameScanner.scan(tmpDir)
            assertTrue(names.isEmpty())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `scan returns empty when bucket subdir exists but empty`() {
        val tmpDir = createTempDir("scoop-buckets")
        try {
            File(tmpDir, "main/bucket").also { it.mkdirs() }
            // Empty bucket subdir (no files at all)

            val names = UsedAppNameScanner.scan(tmpDir)
            assertTrue(names.isEmpty())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // scanDefault
    // ---------------------------------------------------------------

    @Test
    fun `scanDefault does not throw`() {
        // scanDefault should not throw regardless of whether Scoop is installed.
        // If scoop is installed, it returns known names; if not, empty set.
        val names = UsedAppNameScanner.scanDefault()
        assertTrue(names is Set<*>, "scanDefault should return a Set")
    }
}

/**
 * Creates a temporary directory for testing.
 */
private fun createTempDir(prefix: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "${prefix}_${System.nanoTime()}")
    dir.mkdirs()
    return dir
}
