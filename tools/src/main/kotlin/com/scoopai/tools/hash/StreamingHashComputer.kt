package com.scoopai.tools.hash

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.scoopai.core.tools.api.HashComputer
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@LLMDescription("Hash computation tools")
class StreamingHashComputer : HashComputer, ToolSet {

    companion object {
        private const val MAX_DOWNLOAD_BYTES: Long = 500L * 1024 * 1024 // 500 MB
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Tool
    @LLMDescription("Download a file from URL and compute its SHA-256 hash. Returns the hex-encoded hash.")
    override suspend fun computeSha256FromUrl(
        @LLMDescription("The URL of the file to download and hash")
        url: String
    ): String {
        var tempFile: Path? = null
        return try {
            tempFile = Files.createTempFile("scoop-ai-hash-", ".tmp")

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "scoop-ai/0.1.0")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                Files.deleteIfExists(tempFile)
                return buildJsonObject {
                    put("error", "HTTP ${response.statusCode()} for $url")
                }.toString()
            }

            response.body().use { input ->
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
            }

            val fileSize = Files.size(tempFile)
            if (fileSize > MAX_DOWNLOAD_BYTES) {
                Files.deleteIfExists(tempFile)
                return buildJsonObject {
                    put("error", "File too large: $fileSize bytes (max $MAX_DOWNLOAD_BYTES)")
                }.toString()
            }

            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(tempFile).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            val hashBytes = digest.digest()
            val hexHash = hashBytes.joinToString("") { "%02x".format(it) }

            buildJsonObject {
                put("url", url)
                put("sha256", hexHash)
                put("size", fileSize)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to compute SHA-256: ${e.message ?: e.javaClass.name}")
            }.toString()
        } finally {
            tempFile?.let { Files.deleteIfExists(it) }
        }
    }
}
