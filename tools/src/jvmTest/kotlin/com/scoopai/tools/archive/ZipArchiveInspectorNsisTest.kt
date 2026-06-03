package com.scoopai.tools.archive

import com.scoopai.tools.cache.ScoopCacheManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ZipArchiveInspector.testNsisExtractability].
 *
 * These tests verify that 7z can/cannot list various file types.
 */
class ZipArchiveInspectorNsisTest {

    /** Create a [ZipArchiveInspector] instance for testing. */
    private fun createInspector(): ZipArchiveInspector {
        return ZipArchiveInspector(ScoopCacheManager(keepCache = false))
    }

    // ---------------------------------------------------------------
    // testNsisExtractability — valid zip should be listable by 7z
    // ---------------------------------------------------------------

    @Test
    fun `testNsisExtractability returns true for valid zip file`() {
        val zipFile = createTempZip("test.txt", "hello world")
        try {
            val inspector = createInspector()
            val result = inspector.testNsisExtractability(zipFile)
            assertTrue(result, "7z should be able to list a valid zip file")
        } finally {
            Files.deleteIfExists(zipFile)
        }
    }

    @Test
    fun `testNsisExtractability returns true for valid zip with nested content`() {
        val zipFile = createTempZip("dir/nested/file.txt", "nested content")
        try {
            val inspector = createInspector()
            val result = inspector.testNsisExtractability(zipFile)
            assertTrue(result, "7z should be able to list a valid zip with nested content")
        } finally {
            Files.deleteIfExists(zipFile)
        }
    }

    // ---------------------------------------------------------------
    // testNsisExtractability — non-archive files should fail
    // ---------------------------------------------------------------

    @Test
    fun `testNsisExtractability returns false for random binary file`() {
        val binFile = Files.createTempFile("test-random-", ".bin")
        try {
            Files.write(binFile, byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte()))
            val inspector = createInspector()
            val result = inspector.testNsisExtractability(binFile)
            assertFalse(result, "7z should fail to list a random binary file")
        } finally {
            Files.deleteIfExists(binFile)
        }
    }

    @Test
    fun `testNsisExtractability returns false for plain text file`() {
        val txtFile = Files.createTempFile("test-plain-", ".txt")
        try {
            Files.writeString(txtFile, "This is just a plain text file, not an archive.")
            val inspector = createInspector()
            val result = inspector.testNsisExtractability(txtFile)
            assertFalse(result, "7z should fail to list a plain text file")
        } finally {
            Files.deleteIfExists(txtFile)
        }
    }

    // ---------------------------------------------------------------
    // testNsisExtractability — empty file should fail
    // ---------------------------------------------------------------

    @Test
    fun `testNsisExtractability returns false for empty file`() {
        val emptyFile = Files.createTempFile("test-empty-", ".dat")
        try {
            Files.write(emptyFile, ByteArray(0))
            val inspector = createInspector()
            val result = inspector.testNsisExtractability(emptyFile)
            assertFalse(result, "7z should fail to list an empty file")
        } finally {
            Files.deleteIfExists(emptyFile)
        }
    }

    // ---------------------------------------------------------------
    // testNsisExtractability — non-existent file returns false
    // ---------------------------------------------------------------

    @Test
    fun `testNsisExtractability returns false for non-existent file`() {
        val fakeFile = Path.of("C:/nonexistent-nsis-test-file-xyz.bin")
        val inspector = createInspector()
        val result = inspector.testNsisExtractability(fakeFile)
        assertFalse(result, "7z should fail for non-existent file")
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Create a temporary zip file with one entry. */
    private fun createTempZip(entryName: String, content: String): Path {
        val zipFile = Files.createTempFile("test-nsis-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return zipFile
    }
}
