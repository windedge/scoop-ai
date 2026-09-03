package com.scoopai.tools.verify

import com.scoopai.tools.cache.ScoopCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ProcessScoopVerifierTest {

    private val manifestJson = """
        {"version":"1.0","url":"https://example.com/app-1.0.zip","bin":"app.exe"}
    """.trimIndent()

    // -----------------------------------------------------------------------
    // 测试缝：fake commandRunner 记录命令序列，返回可控结果
    // -----------------------------------------------------------------------

    private class FakeRunner {
        val commands = mutableListOf<List<String>>()
        var installExit = 0
        var installOutput = "install ok"
        var failInstallWith: Exception? = null

        val runner: suspend (List<String>) -> ScoopProcessResult = { args ->
            commands += args.toList()
            when (args.firstOrNull()) {
                "install" -> failInstallWith?.let { throw it }
                    ?: ScoopProcessResult(installExit, installOutput)
                else -> ScoopProcessResult(0, "ok")
            }
        }
    }

    private fun newVerifier(
        runner: suspend (List<String>) -> ScoopProcessResult,
        scoopRoot: Path? = null,
        cache: ScoopCacheManager? = null,
    ): ProcessScoopVerifier = ProcessScoopVerifier.createForTesting(runner, scoopRoot, cache)

    private fun jsonField(json: String, field: String): String =
        Json.parseToJsonElement(json).jsonObject[field]!!.jsonPrimitive.content

    private fun hasField(json: String, field: String): Boolean =
        Json.parseToJsonElement(json).jsonObject.containsKey(field)

    // -----------------------------------------------------------------------
    // appName 白名单
    // -----------------------------------------------------------------------

    @Test
    fun `app name with illegal characters is rejected without running any command`() = runBlocking {
        val fake = FakeRunner()
        val verifier = newVerifier(fake.runner)

        val result = verifier.verifyInstall(manifestJson, "app; rm -rf /")

        assertEquals("false", jsonField(result, "success"))
        assertTrue(jsonField(result, "error").contains("Invalid app name"))
        assertTrue(fake.commands.isEmpty(), "no scoop command should run for an invalid app name")
    }

    @Test
    fun `uninstall rejects an app name with illegal characters`() = runBlocking {
        val fake = FakeRunner()
        val verifier = newVerifier(fake.runner)

        val result = verifier.uninstall("app & del")

        assertEquals("false", jsonField(result, "success"))
        assertTrue(fake.commands.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 命令顺序与清理
    // -----------------------------------------------------------------------

    @Test
    fun `uninstall runs before install to clear crash leftovers`() = runBlocking {
        val fake = FakeRunner()
        val verifier = newVerifier(fake.runner)

        verifier.verifyInstall(manifestJson, "app")

        assertEquals(listOf("uninstall", "app"), fake.commands[0])
        assertEquals("install", fake.commands[1].first())
    }

    @Test
    fun `successful install ends with uninstall -p and persist dir deleted`() = runBlocking {
        val fake = FakeRunner()
        val scoopRoot = Files.createTempDirectory("scoop-verify-root-")
        val persistDir = scoopRoot.resolve("persist").resolve("app")
        Files.createDirectories(persistDir)
        Files.writeString(persistDir.resolve("settings.json"), "{}")
        val verifier = newVerifier(fake.runner, scoopRoot = scoopRoot)

        val result = verifier.verifyInstall(manifestJson, "app")

        assertEquals("true", jsonField(result, "success"))
        assertEquals(listOf("uninstall", "app", "-p"), fake.commands.last())
        assertFalse(Files.exists(persistDir), "persist/<app> must be deleted as fallback cleanup")
        assertFalse(Files.exists(scoopRoot.resolve("persist")), "empty persist parent is removed too")
    }

    @Test
    fun `already installed output is judged as inconclusive failure`() = runBlocking {
        val fake = FakeRunner()
        fake.installExit = 0
        fake.installOutput = "INFO  app is already installed. Skipping installation."
        val verifier = newVerifier(fake.runner)

        val result = verifier.verifyInstall(manifestJson, "app")

        assertEquals("false", jsonField(result, "success"))
        assertTrue(jsonField(result, "error").contains("already installed", ignoreCase = true))
    }

    @Test
    fun `failed install still cleans up and surfaces output`() = runBlocking {
        val fake = FakeRunner()
        fake.installExit = 1
        fake.installOutput = "ERROR Download failed (404)"
        val verifier = newVerifier(fake.runner)

        val result = verifier.verifyInstall(manifestJson, "app")

        assertEquals("false", jsonField(result, "success"))
        assertEquals("ERROR Download failed (404)", jsonField(result, "output"))
        assertTrue(hasField(result, "exit_code"))
        assertEquals(listOf("uninstall", "app", "-p"), fake.commands.last())
    }

    @Test
    fun `install timeout produces timeout error JSON and still cleans up`() = runBlocking {
        val fake = FakeRunner()
        fake.failInstallWith = ScoopProcessTimeoutException("scoop install timed out after 600s")
        val verifier = newVerifier(fake.runner)

        val result = verifier.verifyInstall(manifestJson, "app")

        assertEquals("false", jsonField(result, "success"))
        assertTrue(jsonField(result, "error").contains("timed out"))
        assertEquals(listOf("uninstall", "app", "-p"), fake.commands.last())
    }

    // -----------------------------------------------------------------------
    // 会话级 install 预算
    // -----------------------------------------------------------------------

    @Test
    fun `third install attempt hits session budget`() = runBlocking {
        val fake = FakeRunner()
        val verifier = newVerifier(fake.runner)

        verifier.verifyInstall(manifestJson, "app")
        verifier.verifyInstall(manifestJson, "app")
        val countBefore = fake.commands.size
        val result = verifier.verifyInstall(manifestJson, "app")

        assertEquals("false", jsonField(result, "success"))
        assertTrue(jsonField(result, "error").contains("install test budget exhausted"))
        assertEquals(countBefore, fake.commands.size, "budget exhausted must not run any command")
    }

    // -----------------------------------------------------------------------
    // S1 双实例：assist 与 review verifier 是不同实例，各有独立 install 预算
    // -----------------------------------------------------------------------

    @Test
    fun `two verifier instances are distinct and have independent install budgets`() = runBlocking {
        val fakeReview = FakeRunner()
        val fakeAssist = FakeRunner()
        val review = newVerifier(fakeReview.runner)
        val assist = newVerifier(fakeAssist.runner)

        assertNotSame(review, assist, "review and assist must be distinct verifier instances")

        // Run each just under its own budget; the other's budget is untouched.
        review.verifyInstall(manifestJson, "app")
        review.verifyInstall(manifestJson, "app")
        // Review is now at its 2-install budget; the third would exhaust it.
        val reviewResult = review.verifyInstall(manifestJson, "app")
        assertEquals("false", jsonField(reviewResult, "success"))
        assertTrue(jsonField(reviewResult, "error").contains("budget exhausted"))

        // Assist has its own budget: it can still run two installs.
        val assistResult1 = assist.verifyInstall(manifestJson, "app")
        assertEquals("true", jsonField(assistResult1, "success"))
        assertEquals("app", jsonField(assistResult1, "app_name"))
        val assistResult2 = assist.verifyInstall(manifestJson, "app")
        assertEquals("true", jsonField(assistResult2, "success"))
    }

    // -----------------------------------------------------------------------
    // prewarm：finalizeCacheEntry 用提取的 url/version/appName 调用
    // -----------------------------------------------------------------------

    @Test
    fun `prewarm finalizes cache entry from top-level url and version`() = runBlocking {
        val fake = FakeRunner()
        val cacheDir = Files.createTempDirectory("scoop-verify-cache-")
        val url = "https://example.com/app-1.0.zip"
        val hash = ScoopCacheManager.urlHash(url)
        Files.writeString(cacheDir.resolve("$hash.zip"), "payload")
        val cache = ScoopCacheManager(customCacheDir = cacheDir)
        val verifier = newVerifier(fake.runner, cache = cache)

        verifier.verifyInstall(manifestJson, "app")

        val finalized = cacheDir.resolve("app#1.0#$hash.zip")
        assertTrue(Files.exists(finalized), "download-phase cache file must be renamed to app#version#hash form")
    }

    @Test
    fun `prewarm is skipped when manifest has no extractable url`() = runBlocking {
        val fake = FakeRunner()
        val cacheDir = Files.createTempDirectory("scoop-verify-cache-")
        val cache = ScoopCacheManager(customCacheDir = cacheDir)
        val verifier = newVerifier(fake.runner, cache = cache)

        val result = verifier.verifyInstall("""{"version":"1.0"}""", "app")

        assertEquals("true", jsonField(result, "success"))
        assertEquals(0, Files.list(cacheDir).use { it.count() }, "no cache file should appear without url")
    }

    // -----------------------------------------------------------------------
    // manifest url/version 提取
    // -----------------------------------------------------------------------

    @Test
    fun `extraction reads top-level url and plain version`() {
        val verifier = newVerifier(FakeRunner().runner)
        val (url, version) = verifier.extractUrlAndVersion(manifestJson)
        assertEquals("https://example.com/app-1.0.zip", url)
        assertEquals("1.0", version)
    }

    @Test
    fun `extraction falls back to architecture 64bit then 32bit url`() {
        val verifier = newVerifier(FakeRunner().runner)
        val json = """
            {"version":"2.0","architecture":{
              "32bit":{"url":"https://x.com/a32.zip"},
              "64bit":{"url":"https://x.com/a64.zip"}
            }}
        """.trimIndent()
        val (url, version) = verifier.extractUrlAndVersion(json)
        assertEquals("https://x.com/a64.zip", url)
        assertEquals("2.0", version)
    }

    @Test
    fun `extraction reads first element of version array`() {
        val verifier = newVerifier(FakeRunner().runner)
        val (_, version) = verifier.extractUrlAndVersion(
            """{"version":["3.1","abc123"],"url":"https://x.com/a.zip"}"""
        )
        assertEquals("3.1", version)
    }

    @Test
    fun `extraction returns nulls on unparseable manifest`() {
        val verifier = newVerifier(FakeRunner().runner)
        val (url, version) = verifier.extractUrlAndVersion("not json at all")
        assertEquals(null, url)
        assertEquals(null, version)
    }

    // -----------------------------------------------------------------------
    // runProcess 进程级行为（真实进程，Windows CI 友好：ping 做长睡眠）
    // -----------------------------------------------------------------------

    @Test
    @Tag("process")
    fun `runProcess times out instead of hanging forever`() {
        val start = System.currentTimeMillis()
        val error = assertFailsWith<ScoopProcessTimeoutException> {
            runBlocking {
                // ping -n 30 约 29 秒，timeout 1s 必须先返回
                ProcessScoopVerifier.runProcess(
                    listOf("cmd", "/c", "ping", "-n", "30", "127.0.0.1"),
                    timeoutMs = 1_000,
                )
            }
        }
        assertTrue(error.message!!.contains("timed out"))
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 15_000, "timeout must fire at ~1s, took ${elapsed}ms (old impl would block ~29s)")
    }

    @Test
    @Tag("process")
    fun `runProcess destroys process and rethrows on coroutine cancellation`() = runBlocking {
        withTimeout(10_000) {
            // job body 会阻塞线程，必须跑在独立多线程池，否则 runBlocking event loop 卡死
            val job = launch(Dispatchers.Default) {
                ProcessScoopVerifier.runProcess(
                    listOf("cmd", "/c", "ping", "-n", "30", "127.0.0.1"),
                    timeoutMs = 60_000,
                )
            }
            kotlinx.coroutines.delay(700)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
    }
}
