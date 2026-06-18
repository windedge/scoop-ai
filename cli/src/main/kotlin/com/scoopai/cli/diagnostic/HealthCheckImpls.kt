package com.scoopai.cli.diagnostic

import com.scoopai.cli.Version
import java.io.File

// ---------------------------------------------------------------------------
// 1. JdkVersionCheck
// ---------------------------------------------------------------------------

/**
 * Checks that the JVM version is >= 17.
 *
 * @param versionProvider Returns the Java version string (default: [System.getProperty]"java.version").
 */
class JdkVersionCheck(
    private val versionProvider: () -> String = { System.getProperty("java.version") },
) : HealthCheck {
    override val name = "jdk-version"

    override suspend fun run(): HealthResult {
        val versionStr = versionProvider()
        val major = versionStr
            .substringBefore(".")
            .takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
        return when {
            major == null -> HealthResult(Status.FAIL, "Cannot parse Java version: $versionStr")
            major >= 21 -> HealthResult(Status.OK, "Java $versionStr (>= 21, recommended)")
            major >= 17 -> HealthResult(Status.OK, "Java $versionStr (>= 17)")
            else -> HealthResult(Status.FAIL, "Java $versionStr (< 17, minimum required is 17)")
        }
    }
}

// ---------------------------------------------------------------------------
// 2. OsCheck
// ---------------------------------------------------------------------------

/**
 * Reports the operating system name and version.
 *
 * @param osNameProvider Returns the OS name (default: [System.getProperty]"os.name").
 * @param osVersionProvider Returns the OS version (default: [System.getProperty]"os.version").
 */
class OsCheck(
    private val osNameProvider: () -> String = { System.getProperty("os.name") },
    private val osVersionProvider: () -> String = { System.getProperty("os.version") },
) : HealthCheck {
    override val name = "os"

    override suspend fun run(): HealthResult {
        val osName = osNameProvider()
        val osVersion = osVersionProvider()
        return HealthResult(Status.OK, "$osName $osVersion")
    }
}

// ---------------------------------------------------------------------------
// 3. ScoopInstalledCheck
// ---------------------------------------------------------------------------

/**
 * Checks that Scoop is installed by running `scoop --version`.
 *
 * @param scoopRunner Runs the scoop command (default: runs "scoop --version" via [ProcessBuilder]).
 */
class ScoopInstalledCheck(
    private val scoopRunner: () -> ScoopResult = ::runScoopVersion,
) : HealthCheck {
    override val name = "scoop-installed"

    override suspend fun run(): HealthResult {
        val result = scoopRunner()
        return if (result.success) {
            HealthResult(Status.OK, "Scoop ${result.output}")
        } else {
            HealthResult(Status.WARN, "Scoop not found or not working (${result.errorMessage})")
        }
    }
}

data class ScoopResult(val success: Boolean, val output: String, val errorMessage: String = "")

internal fun runScoopVersion(): ScoopResult {
    return try {
        val process = ProcessBuilder("scoop", "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode == 0) {
            ScoopResult(true, output.lines().firstOrNull() ?: output)
        } else {
            ScoopResult(false, output, "exit code $exitCode")
        }
    } catch (e: Exception) {
        ScoopResult(false, "", e.message ?: e.javaClass.simpleName)
    }
}

// ---------------------------------------------------------------------------
// 4. ApiKeyCheck
// ---------------------------------------------------------------------------

/**
 * Checks whether an API key is configured via any supported env var.
 * Never leaks the actual key — only shows a masked prefix.
 *
 * @param envVars Environment variable map (default: [System.getenv]).
 */
