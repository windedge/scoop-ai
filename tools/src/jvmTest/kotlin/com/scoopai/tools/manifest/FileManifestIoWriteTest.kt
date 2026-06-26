package com.scoopai.tools.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class FileManifestIoWriteTest {

    private val io = FileManifestIo()

    /** Helper: write a manifest and return the written file's bytes. */
    private fun writeManifest(json: String, fileName: String = "test-app.json"): ByteArray {
        val tempDir = Files.createTempDirectory("scoop-test-")
        try {
            io.write(json, tempDir.resolve(fileName).toString())
            return Files.readAllBytes(tempDir.resolve(fileName))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /** A minimal valid manifest that's quick to write. */
    private val minimalManifest = """
        {
            "version": "1.0.0",
            "description": "Test app",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app-1.0.0.zip",
            "hash": "5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b",
            "bin": "app.exe"
        }
    """.trimIndent()

    // ---------------------------------------------------------------
    // Constraint 1: files end with a newline (CRLF)
    // ---------------------------------------------------------------

    @Test
    fun `written manifest ends with CRLF newline`() {
        val bytes = writeManifest(minimalManifest)

        assertTrue(bytes.size >= 2, "File must be at least 2 bytes long")
        assertEquals(13, bytes[bytes.size - 2].toInt(), "Second-to-last byte must be CR (0x0D)")
        assertEquals(10, bytes[bytes.size - 1].toInt(), "Last byte must be LF (0x0A)")
    }

    @Test
    fun `written manifest ends with exactly one CRLF not multiple`() {
        val bytes = writeManifest(minimalManifest)

        // Should NOT end with \r\n\r\n
        if (bytes.size >= 4) {
            val tail = bytes.takeLast(4)
            assertFalse(
                tail == listOf<Byte>(13, 10, 13, 10),
                "File should not end with multiple CRLF sequences; tail = ${tail.joinToString { it.toInt().toString() }}"
            )
        }
        // Verify exactly one CRLF at the end - there should only be ONE \r before the final \n
        val crBeforeFinalLf = bytes.dropLast(1).lastOrNull { it.toInt() == 13 }
        val lastCrIndex = bytes.indexOfLast { it.toInt() == 13 }
        assertEquals(bytes.size - 2, lastCrIndex, "The only trailing CR must be at second-to-last position")
    }

    // ---------------------------------------------------------------
    // Constraint 2: file newlines are CRLF (uniform)
    // ---------------------------------------------------------------

    @Test
    fun `written manifest uses CRLF line endings uniformly`() {
        // Use a manifest with multiple lines (description with newlines via various fields)
        val multiLineManifest = """
        {
            "version": "2.0.0",
            "description": "A multi-line\\ntest description",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app-2.0.0.zip",
            "hash": "5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b",
            "bin": "app.exe",
            "architecture": {
                "64bit": {
                    "url": "https://example.com/app-2.0.0-x64.zip",
                    "hash": "5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b"
                }
            }
        }
        """.trimIndent()
        val bytes = writeManifest(multiLineManifest)

        // Count CR (0x0D) and LF (0x0A) bytes
        val crCount = bytes.count { it.toInt() == 13 }
        val lfCount = bytes.count { it.toInt() == 10 }

        assertTrue(crCount > 0, "File must contain at least one CRLF")
        assertEquals(
            crCount, lfCount,
            "CR count ($crCount) must equal LF count ($lfCount) — every LF preceded by CR"
        )

        // Also verify no bare LF: check that every 0x0A is preceded by 0x0D
        val lfIndices = bytes.indices.filter { bytes[it].toInt() == 10 }
        for (lfIndex in lfIndices) {
            assertTrue(lfIndex > 0, "LF at index 0 is impossible but check anyway")
            assertEquals(
                13, bytes[lfIndex - 1].toInt(),
                "LF at index $lfIndex must be preceded by CR (0x0D)"
            )
        }
    }

    // ---------------------------------------------------------------
    // Constraint 3: files do not contain leading UTF-8 BOM
    // ---------------------------------------------------------------

    @Test
    fun `written manifest has no UTF-8 BOM`() {
        val bytes = writeManifest(minimalManifest)

        val hasBom = bytes.size >= 3 &&
            bytes[0].toInt() == 0xEF &&
            bytes[1].toInt() == 0xBB &&
            bytes[2].toInt() == 0xBF

        assertFalse(hasBom, "File must not start with UTF-8 BOM bytes (EF BB BF)")
    }

    // ---------------------------------------------------------------
    // Constraint 4: files have no lines containing trailing whitespace
    // ---------------------------------------------------------------

    @Test
    fun `written manifest has no trailing whitespace on any line`() {
        val bytes = writeManifest(minimalManifest)
        val text = bytes.decodeToString()

        val lines = text.split("\r\n")
        for ((i, line) in lines.withIndex()) {
            // The last element after split on \r\n may be empty (trailing newline)
            if (line.isEmpty()) continue
            assertFalse(
                line.matches(Regex(".*[ \t]$")),
                "Line ${i + 1} has trailing whitespace: '$line'"
            )
        }
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Test
    fun `manifest with persist array and multiline is normalized correctly`() {
        val manifest = """
        {
            "version": "1.5.0",
            "description": "App with persist",
            "homepage": "https://example.com",
            "license": "GPL-3.0-only",
            "url": "https://example.com/app-1.5.0.zip",
            "hash": "5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b",
            "bin": "app.exe",
            "persist": [
                "config",
                "data"
            ],
            "shortcuts": [
                ["app.exe", "App"]
            ]
        }
        """.trimIndent()
        val bytes = writeManifest(manifest)
        val text = bytes.decodeToString()

        // All constraints
        assertTrue(bytes.size >= 2, "File must be at least 2 bytes")
        assertEquals(13, bytes[bytes.size - 2].toInt(), "Ends with CR")
        assertEquals(10, bytes[bytes.size - 1].toInt(), "Ends with LF")

        val crCount = bytes.count { it.toInt() == 13 }
        val lfCount = bytes.count { it.toInt() == 10 }
        assertEquals(crCount, lfCount, "CRLF uniform")

        assertFalse(
            bytes.size >= 3 &&
                bytes[0].toInt() == 0xEF &&
                bytes[1].toInt() == 0xBB &&
                bytes[2].toInt() == 0xBF,
            "No BOM"
        )

        val lines = text.split("\r\n")
        for ((i, line) in lines.withIndex()) {
            if (line.isEmpty()) continue
            assertFalse(
                line.matches(Regex(".*[ \t]$")),
                "Line ${i + 1} has trailing whitespace: '$line'"
            )
        }
    }

    @Test
    fun `manifest with trailing whitespace in content is cleaned up`() {
        // Simulate what an LLM might produce: trailing spaces after some lines
        val dirtyJson = """{
    "version": "1.0.0",   
    "description": "Test app",  
    "homepage": "https://example.com",   
    "license": "MIT",   
    "url": "https://example.com/app.zip",   
    "hash": "5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b",
    "bin": "app.exe"   
}"""
        val bytes = writeManifest(dirtyJson, "dirty-test.json")
        val text = bytes.decodeToString()

        val lines = text.split("\r\n")
        for ((i, line) in lines.withIndex()) {
            if (line.isEmpty()) continue
            assertFalse(
                line.matches(Regex(".*[ \t]$")),
                "Line ${i + 1} should have trailing whitespace cleaned: '$line'"
            )
        }
    }
}
