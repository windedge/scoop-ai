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

/** runScoop 超时标记，fake commandRunner 测试缝复用同一异常 */
class ScoopProcessTimeoutException(message: String) : Exception(message)

/** scoop 命令执行结果 */
data class ScoopProcessResult(val exitCode: Int, val output: String)

/**
 * 通过真实 scoop 命令验证 manifest 可安装的服务。
 *
 * 不再是 ToolSet：由上层以受控方式调用，appName 走白名单校验，
 * 清理在 finally 中确定性执行，防止残留安装造成假阳性。
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
         * 解析实际可执行的 scoop 命令。Windows 上 scoop 是 shims 里的 .cmd/
         * .ps1（非 .exe），ProcessBuilder 直跑会 IOException，故用 cmd /c 包装。
         * 解析结果一次性缓存，探测与后续命令共用。
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

        /** scoop --version 探测成功才创建；scoop 不可用时返回 null 并输出原因到 stderr */
        fun tryCreate(cache: ScoopCacheManager? = null, scoopRoot: Path? = null): ProcessScoopVerifier? {
            val resolvedHome = Path.of(System.getProperty("user.home"))
            val resolvedScoopEnv = System.getenv("SCOOP")
            val resolvedCommand = resolveScoopCommand(resolvedHome, resolvedScoopEnv)
            val check = try {
                runBlocking { runProcess(resolvedCommand + "--version", timeoutMs = 30_000) }
            } catch (e: Exception) {
                System.err.println(
                    "scoop verification disabled: cannot run '${resolvedCommand.joinToString(" ")} --version' " +
                        "(${e.message}). Install tests will be skipped."
                )
                return null
            }
            if (check.exitCode != 0) {
                System.err.println(
                    "scoop verification disabled: '${resolvedCommand.joinToString(" ")} --version' " +
                        "exited with ${check.exitCode}. Install tests will be skipped."
                )
                return null
            }
            return ProcessScoopVerifier(
                commandRunner = { args -> runScoopCommand(resolvedCommand, *args.toTypedArray()) },
                scoopRoot = scoopRoot ?: defaultScoopRoot(),
                cache = cache,
            )
        }

        /** 测试缝：注入 fake commandRunner 与 scoopRoot */
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
         * 启动进程后并发流式 drain 输出（256KB cap 防内存爆炸），
         * 轮询 waitFor 同时检查协程取消；超时或结束时 destroyForcibly 兜底。
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
            // 阻塞读无法被协程取消，用后台线程 drain；进程被杀后流关闭线程自然退出
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
                // install 前清理崩溃残留，避免 already installed 假阳性
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

    /** 独立清理方法，不再作为对 LLM 的自由参数工具暴露 */
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
     * 从 manifest 提取顶层或 architecture 形态的 url 与 version（version 数组取首元素）。
     * 解析失败返回 (null, null)，调用方跳过 prewarm。
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

    /** install 完成后按提取的 url/version finalize 缓存条目，使同 URL 重测免下载 */
    private fun prewarmCache(manifestJson: String, appName: String) {
        val cache = cache ?: return
        val (url, version) = extractUrlAndVersion(manifestJson)
        if (url == null || version == null) return
        try {
            cache.finalizeCacheEntry(url, appName, version)
        } catch (_: Exception) {
        }
    }

    /** persist 目录兜底删除（scoop uninstall -p 失败时仍能清掉崩溃残留） */
    private fun deletePersistDir(appName: String) {
        val root = scoopRoot ?: return
        val persist = root.resolve("persist").resolve(appName)
        val persistFile = persist.toFile()
        if (persistFile.exists()) {
            try {
                persistFile.deleteRecursively()
                // 空的 persist 父目录一并移除，保持 scoop 布局干净
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
