package com.scoopai.tools.cache

import com.sun.net.httpserver.HttpServer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScoopCacheManagerTest {

    @Test
    fun `download rejects files exceeding size limit via Content-Length`() {
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
    fun `download rejects files exceeding size limit during streaming when Content-Length unavailable`() {
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
                "Temp .downloading file should have been cleaned up: $tmpFiles"
            )
        } finally {
            server.stop(0)
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download proceeds when Content-Length is within limit`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val url = "http://localhost:${server.address.port}/within-limit"

        server.createContext("/within-limit") { exchange ->
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
    fun `download falls back to streaming check when HEAD returns 405`() {
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
}