class ApiKeyCheck(
    private val envVars: Map<String, String> = System.getenv(),
) : HealthCheck {
    override val name = "api-key"

    /** Known env var names that can hold an API key, in priority order. */
    private val keyEnvVars = listOf(
        "SCOOP_AI_API_KEY",
        "DEEPSEEK_API_KEY",
        "OPENAI_API_KEY",
        "ANTHROPIC_API_KEY",
        "OPENROUTER_API_KEY",
    )

    override suspend fun run(): HealthResult {
        val found = keyEnvVars.firstOrNull { envVars[it]?.isNotBlank() == true }
        return if (found != null) {
            val raw = envVars[found]!!
            val masked = maskApiKey(raw)
            HealthResult(Status.OK, "Found in \$$found ($masked)")
        } else {
            HealthResult(Status.WARN, "No API key found (set SCOOP_AI_API_KEY or provider-specific env var)")
        }
    }

    companion object {
        /**
         * Masks an API key for safe display.
         * Shows prefix (e.g. "sk-") + first 2 chars + "..." + last 4 chars when key >= 8 chars.
         * For shorter keys, only shows "set".
         */
        fun maskApiKey(key: String): String {
            val trimmed = key.trim()
            if (trimmed.length < 8) return "set"
            val prefixEnd = if (trimmed.startsWith("sk-")) 3 else 0
            val prefix = trimmed.substring(0, prefixEnd + 2.coerceAtMost(trimmed.length - 4))
            val suffix = trimmed.substring(trimmed.length - 4)
            return "$prefix...$suffix"
        }
    }
}

// ---------------------------------------------------------------------------
// 5. CacheDirCheck
// ---------------------------------------------------------------------------

/**
 * Checks that the Scoop cache directory exists and is writable.
 *
 * @param cacheDirProvider Returns the cache directory path (default: ~/scoop/cache).
 */
class CacheDirCheck(
    private val cacheDirProvider: () -> File = {
        File(System.getProperty("user.home"), "scoop/cache")
    },
) : HealthCheck {
    override val name = "cache-dir"

    override suspend fun run(): HealthResult {
        val dir = cacheDirProvider()
        return when {
            !dir.exists() -> HealthResult(Status.WARN, "${dir.absolutePath} does not exist")
            !dir.canWrite() -> HealthResult(Status.WARN, "${dir.absolutePath} is not writable")
            else -> HealthResult(Status.OK, "${dir.absolutePath} (writable)")
        }
    }
}

// ---------------------------------------------------------------------------
// 6. WritePermissionCheck
// ---------------------------------------------------------------------------

/**
 * Checks that the current directory (or bucket/) is writable.
 *
 * @param writableDirs Directories to check (default: current dir + bucket/).
 */
class WritePermissionCheck(
    private val writableDirs: () -> List<File> = {
        listOf(
            File(".").absoluteFile,
            File("bucket"),
        )
    },
) : HealthCheck {
    override val name = "write-permission"

    override suspend fun run(): HealthResult {
        val dirs = writableDirs()
        val unwritable = dirs.filter { it.exists() && !it.canWrite() }
        return if (unwritable.isEmpty()) {
            HealthResult(Status.OK, "Current directory is writable")
        } else {
            val names = unwritable.joinToString(", ") { it.absolutePath }
            HealthResult(Status.FAIL, "Not writable: $names")
        }
    }
}

// ---------------------------------------------------------------------------
// 7. VersionCheck
// ---------------------------------------------------------------------------

/**
 * Reports the scoop-ai version and commit.
 */
class VersionCheck : HealthCheck {
    override val name = "version"

    override suspend fun run(): HealthResult {
        return HealthResult(Status.OK, "scoop-ai ${Version.VERSION} (commit ${Version.COMMIT})")
    }
}

// ---------------------------------------------------------------------------
// Default health check list
// ---------------------------------------------------------------------------

/**
 * Returns the standard list of health checks in display order.
 */
fun defaultHealthChecks(): List<HealthCheck> = listOf(
    JdkVersionCheck(),
    OsCheck(),
    ScoopInstalledCheck(),
    ApiKeyCheck(),
    CacheDirCheck(),
    WritePermissionCheck(),
    VersionCheck(),
)
