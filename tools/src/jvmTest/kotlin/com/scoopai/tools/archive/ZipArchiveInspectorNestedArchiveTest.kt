package com.scoopai.tools.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for nested archive detection in [ZipArchiveInspector.listArchiveContents].
 *
 * Verifies that when a ZIP/7z contains only a single nested ZIP/7z, the tool
 * recursively inspects the inner archive and returns its contents.
 */
class ZipArchiveInspectorNestedArchiveTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------
    // Helpers (matching pattern from ZipArchiveInspectorSearchTest)
    // ---------------------------------------------------------------

    /** Create a test zip file with the given text file entries. */
    private fun createTestZip(vararg entries: Pair<String, String>): Path {
        val zipFile = Files.createTempFile("test-nested-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /** Create a test zip file with binary or text entries. */
    private fun createTestZipWithBinary(vararg entries: Pair<String, Any>): Path {
        val zipFile = Files.createTempFile("test-nested-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                when (content) {
                    is String -> zos.write(content.toByteArray(Charsets.UTF_8))
                    is ByteArray -> zos.write(content)
                }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /**
     * Create a ZipArchiveInspector whose cache returns the file for the given URL.
     */
    private fun createInspector(testFile: Path, testUrl: String): ZipArchiveInspector {
        val cacheDir = com.scoopai.tools.cache.ScoopCacheManager.scoopCacheDir()
        val cacheName = com.scoopai.tools.cache.ScoopCacheManager.downloadCacheName(testUrl)
        val cachedFile = cacheDir.resolve(cacheName)
        Files.copy(testFile, cachedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return ZipArchiveInspector(com.scoopai.tools.cache.ScoopCacheManager(keepCache = false))
    }

    private fun cleanupInspector(testUrl: String) {
        val cacheDir = com.scoopai.tools.cache.ScoopCacheManager.scoopCacheDir()
        val cacheName = com.scoopai.tools.cache.ScoopCacheManager.downloadCacheName(testUrl)
        Files.deleteIfExists(cacheDir.resolve(cacheName))
    }

    // ---------------------------------------------------------------
    // Nested archive detection
    // ---------------------------------------------------------------

    @Test
    fun `listArchiveContents detects single nested zip and returns inner files`() = kotlinx.coroutines.runBlocking {
        // Create inner zip with fake application files
        val innerZip = createTestZip(
            "GoZen.exe" to "fake exe content",
            "GoZen.pck" to "fake pck content",
            "libgozen.dll" to "fake dll content",
        )
        val innerZipBytes = Files.readAllBytes(innerZip)

        // Create outer zip containing only the inner zip (simulating Godot export nesting)
        val outerZip = createTestZipWithBinary(
            "GoZen_Windows_v0.9.4-alpha.zip" to innerZipBytes,
        )

        val url = "https://github.com/test/gozen/releases/download/v0.9.4/GoZen_Windows_v0.9.4-alpha.zip"

        try {
            val inspector = createInspector(outerZip, url)
            val result = inspector.listArchiveContents(url = url)
            val obj = json.parseToJsonElement(result).jsonObject

            // Should have nested_archive field
            assertTrue(obj.containsKey("nested_archive"), "Should detect nested archive, got: $result")
            val nested = obj["nested_archive"]!!.jsonObject

            // Check nested_archive name
            assertEquals(
                "GoZen_Windows_v0.9.4-alpha.zip",
                nested["name"]?.jsonPrimitive?.content,
            )

            // Check inner files contain the real executable
            val innerFiles = nested["files"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(innerFiles.contains("GoZen.exe"), "Inner files should contain GoZen.exe, got: $innerFiles")
            assertTrue(innerFiles.contains("GoZen.pck"), "Inner files should contain GoZen.pck, got: $innerFiles")
            assertTrue(innerFiles.contains("libgozen.dll"), "Inner files should contain libgozen.dll, got: $innerFiles")
        } finally {
            Files.deleteIfExists(innerZip)
            Files.deleteIfExists(outerZip)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Regression: flat zip with multiple files
    // ---------------------------------------------------------------

    @Test
    fun `listArchiveContents returns flat listing for non-nested zip`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "app.exe" to "exe content",
            "readme.txt" to "hello world",
        )
        val url = "https://example.com/test-flat.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.listArchiveContents(url = url)
            val obj = json.parseToJsonElement(result).jsonObject

            assertFalse(obj.containsKey("nested_archive"), "Should not have nested_archive for flat zip: $result")
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Boundary: multiple archive entries
    // ---------------------------------------------------------------

    @Test
    fun `listArchiveContents does not recurse when outer has multiple archive entries`() = kotlinx.coroutines.runBlocking {
        val innerZip1 = createTestZip("inner1.exe" to "exe")
        val innerZip2 = createTestZip("inner2.exe" to "exe2")
        val outerZip = createTestZipWithBinary(
            "archive1.zip" to Files.readAllBytes(innerZip1),
            "archive2.zip" to Files.readAllBytes(innerZip2),
        )
        val url = "https://example.com/test-multi-archive.zip"
        try {
            val inspector = createInspector(outerZip, url)
            val result = inspector.listArchiveContents(url = url)
            val obj = json.parseToJsonElement(result).jsonObject

            assertFalse(obj.containsKey("nested_archive"), "Should not recurse for multiple archives: $result")
        } finally {
            Files.deleteIfExists(innerZip1)
            Files.deleteIfExists(innerZip2)
            Files.deleteIfExists(outerZip)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Boundary: outer zip has no archive entries
    // ---------------------------------------------------------------

    @Test
    fun `listArchiveContents returns flat listing for zip with no archive files`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "data.bin" to "binary",
            "config.json" to "{}",
        )
        val url = "https://example.com/test-no-archive.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.listArchiveContents(url = url)
            val obj = json.parseToJsonElement(result).jsonObject

            assertFalse(obj.containsKey("nested_archive"), "Should not have nested_archive: $result")
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }
}
