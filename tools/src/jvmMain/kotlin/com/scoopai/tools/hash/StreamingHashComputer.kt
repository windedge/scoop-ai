package com.scoopai.tools.hash

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.tools.api.HashComputer
import com.scoopai.tools.cache.ScoopCacheManager
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest

@LLMDescription("Hash computation tools")
class StreamingHashComputer(
    private val cache: ScoopCacheManager,
) : HashComputer, ToolSet {

    @Tool
    @LLMDescription("Download a file from URL and compute its SHA-256 hash. Returns the hex-encoded hash.")
    override suspend fun computeSha256FromUrl(
        @LLMDescription("The URL of the file to download and hash")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide the URL of the file to hash." }
        return try {
            val file = cache.getOrDownload(urlNotNull)

            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            val hashBytes = digest.digest()
            val hexHash = hashBytes.joinToString("") { "%02x".format(it) }
            val fileSize = Files.size(file)

            buildJsonObject {
                put("url", urlNotNull)
                put("sha256", hexHash)
                put("size", fileSize)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to compute SHA-256: ${e.message ?: e.javaClass.name}")
            }.toString()
        }
    }
}
