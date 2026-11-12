package com.scoopai.tools.verify

import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.tools.cache.ScoopCacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Marks a runScoop timeout; reused by fake commandRunner test seams. */
class ScoopProcessTimeoutException(message: String) : Exception(message)

/** Result of one scoop command execution */
data class ScoopProcessResult(val exitCode: Int, val output: String)

/**
 * Verifies that a manifest is installable by running the real scoop command.
 *
 * No longer a ToolSet: callers invoke it in a controlled way with an app-name
 * allowlist, and cleanup runs deterministically in finally to avoid false
 * positives from leftover installs.
 */
class ProcessScoopVerifier private constructor(
    private val commandRunner: suspend (List<String>) -> ScoopProcessResult,
    private val scoopRoot: Path?,
    private val cache: ScoopCacheManager?,
) : ScoopVerifier {

    companion object {
        const val INSTALL_TIMEOUT_MINUTES = 10L
        const val UNINSTALL_TIMEOUT_SECONDS = 60L
        const val INSTALL_BUDGET = 2

        private const val OUTPUT_CAP_CHARS = 256 * 1024
        private const val POLL_INTERVAL_MS = 200L
        private val APP_NAME_PATTERN = Regex("^[A-Za-z0-9._-]+$")

        private fun isOsWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("windows")

        /**
         * Resolve the actually executable scoop command. On Windows scoop is a
         * .cmd/.ps1 shim (not an .exe), which ProcessBuilder cannot run directly,
         * hence the cmd /c wrapper. Resolved once and shared by probe and later
         * commands.
         */
        internal fun resolveScoopCommand(
            home: Path,
            scoopEnv: String?,
            isWindows: Boolean = isOsWindows(),
        ): List<String> {
            if (!isWindows) return listOf("scoop")
            val envShim = scoopEnv?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).resolve("shims").resolve("scoop.cmd") }
            val shim = envShim ?: home.resolve("scoop").resolve("shims").resolve("scoop.cmd")
            val scoopCmd = if (Files.isRegularFile(shim)) shim else null
            return if (scoopCmd != null) listOf("cmd", "/c", scoopCmd.toString())
            else listOf("cmd", "/c", "scoop")
        }

        /** Only created after a successful `scoop --version` probe; returns null and logs the reason to stderr when scoop is unavailable */
        fun tryCreate(cache: ScoopCacheManager? = null, scoopRoot: Path? = null): ProcessScoopVerifier? =
            tryCreateDetailed(cache = cache, scoopRoot = scoopRoot).first

        /**
         * Diagnostic variant of tryCreate: also returns why scoop is unavailable so the
         * assist prompt can relay the root cause to the user. On success verifier is non-null
         * and reason is null. `probe` is for test injection only.
         */
        internal fun tryCreateDetailed(
            cache: ScoopCacheManager? = null,
            scoopRoot: Path? = null,
            probe: (List<String>) -> ScoopProcessResult = { cmd ->
                runBlocking { runProcess(cmd, timeoutMs = 30_000) }
            },
        ): Pair<ProcessScoopVerifier?, String?> {
            val resolvedHome = Path.of(System.getProperty("user.home"))
            val resolvedScoopEnv = System.getenv("SCOOP")
            val resolvedCommand = resolveScoopCommand(resolvedHome, resolvedScoopEnv)
            val cmd = resolvedCommand.joinToString(" ")

            fun fail(reason: String): Pair<ProcessScoopVerifier?, String?> {
                System.err.println("scoop verification disabled: $reason. Install tests will be skipped.")
                return null to reason
            }

            val check = try {
                probe(resolvedCommand + "--version")
            } catch (e: Exception) {
                return fail("cannot run '$cmd --version' (${e.message})")
            }
            if (check.exitCode != 0) {
                return fail("'$cmd --version' exited with ${check.exitCode}")
            }
            return ProcessScoopVerifier(
                commandRunner = { args -> runScoopCommand(resolvedCommand, *args.toTypedArray()) },
                scoopRoot = scoopRoot ?: defaultScoopRoot(),
                cache = cache,
            ) to null
        }

        /** Test seam: inject a fake commandRunner and scoopRoot */
        internal fun createForTesting(
            commandRunner: suspend (List<String>) -> ScoopProcessResult,
            scoopRoot: Path?,
            cache: ScoopCacheManager?,
        ): ProcessScoopVerifier = ProcessScoopVerifier(commandRunner, scoopRoot, cache)

        private fun defaultScoopRoot(): Path {
            val scoopEnv = System.getenv("SCOOP")
            return if (!scoopEnv.isNullOrBlank()) Path.of(scoopEnv)
            else Path.of(System.getProperty("user.home")).resolve("scoop")
        }

        internal suspend fun runScoopCommand(command: List<String>, vararg args: String): ScoopProcessResult {
            val timeoutMs = if (args.firstOrNull() == "install") {
                INSTALL_TIMEOUT_MINUTES * 60_000
            } else {
                UNINSTALL_TIMEOUT_SECONDS * 1000
            }
            return runProcess(command + args, timeoutMs)
        }

        /**
         * Streams and drains output concurrently after process start (256KB cap to
         * bound memory), polls waitFor while checking coroutine cancellation, and
         * destroyForcibly is the timeout/end-of-process backstop.
         */
        internal suspend fun runProcess(command: List<String>, timeoutMs: Long): ScoopProcessResult {
            val proc = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: IOException) {
                throw IOException(
                    "failed to start process for scoop command [${command.joinToString(" ")}]: ${e.message}",
                    e,
                )
            }
            val captured = StringBuilder()
            // blocking reads cannot be cancelled; drain on a daemon thread, which exits once the killed process closes the stream
            val drain = thread(isDaemon = true) {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    val buf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (total < OUTPUT_CAP_CHARS) {
                            val take = minOf(n, OUTPUT_CAP_CHARS - total)
                            captured.append(buf, 0, take)
                            total += take
                        }
                    }
                } catch (_: Exception) {
                }
            }
            try {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!proc.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    coroutineContext.ensureActive()
                    if (System.currentTimeMillis() >= deadline) {
                        val label = command.dropWhile { it == "scoop" || it == "cmd" || it == "/c" || it.endsWith("scoop.cmd") }
                            .firstOrNull() ?: command.first()
                        throw ScoopProcessTimeoutException(
                            "scoop $label timed out after ${timeoutMs / 1000}s"
                        )
                    }
                }
                drain.join(10_000)
                return ScoopProcessResult(proc.exitValue(), captured.toString().trim())
            } catch (e: CancellationException) {
                throw e
            } finally {
                proc.destroyForcibly()
                drain.join(5_000)
            }
        }
    }

    private var installCount = 0

    override suspend fun verifyInstall(manifestJson: String, appName: String): String =
        withContext(Dispatchers.IO) {
            if (!APP_NAME_PATTERN.matches(appName)) {
                return@withContext errorJson(appName, "Invalid app name '$appName': must match ${APP_NAME_PATTERN.pattern}")
            }
            if (installCount >= INSTALL_BUDGET) {
                return@withContext errorJson(
                    appName,
                    "install test budget exhausted ($INSTALL_BUDGET installs per session)",
                )
            }
            installCount++

            val tempDir = Files.createTempDirectory("scoop-verify-")
            val manifestFile = tempDir.resolve("$appName.json")
            try {
                Files.writeString(manifestFile, manifestJson)
                // clean up crash leftovers before install to avoid a false "already installed" positive
                safeCleanup { commandRunner(listOf("uninstall", appName)) }

                val install = commandRunner(
                    listOf("install", manifestFile.toAbsolutePath().toString(), "-s"),
                )

                prewarmCache(manifestJson, appName)

                val alreadyInstalled = install.exitCode == 0 &&
                    install.output.contains("already installed", ignoreCase = true)
                if (alreadyInstalled) {
                    buildJsonObject {
                        put("success", false)
                        put("app_name", appName)
                        put("exit_code", install.exitCode)
                        put("output", install.output)
                        put(
                            "error",
                            "Residual installation detected: scoop reports '$appName' as already installed; " +
                                "the manifest was not actually verified. Check for a stale install or bucket conflict.",
                        )
                    }.toString()
                } else {
                    buildJsonObject {
                        put("success", install.exitCode == 0)
                        put("app_name", appName)
                        put("exit_code", install.exitCode)
                        put("output", install.output)
                    }.toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScoopProcessTimeoutException) {
                errorJson(appName, "scoop command timed out: ${e.message}")
            } catch (e: Exception) {
                errorJson(appName, e.message ?: "Unknown error")
            } finally {
                withContext(NonCancellable) {
                    safeCleanup { commandRunner(listOf("uninstall", appName, "-p")) }
                    safeCleanup { deletePersistDir(appName) }
                    try { Files.deleteIfExists(manifestFile) } catch (_: Exception) {}
                    try { Files.delete(tempDir) } catch (_: Exception) {}
                }
            }
        }

    /** Standalone cleanup, no longer exposed to the LLM as a free-form parameter tool */
    suspend fun uninstall(appName: String): String = withContext(Dispatchers.IO) {
        if (!APP_NAME_PATTERN.matches(appName)) {
            return@withContext errorJson(appName, "Invalid app name '$appName': must match ${APP_NAME_PATTERN.pattern}")
        }
        val result = commandRunner(listOf("uninstall", appName))
        buildJsonObject {
            put("success", result.exitCode == 0)
            put("app_name", appName)
            put("output", result.output)
        }.toString()
    }

    /**
     * Extracts url and version from the manifest (top-level or architecture form; version arrays use the first element).
     * On parse failure returns (null, null) and the caller skips prewarm.
     */
    internal fun extractUrlAndVersion(json: String): Pair<String?, String?> = try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val version = when (val v = obj["version"]) {
            is JsonPrimitive -> v.content
            is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }
        val url = (obj["url"] as? JsonPrimitive)?.content
            ?: (obj["architecture"] as? JsonObject)?.let { arch ->
                ((arch["64bit"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content
                    ?: ((arch["32bit"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content
            }
        url to version
    } catch (_: Exception) {
        null to null
    }

    /** Finalizes the cache entry by extracted url/version after install, so re-tests of the same URL skip the download */
    private fun prewarmCache(manifestJson: String, appName: String) {
        val cache = cache ?: return
        val (url, version) = extractUrlAndVersion(manifestJson)
        if (url == null || version == null) return
        try {
            cache.finalizeCacheEntry(url, appName, version)
        } catch (_: Exception) {
        }
    }

    /** Persist-dir backstop deletion (clears crash leftovers even when scoop uninstall -p failed) */
    private fun deletePersistDir(appName: String) {
        val root = scoopRoot ?: return
        val persist = root.resolve("persist").resolve(appName)
        val persistFile = persist.toFile()
        if (persistFile.exists()) {
            try {
                persistFile.deleteRecursively()
                // also remove an empty persist parent to keep the scoop layout clean
                val parentFile = persistFile.parentFile
                if (parentFile != null && parentFile.exists() && parentFile.listFiles()?.isEmpty() == true) {
                    parentFile.deleteRecursively()
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun safeCleanup(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private fun errorJson(appName: String, error: String): String = buildJsonObject {
        put("success", false)
        put("app_name", appName)
        put("error", error)
    }.toString()
}
