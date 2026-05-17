package com.scoopai.tools.manifest

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.scoopai.core.tools.api.ManifestIo
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

@LLMDescription("Scoop manifest file I/O and validation tools")
class FileManifestIo : ManifestIo, ToolSet {

    companion object {
        private val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }

    @Tool
    @LLMDescription("Validate a Scoop manifest JSON string. Checks required fields, hash format, URL format.")
    override fun validate(
        @LLMDescription("The Scoop manifest JSON string to validate")
        json: String
    ): String {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val element = Json.parseToJsonElement(json)
            if (element !is JsonObject) {
                errors.add("Root element must be a JSON object")
                return validationResult(false, errors, warnings)
            }

            val obj = element.jsonObject

            // Check required field: version
            if (!obj.containsKey("version")) {
                errors.add("Missing required field: version")
            } else {
                val version = obj["version"]?.jsonPrimitive?.contentOrNull
                if (version.isNullOrBlank()) {
                    errors.add("Field 'version' must be a non-empty string")
                }
            }

            // Check url or architecture
            val hasUrl = obj.containsKey("url")
            val hasArchitecture = obj.containsKey("architecture")
            if (!hasUrl && !hasArchitecture) {
                errors.add("Missing required field: must have 'url' or 'architecture'")
            }

            // Validate url format if present
            obj["url"]?.let { urlElement ->
                validateUrls(urlElement, errors, "url")
            }

            // Validate architecture.url if present
            obj["architecture"]?.jsonObject?.let { arch ->
                arch["64bit"]?.jsonObject?.let { bit64 ->
                    bit64["url"]?.let { validateUrls(it, errors, "architecture.64bit.url") }
                }
                arch["32bit"]?.jsonObject?.let { bit32 ->
                    bit32["url"]?.let { validateUrls(it, errors, "architecture.32bit.url") }
                }
            }

            // Check hash or architecture.hash
            val hasHash = obj.containsKey("hash")
            val hasArchHash = obj["architecture"]?.jsonObject?.let { arch ->
                arch["64bit"]?.jsonObject?.containsKey("hash") == true ||
                    arch["32bit"]?.jsonObject?.containsKey("hash") == true
            } ?: false
            if (!hasHash && !hasArchHash) {
                warnings.add("No hash field found; consider adding 'hash' for verification")
            }

            // Validate hash format if present
            obj["hash"]?.let { hashElement ->
                validateHashes(hashElement, errors, "hash")
            }

            // Validate architecture.hash if present
            obj["architecture"]?.jsonObject?.let { arch ->
                arch["64bit"]?.jsonObject?.let { bit64 ->
                    bit64["hash"]?.let { validateHashes(it, errors, "architecture.64bit.hash") }
                }
                arch["32bit"]?.jsonObject?.let { bit32 ->
                    bit32["hash"]?.let { validateHashes(it, errors, "architecture.32bit.hash") }
                }
            }

        } catch (e: Exception) {
            errors.add("Invalid JSON: ${e.message}")
        }

        return validationResult(errors.isEmpty(), errors, warnings)
    }

    @Tool
    @LLMDescription("Write manifest JSON to a file. Creates parent directories if needed.")
    override fun write(
        @LLMDescription("The manifest JSON content to write")
        json: String,
        @LLMDescription("The file path to write the manifest to")
        path: String
    ): String {
        return try {
            val filePath = Path.of(path)
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, json)
            buildJsonObject {
                put("path", filePath.toAbsolutePath().toString())
                put("success", true)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to write manifest: ${e.message}")
                put("success", false)
            }.toString()
        }
    }

    @Tool
    @LLMDescription("Read a manifest file and return its content")
    override fun read(
        @LLMDescription("The file path of the manifest to read")
        path: String
    ): String {
        return try {
            Files.readString(Path.of(path))
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to read manifest: ${e.message}")
            }.toString()
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private fun validateUrls(urlElement: JsonElement, errors: MutableList<String>, fieldPath: String) {
        when (urlElement) {
            is JsonPrimitive -> {
                val url = urlElement.contentOrNull ?: return
                if (!url.startsWith("http")) {
                    errors.add("Field '$fieldPath' must start with http: $url")
                }
            }
            is JsonArray -> {
                urlElement.forEachIndexed { index, element ->
                    val url = element.jsonPrimitive.contentOrNull ?: return@forEachIndexed
                    if (!url.startsWith("http")) {
                        errors.add("Field '$fieldPath[$index]' must start with http: $url")
                    }
                }
            }
            else -> warnings(errors, "Field '$fieldPath' has unexpected type")
        }
    }

    private fun warnings(errors: MutableList<String>, msg: String) {
        errors.add(msg)
    }

    private fun validateHashes(hashElement: JsonElement, errors: MutableList<String>, fieldPath: String) {
        when (hashElement) {
            is JsonPrimitive -> {
                val hash = hashElement.contentOrNull ?: return
                if (!SHA256_REGEX.matches(hash)) {
                    errors.add("Field '$fieldPath' is not a valid SHA-256 hash (expected 64 hex chars): $hash")
                }
            }
            is JsonArray -> {
                hashElement.forEachIndexed { index, element ->
                    val hash = element.jsonPrimitive.contentOrNull ?: return@forEachIndexed
                    if (!SHA256_REGEX.matches(hash)) {
                        errors.add("Field '$fieldPath[$index]' is not a valid SHA-256 hash (expected 64 hex chars): $hash")
                    }
                }
            }
            else -> errors.add("Field '$fieldPath' has unexpected type")
        }
    }

    private fun validationResult(valid: Boolean, errors: List<String>, warnings: List<String>): String {
        return buildJsonObject {
            put("valid", valid)
            put("errors", buildJsonArray { errors.forEach { add(it) } })
            put("warnings", buildJsonArray { warnings.forEach { add(it) } })
        }.toString()
    }
}
