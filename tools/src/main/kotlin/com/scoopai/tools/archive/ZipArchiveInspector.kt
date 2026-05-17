package com.scoopai.tools.archive

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.scoopai.core.tools.api.ArchiveInspector
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

@LLMDescription("Archive inspection tools for ZIP and installer analysis")
class ZipArchiveInspector : ArchiveInspector, ToolSet {

    companion object {
        private const val MAX_DOWNLOAD_BYTES: Long = 200L * 1024 * 1024 // 200 MB
        private const val MAX_DEPTH = 3
    }

    // ---------------------------------------------------------------------------
    // Download helper
    // ---------------------------------------------------------------------------

    private fun downloadToTempFile(url: String): Path {
        val tempFile = Files.createTempFile("scoop-ai-zip-", ".tmp")
        return try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "scoop-ai/0.1.0")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                Files.deleteIfExists(tempFile)
                throw IllegalStateException("HTTP ${response.statusCode()} for $url")
            }
            response.body().use { input ->
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
            }
            val fileSize = Files.size(tempFile)
            if (fileSize > MAX_DOWNLOAD_BYTES) {
                Files.deleteIfExists(tempFile)
                throw IllegalStateException("File too large: $fileSize bytes (max $MAX_DOWNLOAD_BYTES)")
            }
            tempFile
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }
    }

    // ---------------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------------

    @Tool
    @LLMDescription("Download a ZIP file and list its directory structure. Returns JSON array of file paths.")
    override suspend fun listZipContents(
        @LLMDescription("URL of the ZIP file to inspect")
        url: String
    ): String {
        var tempFile: Path? = null
        return try {
            val file = downloadToTempFile(url)
            tempFile = file

            val entries = mutableListOf<String>()
            Files.newInputStream(file).use { fis ->
                ZipInputStream(fis).use { zis ->
                    generateSequence { zis.nextEntry }
                        .forEach { entry ->
                            val name = entry.name
                            val depth = name.count { it == '/' }
                            if (depth <= MAX_DEPTH) {
                                entries.add(name)
                            }
                            zis.closeEntry()
                        }
                }
            }

            buildJsonArray {
                for (entry in entries) {
                    add(entry)
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to list ZIP contents: ${e.message ?: e.javaClass.name}")
                put("stack", e.stackTraceToString().take(500))
            }.toString()
        } finally {
            tempFile?.let { Files.deleteIfExists(it) }
        }
    }

    @Tool
    @LLMDescription("Download a ZIP file and find all .exe files. Returns JSON array of executable paths.")
    override suspend fun findExecutables(
        @LLMDescription("URL of the ZIP file to inspect")
        url: String
    ): String {
        var tempFile: Path? = null
        return try {
            val file = downloadToTempFile(url)
            tempFile = file

            val executables = mutableListOf<String>()
            Files.newInputStream(file).use { fis ->
                ZipInputStream(fis).use { zis ->
                    generateSequence { zis.nextEntry }
                        .forEach { entry ->
                            if (entry.name.endsWith(".exe", ignoreCase = true)) {
                                executables.add(entry.name)
                            }
                            zis.closeEntry()
                        }
                }
            }

            buildJsonArray {
                for (exe in executables) {
                    add(exe)
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to find executables: ${e.message ?: e.javaClass.name}")
                put("stack", e.stackTraceToString().take(500))
            }.toString()
        } finally {
            tempFile?.let { Files.deleteIfExists(it) }
        }
    }

    @Tool
    @LLMDescription("Download file header and detect installer type (innosetup/nsis/unknown)")
    override suspend fun detectInstallerType(
        @LLMDescription("URL of the file to check")
        url: String
    ): String {
        return try {
            // Download first 1MB only via streaming
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "scoop-ai/0.1.0")
                .GET()
                .build()

            // Read up to 1MB of response body
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return buildJsonObject {
                    put("error", "HTTP ${response.statusCode()} for $url")
                }.toString()
            }

            val body = response.body()
            val headerBytes = body.toByteArray(Charsets.ISO_8859_1)
            val headerContent = if (headerBytes.size > 1024 * 1024) {
                String(headerBytes, 0, 1024 * 1024, Charsets.ISO_8859_1)
            } else {
                body
            }

            val installerType = when {
                headerContent.contains("Inno Setup", ignoreCase = true) -> "innosetup"
                headerContent.contains("Nullsoft", ignoreCase = true) -> "nsis"
                else -> "unknown"
            }

            buildJsonObject {
                put("url", url)
                put("installer_type", installerType)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to detect installer type: ${e.message}")
            }.toString()
        }
    }
}
