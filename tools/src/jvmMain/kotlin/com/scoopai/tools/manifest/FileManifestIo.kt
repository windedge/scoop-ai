package com.scoopai.tools.manifest

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
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
        json: String?
    ): String {
        val jsonNotNull = validateNotNull(json) { "Parameter 'json' is required. Provide the complete Scoop manifest JSON string." }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val element = Json.parseToJsonElement(jsonNotNull)
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
                arch["arm64"]?.jsonObject?.let { bitarm ->
                    bitarm["hash"]?.let { validateHashes(it, errors, "architecture.arm64.hash") }
                }
            }

            // ── Semantic validation ───────────────────────────────
            validateBin(obj, errors, warnings)
            validateArchitectureBlock(obj, errors, warnings)
            validateExtractDir(obj, warnings)
            validateVersion(obj, errors)

        } catch (e: Exception) {
            errors.add("Invalid JSON: ${e.message}")
        }

        return validationResult(errors.isEmpty(), errors, warnings)
    }

    @Tool
    @LLMDescription("Write manifest JSON to a file. Creates parent directories if needed. If path is not provided, auto-derives from the manifest content (bucket/<appname>.json).")
    override fun write(
        @LLMDescription("The manifest JSON content to write")
        json: String?,
        @LLMDescription("The file path to write the manifest to. If omitted, auto-derives as bucket/<appname>.json")
        path: String?
    ): String {
        val jsonNotNull = validateNotNull(json) { "Parameter 'json' is required. Provide the complete Scoop manifest JSON string." }
        return try {
            val resolvedPath = if (!path.isNullOrBlank()) {
                Path.of(path)
            } else {
                val appName = extractAppName(jsonNotNull)
                Path.of("bucket").resolve("$appName.json")
            }
            resolvedPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(resolvedPath, jsonNotNull)
            buildJsonObject {
                put("path", resolvedPath.toAbsolutePath().toString())
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
        path: String?
    ): String {
        val pathNotNull = validateNotNull(path) { "Parameter 'path' is required. Provide the file path of the manifest to read." }
        return try {
            Files.readString(Path.of(pathNotNull))
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to read manifest: ${e.message}")
            }.toString()
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private fun extractAppName(json: String): String {
        return try {
            val element = Json.parseToJsonElement(json)
            if (element !is JsonObject) return "manifest"

            // Try to get name from bin field
            val bin = element.jsonObject["bin"]
            val binName = when {
                bin == null -> null
                bin is JsonPrimitive -> bin.content.substringBeforeLast('.')
                bin is JsonArray -> {
                    val first = bin.firstOrNull()
                    when (first) {
                        is JsonArray -> first.firstOrNull()?.jsonPrimitive?.contentOrNull
                        is JsonPrimitive -> first.contentOrNull
                        else -> null
                    }?.substringBeforeLast('.')
                }
                else -> null
            }

            when {
                !binName.isNullOrBlank() -> binName
                else -> {
                    val homepage = element.jsonObject["homepage"]?.jsonPrimitive?.contentOrNull
                    homepage?.substringAfterLast("/")?.substringBefore(".") ?: "manifest"
                }
            }
        } catch (_: Exception) {
            "manifest"
        }
    }

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

    // ---------------------------------------------------------------------------
    // Semantic validation
    // ---------------------------------------------------------------------------

    /** Validates `bin` field structure. */
    private fun validateBin(obj: JsonObject, errors: MutableList<String>, warnings: MutableList<String>) {
        val bin = obj["bin"] ?: return // bin is optional
        when (bin) {
            is JsonPrimitive -> {
                if (!bin.isString) {
                    errors.add("Field 'bin' must be a string or array")
                } else {
                    val value = bin.content
                    if (value.isBlank()) errors.add("Field 'bin' must not be empty")
                    if (!value.endsWith(".exe") && !value.endsWith(".ps1") && !value.contains(".")) {
                        warnings.add("Field 'bin' value '$value' does not look like an executable path")
                    }
                }
            }
            is JsonArray -> {
                bin.forEachIndexed { index, element ->
                    when (element) {
                        is JsonPrimitive -> {
                            val value = element.contentOrNull ?: return@forEachIndexed
                            if (value.isBlank()) errors.add("Field 'bin[$index]' must not be empty")
                        }
                        is JsonArray -> {
                            if (element.isEmpty()) {
                                errors.add("Field 'bin[$index]' must not be an empty array")
                            } else if (element.size > 3) {
                                warnings.add("Field 'bin[$index]' has more than 3 elements (expected: [exe, alias, args?])")
                            }
                        }
                        else -> errors.add("Field 'bin[$index]' has unexpected type")
                    }
                }
            }
            else -> errors.add("Field 'bin' must be a string or array")
        }
    }

    /** Validates that each architecture block has both `url` and `hash`. */
    private fun validateArchitectureBlock(obj: JsonObject, errors: MutableList<String>, warnings: MutableList<String>) {
        val arch = obj["architecture"]?.jsonObject ?: return
        for ((archName, archElement) in arch) {
            if (archElement !is JsonObject) continue
            val hasUrl = archElement.containsKey("url")
            val hasHash = archElement.containsKey("hash")
            if (hasUrl && !hasHash) {
                warnings.add("architecture.$archName has 'url' but no 'hash'")
            }
            if (hasHash && !hasUrl) {
                errors.add("architecture.$archName has 'hash' but no 'url'")
            }
            // Validate url and hash inside each arch block
            archElement["url"]?.let { validateUrls(it, errors, "architecture.$archName.url") }
            archElement["hash"]?.let { validateHashes(it, errors, "architecture.$archName.hash") }
        }
    }

    /** Warns if `extract_dir` uses "." or empty string (should be omitted instead). */
    private fun validateExtractDir(obj: JsonObject, warnings: MutableList<String>) {
        val extractDir = obj["extract_dir"]?.jsonPrimitive?.contentOrNull
        if (extractDir == "." || extractDir == "") {
            warnings.add("Field 'extract_dir' should be omitted when the exe is at the archive root, not set to '$extractDir'")
        }
        // Check extract_dir in architecture blocks too
        obj["architecture"]?.jsonObject?.let { arch ->
            for ((archName, archElement) in arch) {
                if (archElement !is JsonObject) continue
                val archExtractDir = archElement["extract_dir"]?.jsonPrimitive?.contentOrNull
                if (archExtractDir == "." || archExtractDir == "") {
                    warnings.add("architecture.$archName.extract_dir should be omitted when the exe is at the archive root")
                }
            }
        }
    }

    /** Validates version format (no leading 'v'). */
    private fun validateVersion(obj: JsonObject, errors: MutableList<String>) {
        val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: return
        if (version.startsWith("v")) {
            errors.add("Field 'version' must not have a leading 'v': $version")
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
