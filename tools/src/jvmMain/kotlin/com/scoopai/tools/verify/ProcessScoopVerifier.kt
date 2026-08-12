package com.scoopai.tools.verify

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.tools.api.ScoopVerifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@LLMDescription("Scoop installation verification tools. Use these to verify a generated manifest actually installs correctly, and to clean up after testing.")
class ProcessScoopVerifier : ScoopVerifier, ToolSet {

    companion object {
        private const val INSTALL_TIMEOUT_MINUTES = 5L
        private const val UNINSTALL_TIMEOUT_SECONDS = 30L

        /** Create a verifier only when `scoop --version` succeeds. */
        fun tryCreate(): ProcessScoopVerifier? = try {
            val check = ProcessBuilder("scoop", "--version")
                .redirectErrorStream(true)
                .start()
            check.inputStream.bufferedReader().readText()
            check.waitFor()
            if (check.exitValue() == 0) ProcessScoopVerifier() else null
        } catch (_: Exception) {
            null
        }
    }

    @Tool
    @LLMDescription("""
        Verify a Scoop manifest by installing it with scoop install.
        Writes the manifest to a temp file, runs scoop install, captures the result, then uninstalls.
        Returns JSON with success status, scoop output, and error details if it failed.
        Common failures: URL 404, extract_dir wrong, bin path not found, installer type mismatch.
    """)
    override fun verifyInstall(
        @LLMDescription("The complete Scoop manifest JSON string to verify")
        manifestJson: String?
    ): String {
        val manifestJsonNotNull = validateNotNull(manifestJson) { "Parameter 'manifestJson' is required. Provide the complete Scoop manifest JSON string." }
        val appName = extractAppName(manifestJsonNotNull)

        val tempDir = Files.createTempDirectory("scoop-verify-")
        val manifestFile = tempDir.resolve("$appName.json")

        return try {
            Files.writeString(manifestFile, manifestJsonNotNull)

            val installResult = runScoop("install", manifestFile.toAbsolutePath().toString(), "-s")
            val success = installResult.exitCode == 0

            if (success) {
                // Clean up: uninstall after successful verification
                runScoop("uninstall", appName)
            }

            buildJsonObject {
                put("success", success)
                put("app_name", appName)
                put("exit_code", installResult.exitCode)
                put("output", installResult.output)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("success", false)
                put("app_name", appName)
                put("error", e.message ?: "Unknown error")
            }.toString()
        } finally {
            try { Files.deleteIfExists(manifestFile) } catch (_: Exception) {}
            try { Files.delete(tempDir) } catch (_: Exception) {}
        }
    }

    @Tool
    @LLMDescription("Uninstall a scoop app by name. Use this to clean up after a failed verification.")
    override fun uninstall(
        @LLMDescription("The app name to uninstall")
        appName: String?
    ): String {
        val appNameNotNull = validateNotNull(appName) { "Parameter 'appName' is required. Provide the app name to uninstall." }
        val result = runScoop("uninstall", appNameNotNull)
        return buildJsonObject {
            put("success", result.exitCode == 0)
            put("output", result.output)
        }.toString()
    }

    private fun extractAppName(json: String): String {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            // Try to get name from bin field
            val bin = obj["bin"]
            when {
                bin == null -> "scoop-app"
                bin is JsonPrimitive -> {
                    val name = bin.content.substringBeforeLast('.')
                    name.ifBlank { "scoop-app" }
                }
                bin is JsonArray -> {
                    val first = bin.firstOrNull()?.let {
                        if (it is JsonArray) it.firstOrNull()?.jsonPrimitive?.content
                        else it.jsonPrimitive?.content
                    } ?: "scoop-app"
                    first.substringBeforeLast('.').ifBlank { "scoop-app" }
                }
                else -> "scoop-app"
            }
        } catch (_: Exception) {
            "scoop-app"
        }
    }

    private fun runScoop(vararg args: String): ProcessResult {
        val command = listOf("scoop", *args)
        val proc = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = proc.inputStream.bufferedReader().readText()
        val finished = if (args.firstOrNull() == "install") {
            proc.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } else {
            proc.waitFor(UNINSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        if (!finished) {
            proc.destroyForcibly()
            return ProcessResult(-1, "Process timed out")
        }

        return ProcessResult(proc.exitValue(), output.trim())
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
