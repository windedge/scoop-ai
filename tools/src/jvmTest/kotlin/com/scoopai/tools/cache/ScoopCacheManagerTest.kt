package com.scoopai.tools.cache

import com.sun.net.httpserver.HttpServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScoopCacheManagerTest {

    // -----------------------------------------------------------------------
    // Cache key normalization (fragment stripping)
    // -----------------------------------------------------------------------

    @Test
    fun `urlHash uses full URL with fragment for Scoop compatibility`() {
        val raw = "https://example.com/app-v1.0.exe"
        val withFragment = "https://example.com/app-v1.0.exe#/dl.7z"
        // urlHash is used for scoop-facing cache names, must match Scoop's full-URL hash
        assertNotEquals(ScoopCacheManager.urlHash(raw), ScoopCacheManager.urlHash(withFragment))
    }

    @Test
    fun `urlHash preserves non-scoop parts of URL`() {
        val a = "https://example.com/a.zip"
        val b = "https://example.com/b.zip"
        assertNotEquals(ScoopCacheManager.urlHash(a), ScoopCacheManager.urlHash(b))
    }

    @Test
    fun `urlExtension extracts extension from scoop fragment URL`() {
        assertEquals(".7z", ScoopCacheManager.urlExtension("https://x.com/app.exe#/dl.7z"))
        assertEquals(".zip", ScoopCacheManager.urlExtension("https://x.com/app.zip#/outer.zip"))
        assertEquals(".exe", ScoopCacheManager.urlExtension("https://x.com/app.exe"))
    }

    @Test
    fun `downloadCacheName produces same name with or without fragment when extension unchanged`() {
        // Scenario: zip#/outer.zip — extension stays .zip, only hash needs to match
        val raw = "https://example.com/app-v1.0.zip"
        val withFragment = "https://example.com/app-v1.0.zip#/outer.zip"
        assertEquals(ScoopCacheManager.downloadCacheName(raw), ScoopCacheManager.downloadCacheName(withFragment))
    }

    @Test
    fun `urlHash uses full URL for scoop-facing hashes`() {
        val raw = "https://x.com/app.exe#/dl.7z"
        val nz = "https://x.com/app.exe"
        assertNotEquals(ScoopCacheManager.urlHash(raw), ScoopCacheManager.urlHash(nz))
    }

    @Test
    fun `scoopCacheName uses full URL with fragment for Scoop compatibility`() {
        val withFrag = ScoopCacheManager.scoopCacheName("app", "1.0", "https://x.com/app.exe#/dl.7z")
        val withoutFrag = ScoopCacheManager.scoopCacheName("app", "1.0", "https://x.com/app.exe")
        // Scoop uses the full URL (including fragment) for cache key
        assertNotEquals(withFrag, withoutFrag)
    }

    @Test
    fun `downloadCacheName uses normalized URL for dedup`() {
        // Same extension scenario: hash should be normalized (same) across #fragment variations
        val withFrag = ScoopCacheManager.downloadCacheName("https://x.com/app.7z#/dl.7z")
        val withoutFrag = ScoopCacheManager.downloadCacheName("https://x.com/app.7z")
        // Internal download cache uses normalized URL so #fragment variations share the same file
        assertEquals(withFrag, withoutFrag)
    }

    // -----------------------------------------------------------------------
    // Size limit enforcement
    // -----------------------------------------------------------------------

    @Test
    fun `download succeeds when size is within limit`() = kotlinx.coroutines.runBlocking {
        // Size gate uses Content-Length; body can be small.
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/app.zip"

        server.createContext("/app.zip") { exchange ->
            when (exchange.requestMethod) {
                "HEAD" -> {
                    exchange.responseHeaders.set("Content-Length", "64")
                    exchange.sendResponseHeaders(200, -1)
                }
                "GET" -> {
                    exchange.sendResponseHeaders(200, 64)
                    exchange.responseBody.write(ByteArray(64) { 0x42 })
                    exchange.responseBody.close()
                }
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(keepCache = false, customCacheDir = tmpDir)
            val path = cacheManager.getOrDownload(url)
            assertTrue(Files.exists(path), "File should download with 1GB default limit")
            assertTrue(Files.size(path) > 0, "Downloaded file should have content")
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download rejects files exceeding size limit via Content-Length`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/content-length-test"

        val headRequested = AtomicBoolean(false)
        server.createContext("/content-length-test") { exchange ->
            when (exchange.requestMethod) {
                "HEAD" -> {
                    headRequested.set(true)
                    // Return Content-Length = 600MB (> 200MB limit)
                    exchange.responseHeaders.set("Content-Length", "600000000")
                    exchange.sendResponseHeaders(200, -1)
                }
                "GET" -> {
                    // Should never be reached if HEAD check works
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.close()
                }
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(
                keepCache = false,
                maxDownloadBytes = 200L * 1024 * 1024,
                customCacheDir = tmpDir,
            )
            val ex = assertFailsWith<IllegalStateException> {
                cacheManager.getOrDownload(url)
            }
            assertTrue(ex.message!!.contains("too large"), "Error should mention 'too large': ${ex.message}")
            assertTrue(headRequested.get(), "HEAD request should have been sent before GET")
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download rejects files exceeding size limit during streaming when Content-Length unavailable`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/streaming-test"

        server.createContext("/streaming-test") { exchange ->
            when (exchange.requestMethod) {
                "HEAD" -> {
                    // No Content-Length header → will fall through to streaming check
                    exchange.sendResponseHeaders(200, -1)
                }
                "GET" -> {
                    // Use explicit large Content-Length and stream data
                    val totalSize = 8L * 1024 * 1024 // 8MB total (above 5MB limit)
                    exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                    exchange.sendResponseHeaders(200, totalSize)
                    val chunk = ByteArray(1024 * 1024) // 1MB at a time
                    try {
                        for (i in 0 until 8) {
                            exchange.responseBody.write(chunk)
                            exchange.responseBody.flush()
                        }
                    } catch (_: Exception) {
                        // Client likely disconnected after exceeding limit — expected
                    }
                    exchange.responseBody.close()
                }
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(
                keepCache = false,
                maxDownloadBytes = 5L * 1024 * 1024, // 5MB limit for fast test
                customCacheDir = tmpDir,
            )
            val ex = assertFailsWith<IllegalStateException> {
                cacheManager.getOrDownload(url)
            }
            assertTrue(ex.message!!.contains("too large"), "Error should mention 'too large': ${ex.message}")

            // Verify no .downloading temp file remains
            val tmpFiles = Files.list(tmpDir).toList()
            assertTrue(
                tmpFiles.none { it.fileName.toString().endsWith(".downloading") },
                "Temporary .downloading file should be cleaned up on error",
            )
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download accepts files within size limit when Content-Length is provided`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/ok-test"

        server.createContext("/ok-test") { exchange ->
            when (exchange.requestMethod) {
                "HEAD" -> {
                    exchange.responseHeaders.set("Content-Length", "1024")
                    exchange.sendResponseHeaders(200, -1)
                }
                "GET" -> {
                    exchange.sendResponseHeaders(200, 1024)
                    exchange.responseBody.write(ByteArray(1024) { 0x41 })
                    exchange.responseBody.close()
                }
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(
                keepCache = false,
                maxDownloadBytes = 200L * 1024 * 1024,
                customCacheDir = tmpDir,
            )
            val path = cacheManager.getOrDownload(url)
            assertTrue(Files.exists(path), "Downloaded file should exist")
            assertTrue(Files.size(path) > 0, "Downloaded file should have content")
            assertTrue(Files.size(path) <= 1024, "Downloaded file should be <= Content-Length")
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download falls back to streaming check when HEAD returns 405`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/head-405"

        server.createContext("/head-405") { exchange ->
            when (exchange.requestMethod) {
                "HEAD" -> {
                    // HEAD not allowed (405) — fall back to streaming check
                    exchange.sendResponseHeaders(405, -1)
                }
                "GET" -> {
                    // Stream enough data to exceed test limit
                    val totalSize = 8L * 1024 * 1024
                    exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                    exchange.sendResponseHeaders(200, totalSize)
                    val chunk = ByteArray(1024 * 1024)
                    try {
                        for (i in 0 until 8) {
                            exchange.responseBody.write(chunk)
                            exchange.responseBody.flush()
                        }
                    } catch (_: Exception) { }
                    exchange.responseBody.close()
                }
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(
                keepCache = false,
                maxDownloadBytes = 5L * 1024 * 1024, // 5MB limit
                customCacheDir = tmpDir,
            )
            assertFailsWith<IllegalStateException> {
                cacheManager.getOrDownload(url)
            }
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // Download confirmation
    // -----------------------------------------------------------------------

    @Test
    fun `getOrDownload invokes downloadConfirmer before downloading`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/confirm-app.zip"
        val payload = "dummy zip content".toByteArray()

        server.createContext("/confirm-app.zip") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/zip")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
            exchange.responseBody.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(customCacheDir = tmpDir)
            var askedUrl = ""
            cacheManager.downloadConfirmer = { u, _ ->
                askedUrl = u
                true
            }

            val path = cacheManager.getOrDownload(url)
            assertTrue(Files.exists(path))
            assertEquals(url, askedUrl)
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `getOrDownload aborts download when downloadConfirmer returns false`() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/declined-app.zip"

        server.createContext("/declined-app.zip") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(ByteArray(100))
            exchange.responseBody.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val tmpDir = Files.createTempDirectory("scoop-cache-test-")
        try {
            val cacheManager = ScoopCacheManager(customCacheDir = tmpDir)
            cacheManager.downloadConfirmer = { _, _ -> false }

            val ex = assertFailsWith<IllegalStateException> {
                cacheManager.getOrDownload(url)
            }
            assertTrue(ex.message?.contains("cancelled") == true || ex.message?.contains("declined") == true)
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }
}

