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
import kotlin.test.assertTrue

class ZipArchiveInspectorSearchTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Create a test zip file with the given file entries. */
    private fun createTestZip(vararg entries: Pair<String, String>): Path {
        val zipFile = Files.createTempFile("test-search-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /** Create a test zip file with binary entries. */
    private fun createTestZipWithBinary(vararg entries: Pair<String, Any>): Path {
        val zipFile = Files.createTempFile("test-search-", ".zip")
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
     * Since ScoopCacheManager is final, we use a temp directory-based approach:
     * the cache will find the file by its URL hash.
     */
    private fun createInspector(testFile: Path, testUrl: String): ZipArchiveInspector {
        // Place the test file in the Scoop cache directory with the correct cache name
        val cacheDir = com.scoopai.tools.cache.ScoopCacheManager.scoopCacheDir()
        val cacheName = com.scoopai.tools.cache.ScoopCacheManager.downloadCacheName(testUrl)
        val cachedFile = cacheDir.resolve(cacheName)
        Files.copy(testFile, cachedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        // Create inspector with default cache — it should find the file on disk
        return ZipArchiveInspector(com.scoopai.tools.cache.ScoopCacheManager(keepCache = false))
    }

    private fun cleanupInspector(testUrl: String) {
        val cacheDir = com.scoopai.tools.cache.ScoopCacheManager.scoopCacheDir()
        val cacheName = com.scoopai.tools.cache.ScoopCacheManager.downloadCacheName(testUrl)
        Files.deleteIfExists(cacheDir.resolve(cacheName))
    }

    // ---------------------------------------------------------------
    // Basic text search
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive finds matching text in zip`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "src/main.rs" to """fn main() {
    let config_dir = std::env::var("XDG_CONFIG_HOME")
        .unwrap_or_else(|_| format!("{}/.config/myapp", home_dir()));
}""",
            "README.md" to "# My App\n\nA great tool.",
        )
        val url = "https://example.com/test-source-search.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.searchTextInArchive(
                url = url,
                patterns = "XDG_CONFIG_HOME",
                includeGlobs = null,
            )
            val obj = json.parseToJsonElement(result).jsonObject
            assertTrue(obj.contains("matches"), "Should contain 'matches' key, got: $result")
            val matches = obj["matches"]!!.jsonArray
            assertTrue(matches.size > 0, "Should find at least one match")
            val match = matches[0].jsonObject
            assertEquals("src/main.rs", match["file"]?.jsonPrimitive?.content)
            assertTrue(match["snippet"]?.jsonPrimitive?.content?.contains("XDG_CONFIG_HOME") == true)
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    @Test
    fun `searchTextInArchive returns empty matches when pattern not found`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "src/main.rs" to "fn main() { println!(\"hello\"); }",
        )
        val url = "https://example.com/test-notfound.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.searchTextInArchive(
                url = url,
                patterns = "nonexistent_pattern_xyz",
            )
            val obj = json.parseToJsonElement(result).jsonObject
            val matches = obj["matches"]!!.jsonArray
            assertEquals(0, matches.size)
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Binary file exclusion
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive skips binary files`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZipWithBinary(
            "src/main.rs" to "XDG_CONFIG_HOME is used here",
            "assets/image.bin" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A),
        )
        val url = "https://example.com/test-binary.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.searchTextInArchive(
                url = url,
                patterns = "XDG_CONFIG_HOME",
            )
            val obj = json.parseToJsonElement(result).jsonObject
            val matches = obj["matches"]!!.jsonArray
            assertEquals(1, matches.size)
            assertEquals("src/main.rs", matches[0].jsonObject["file"]?.jsonPrimitive?.content)
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Extension filter (includeGlobs)
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive respects includeGlobs filter`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "src/config.rs" to "let dir = env::var(\"MYAPP_CONFIG_DIR\");",
            "package.json" to "{\"name\": \"myapp\", \"dependencies\": {\"electron\": \"^28.0.0\"}}",
        )
        val url = "https://example.com/test-globs.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val resultJson = inspector.searchTextInArchive(
                url = url,
                patterns = "electron",
                includeGlobs = "*.json",
            )
            val obj = json.parseToJsonElement(resultJson).jsonObject
            val matches = obj["matches"]!!.jsonArray
            assertEquals(1, matches.size)
            assertEquals("package.json", matches[0].jsonObject["file"]?.jsonPrimitive?.content)
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // maxResults limit
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive respects maxResults limit`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "src/a.rs" to "XDG_CONFIG_HOME line 1\nXDG_CONFIG_HOME line 2\nXDG_CONFIG_HOME line 3",
        )
        val url = "https://example.com/test-maxresults.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.searchTextInArchive(
                url = url,
                patterns = "XDG_CONFIG_HOME",
                maxResults = 2,
            )
            val obj = json.parseToJsonElement(result).jsonObject
            val matches = obj["matches"]!!.jsonArray
            assertTrue(matches.size <= 2, "Should respect maxResults limit, got ${matches.size}")
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Multiple patterns
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive supports multiple patterns`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "src/config.rs" to "let xdg = env::var(\"XDG_CONFIG_HOME\");",
            "src/app.rs" to "let store = electron_store::new();",
        )
        val url = "https://example.com/test-multi.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.searchTextInArchive(
                url = url,
                patterns = "XDG_CONFIG_HOME,electron_store",
            )
            val obj = json.parseToJsonElement(result).jsonObject
            val matches = obj["matches"]!!.jsonArray
            assertTrue(matches.size >= 2, "Should find matches for both patterns, got ${matches.size}")
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Error handling
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive returns error for missing URL`() = kotlinx.coroutines.runBlocking {
        val cache = com.scoopai.tools.cache.ScoopCacheManager(keepCache = false)
        val inspector = ZipArchiveInspector(cache)
        try {
            val result = inspector.searchTextInArchive(
                url = "https://example.com/nonexistent-abc123.zip",
                patterns = "test",
            )
            val obj = json.parseToJsonElement(result).jsonObject
            assertTrue(obj.contains("error"), "Should contain 'error' key, got: $result")
        } finally {
            cache.cleanup()
        }
    }

    // ---------------------------------------------------------------
    // Metadata in result
    // ---------------------------------------------------------------

    @Test
    fun `searchTextInArchive includes metadata in result`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "src/main.rs" to "fn main() { let config = \"config.yaml\"; }",
        )
        val url = "https://example.com/test-metadata.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.searchTextInArchive(
                url = url,
                patterns = "config.yaml",
            )
            val obj = json.parseToJsonElement(result).jsonObject
            assertTrue(obj.contains("url"), "Should contain 'url'")
            assertTrue(obj.contains("files_scanned"), "Should contain 'files_scanned'")
            assertTrue(obj.contains("match_count"), "Should contain 'match_count'")
            assertTrue(obj.contains("patterns_used"), "Should contain 'patterns_used'")
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }

    // ---------------------------------------------------------------
    // Existing tools not broken
    // ---------------------------------------------------------------

    @Test
    fun `listArchiveContents still works after searchTextInArchive addition`() = kotlinx.coroutines.runBlocking {
        val zipFile = createTestZip(
            "app-1.0/app.exe" to "binary content here",
            "app-1.0/readme.txt" to "Hello world",
        )
        val url = "https://example.com/test-list.zip"
        try {
            val inspector = createInspector(zipFile, url)
            val result = inspector.listArchiveContents(url = url)
            val obj = json.parseToJsonElement(result).jsonObject
            assertTrue(obj.contains("files"), "Should contain 'files' key")
            val files = obj["files"]!!.jsonArray
            assertEquals(2, files.size)
        } finally {
            Files.deleteIfExists(zipFile)
            cleanupInspector(url)
        }
    }
}
