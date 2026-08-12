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
class FileManifestIo(
    private val defaultAppName: String? = null,
    @LLMDescription("Persist field policy: auto=no validation, force=must have persist, none=must NOT have persist")
    private val persistMode: String = "auto",
) : ManifestIo, ToolSet {

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

            var obj = element.jsonObject

            // Unwrap {"status":"...","manifest":"{...}"} wrapper that LLM sometimes produces
            if (obj.containsKey("manifest") && !obj.containsKey("version")) {
                val inner = obj["manifest"]?.jsonPrimitive?.contentOrNull
                if (inner != null) {
                    obj = Json.parseToJsonElement(inner).jsonObject
                }
            }

            // Check required field: version
            if (!obj.containsKey("version")) {
                errors.add("Missing required field: version")
            } else {
                val version = obj["version"]?.jsonPrimitive?.contentOrNull
                if (version.isNullOrBlank()) {
                    errors.add("Field 'version' must be a non-empty string")
                }
            }

            // Check required field: license
            if (!obj.containsKey("license") || obj["license"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                errors.add("Missing required field: license (SPDX identifier like MIT, GPL-3.0-or-later, etc.) — do NOT omit or leave empty")
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

            // Missing hash is an error (blocks install / triggers refine)
            val hasHash = obj.containsKey("hash")
            val hasArchHash = obj["architecture"]?.jsonObject?.values?.any {
                (it as? JsonObject)?.containsKey("hash") == true
            } == true
            when {
                !hasHash && !hasArchHash ->
                    errors.add("No hash field found; each url must have a corresponding hash (SHA-256)")
                obj.containsKey("url") && !hasHash ->
                    errors.add("Top-level 'url' is present but 'hash' is missing")
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

            // ── Semantic validation (per-function try/catch, one failure doesn't abort others) ──
            safeValidate("bin", warnings) { validateBin(obj, errors, warnings) }
            safeValidate("architecture block", warnings) { validateArchitectureBlock(obj, errors, warnings) }
            safeValidate("pre_install rename", warnings) { validatePreInstallRename(obj, errors) }
            safeValidate("GUI bin", warnings) { validateGuiBin(obj, errors, warnings) }
            safeValidate("extract_dir", warnings) { validateExtractDir(obj, warnings) }
            safeValidate("autoupdate v-prefix", warnings) { validateAutoupdateVPrefix(obj, warnings) }
            safeValidate("version", warnings) { validateVersion(obj, errors) }
            safeValidate("unknown fields", warnings) { validateUnknownFields(obj, errors) }
            safeValidate("template variables in static fields", warnings) { validateTemplateVariablesInStaticFields(obj, errors) }
            safeValidate("exe URL fragment", warnings) { validateExeUrlFragment(obj, warnings) }
            safeValidate("installer script escaping", warnings) { validateInstallerScriptEscaping(obj, errors) }
            safeValidate("persist policy", warnings) { validatePersistPolicy(obj, errors) }

        } catch (e: Exception) {
            errors.add("Invalid JSON: ${e.message}")
            return validationResult(errors.isEmpty(), errors, warnings)
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
                val appName = defaultAppName ?: extractAppName(jsonNotNull)
                Path.of("bucket").resolve("$appName.json")
            }
            resolvedPath.parent?.let { Files.createDirectories(it) }

            // Pretty-print and normalize extract_dir slashes
            val prettyJson = formatManifest(jsonNotNull)
            val normalized = normalizeForScoopBucket(prettyJson)
            Files.writeString(resolvedPath, normalized)

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
    // Scoop bucket style normalization (4 constraints)
    // ---------------------------------------------------------------------------

    /**
     * Normalizes a manifest string to satisfy Scoop bucket CI file-style constraints:
     * 1. Ends with exactly one CRLF newline
     * 2. All line endings are CRLF (no bare LF, no mixed styles)
     * 3. No UTF-8 BOM (handled by [Files.writeString] with default UTF_8 charset)
     * 4. No trailing whitespace on any line
     */
    private fun normalizeForScoopBucket(manifest: String): String {
        // Step 1: Unify all line endings to LF first (normalize mixed CRLF/LF)
        val lfEndings = manifest.replace("\r\n", "\n")

        // Step 2: Remove trailing whitespace from each line
        val noTrailingWs = lfEndings.split("\n").joinToString("\n") { line ->
            line.replace(Regex("[ \\t]+$"), "")
        }

        // Step 3: Convert all LFs to CRLF
        val crlfEndings = noTrailingWs.replace("\n", "\r\n")

        // Step 4: Ensure exactly one trailing CRLF (trim any extra then add one)
        val trimmed = crlfEndings.trimEnd('\r', '\n')
        return trimmed + "\r\n"
    }

    // ---------------------------------------------------------------------------
    // Formatting helper
    // ---------------------------------------------------------------------------

    private val prettyJson = kotlinx.serialization.json.Json {
        prettyPrint = true
        prettyPrintIndent = "    "
    }

    /** Pretty-print JSON and normalize extract_dir slashes. */
    private fun formatManifest(rawJson: String): String {
        // Unwrap {"status":"...","manifest":"{...}"} wrapper that LLM sometimes produces
        val unwrapped = try {
            val element = Json.parseToJsonElement(rawJson)
            if (element is JsonObject && element.containsKey("manifest") && !element.containsKey("version")) {
                val inner = element["manifest"]?.jsonPrimitive?.content
                if (inner != null) inner else rawJson
            } else {
                rawJson
            }
        } catch (_: Exception) {
            rawJson
        }

        val element = try {
            Json.parseToJsonElement(unwrapped)
        } catch (_: Exception) {
            // LLM output may be truncated - try auto-fixing missing closing braces
            val openCount = unwrapped.count { it == '{' }
            val closeCount = unwrapped.count { it == '}' }
            if (openCount > closeCount) {
                val fixed = unwrapped.trimEnd() + "}".repeat(openCount - closeCount)
                try {
                    val fixedElement = Json.parseToJsonElement(fixed)
                    return prettyJson.encodeToString(JsonElement.serializer(), fixedElement)
                } catch (_: Exception) {
                    // Auto-fix didn't help, return original
                }
            }
            return unwrapped
        }
        return prettyJson.encodeToString(JsonElement.serializer(), element)
    }

    private fun extractAppName(json: String): String {
        return try {
            val element = Json.parseToJsonElement(json)
            if (element !is JsonObject) return "manifest"

            // Priority 1: explicit bin field (most reliable)
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
            if (!binName.isNullOrBlank() && binName != "app") return binName.lowercase()

            // Priority 2: homepage → use the LAST path segment (repo name, not org)
            val homepage = element.jsonObject["homepage"]?.jsonPrimitive?.contentOrNull
            val repoName = homepage?.substringAfterLast("/")?.substringBefore("?")?.trim()
            if (!repoName.isNullOrBlank() && repoName.contains(".").not()) return repoName.lowercase()

            // Priority 3: description-based fallback
            "manifest"
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
            else -> errors.add("Field '$fieldPath' has unexpected type")
        }
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

    /**
     * Flags pre_install scripts that use Rename-Item to rename versioned executables.
     * Also detects top-level bin with architecture-specific executable names.
     */
    private fun validatePreInstallRename(obj: JsonObject, errors: MutableList<String>) {
        // First check: pre_install with Rename-Item for versioned exe
        val preInstall = obj["pre_install"]
        if (preInstall != null) {
            val preInstallTexts = when (preInstall) {
                is JsonPrimitive -> listOf(preInstall.content)
                is JsonArray -> preInstall.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
            for (script in preInstallTexts) {
                if ("Rename-Item" in script && ("*.exe" in script || "-*.exe" in script)) {
                    errors.add(
                        "pre_install uses Rename-Item to rename a versioned exe. " +
                        "Put bin INSIDE each architecture.{64bit|32bit} block instead, " +
                        "e.g. \"architecture\": { \"64bit\": { \"bin\": [[\"app-${'$'}version-x86_64.exe\", \"app\"]] } }. " +
                        "Do NOT use a top-level bin list with per-architecture exe names -- " +
                        "Scoop only downloads the current system's architecture, " +
                        "so the other architecture's exe will not exist."
                    )
                    return
                }
            }
        }

        // Second check: top-level bin with architecture-specific exe names
        // while manifest has multiple architecture blocks (wrong placement).
        val archObj = obj["architecture"]?.jsonObject ?: return
        if (archObj.keys.count { it.endsWith("bit") } < 2) return
        val bin = obj["bin"] ?: return
        val archHints = setOf("x86_64", "i686", "amd64", "win32", "win64", "aarch64", "arm64", "i386", "x64")
        val binNames = mutableListOf<String>()
        when (bin) {
            is JsonPrimitive -> binNames.add(bin.content.lowercase())
            is JsonArray -> {
                for (entry in bin) {
                    when (entry) {
                        is JsonPrimitive -> binNames.add(entry.content.lowercase())
                        is JsonArray -> entry.firstOrNull()?.jsonPrimitive?.contentOrNull?.let {
                            binNames.add(it.lowercase())
                        }
                        else -> {}
                    }
                }
            }
            else -> {}
        }
        for (name in binNames) {
            if (archHints.any { name.contains(it) }) {
                errors.add(
                    "Top-level bin contains architecture-specific exe names (e.g. '$name'). " +
                    "Put bin INSIDE each architecture block (architecture.64bit.bin), not at the top level."
                )
                return
            }
        }
    }

    /**
     * Warns when `bin` references the same executable as `shortcuts`.
     * GUI applications launched via shortcut typically don't need `bin`.
     */
    private fun validateGuiBin(obj: JsonObject, errors: MutableList<String>, warnings: MutableList<String>) {
        val shortcuts = obj["shortcuts"]?.jsonArray ?: return
        val bin = obj["bin"] ?: return

        // Collect exe names from shortcuts (first element of each entry)
        val shortcutExes = mutableSetOf<String>()
        for (entry in shortcuts) {
            val arr = entry.jsonArray
            val exe = arr.firstOrNull()?.jsonPrimitive?.contentOrNull?.substringAfterLast('\\')?.substringAfterLast('/')?.lowercase()
            if (!exe.isNullOrBlank()) shortcutExes.add(exe)
        }

        // Collect exe names from bin
        val binExes = mutableSetOf<String>()
        when (bin) {
            is JsonPrimitive -> {
                val name = bin.content.substringAfterLast('\\').substringAfterLast('/').lowercase()
                if (name.isNotBlank()) binExes.add(name)
            }
            is JsonArray -> {
                for (entry in bin) {
                    when (entry) {
                        is JsonPrimitive -> {
                            val name = entry.content.substringAfterLast('\\').substringAfterLast('/').lowercase()
                            if (name.isNotBlank()) binExes.add(name)
                        }
                        is JsonArray -> {
                            val name = entry.firstOrNull()?.jsonPrimitive?.contentOrNull?.substringAfterLast('\\')?.substringAfterLast('/')?.lowercase()
                            if (!name.isNullOrBlank()) binExes.add(name)
                        }
                        else -> {} // ignore
                    }
                }
            }
            else -> {} // ignore
        }

        val overlap = binExes.intersect(shortcutExes)
        if (overlap.isNotEmpty()) {
            // If every bin entry is also in shortcuts with no extra CLI-only exe,
            // this is likely a GUI-only app and bin is unnecessary.
            // Use WARNING only - some dual-mode apps (terminals, IDEs) legitimately need both.
            // The LLM should decide based on semantic context, not a structural rule.
            if (overlap.size == binExes.size) {
                warnings.add(
                    "bin references ${overlap.joinToString()} which is also in shortcuts. " +
                    "If this is a GUI-only application, consider removing 'bin'. " +
                    "If it is dual-mode (terminal, IDE, editor), keep 'bin'."
                )
            } else {
                warnings.add(
                    "bin references ${overlap.joinToString()} which is also in shortcuts. " +
                    "If this is a GUI-only application, consider removing those 'bin' entries."
                )
            }
        }
    }

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

    /** Validates that each architecture block has both `url` and `hash`, and no top-level-only fields. */
    private fun validateArchitectureBlock(obj: JsonObject, errors: MutableList<String>, warnings: MutableList<String>) {
        val arch = obj["architecture"]?.jsonObject ?: return
        for ((archName, archElement) in arch) {
            if (archElement !is JsonObject) continue
            val hasUrl = archElement.containsKey("url")
            val hasHash = archElement.containsKey("hash")
            if (hasUrl && !hasHash) {
                errors.add("architecture.$archName has 'url' but no 'hash'")
            }
            if (hasHash && !hasUrl) {
                errors.add("architecture.$archName has 'hash' but no 'url'")
            }
            // Validate url and hash inside each arch block
            archElement["url"]?.let { validateUrls(it, errors, "architecture.$archName.url") }
            archElement["hash"]?.let { validateHashes(it, errors, "architecture.$archName.hash") }
            // Reject top-level-only fields inside architecture blocks
            // Note: "bin" is allowed inside architecture blocks for per-arch versioned executables
            val topLevelOnly = setOf("shortcuts", "persist", "env_set", "checkver", "autoupdate", "notes", "pre_install", "post_install")
            for (field in topLevelOnly) {
                if (archElement.containsKey(field)) {
                    errors.add("Field '$field' MUST be at the top level of the manifest, NOT inside architecture.$archName. Move it out of the architecture block.")
                }
            }
        }
    }

    /** Warns if `extract_dir` uses "." or empty string (should be omitted instead). */
    private fun validateExtractDir(obj: JsonObject, warnings: MutableList<String>) {
        fun checkExtractDir(path: String, fieldPath: String) {
            if (path == "." || path == "") {
                warnings.add("Field '$fieldPath' should be omitted when the exe is at the archive root, not set to '$path'")
            }
            if (path.contains('/')) {
                warnings.add("Field '$fieldPath' uses forward slashes ('$path'). Use backslashes only: '${path.replace('/', '\\')}')")
            }
        }
        stringListOrNull(obj["extract_dir"])?.forEach { checkExtractDir(it, "extract_dir") }
        val hasTopLevelExtractDir = obj.containsKey("extract_dir")
        obj["architecture"]?.jsonObject?.let { arch ->
            var anyArchExtractDir = false
            for ((archName, archElement) in arch) {
                if (archElement !is JsonObject) continue
                stringListOrNull(archElement["extract_dir"])?.forEach {
                    anyArchExtractDir = true
                    checkExtractDir(it, "architecture.$archName.extract_dir")
                }
            }
            if (hasTopLevelExtractDir && anyArchExtractDir) {
                warnings.add(
                    "Top-level 'extract_dir' conflicts with per-architecture 'extract_dir'. " +
                    "If extract_dir differs by architecture, remove top-level extract_dir. " +
                    "If extract_dir is the same for all, remove per-arch extract_dir."
                )
            }
        }
    }

    /**
     * Checks that autoupdate URL templates preserve the `v` prefix when
     * the static download URL contains `v<version>`.
     *
     * Common failure: LLM generates `\$version/...` instead of `v\$version/...`
     * when the actual release URL path uses `v1.2.3/...`.
     */
    private fun validateAutoupdateVPrefix(obj: JsonObject, warnings: MutableList<String>) {
        val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: return
        val autoupdate = obj["autoupdate"]?.jsonObject ?: return

        val autoupdateArch = autoupdate["architecture"]?.jsonObject
        val staticArch = obj["architecture"]?.jsonObject

        fun getUrl(archBlock: JsonObject?, archName: String): String? =
            firstUrlOrNull(archBlock?.get(archName)?.jsonObject?.get("url"))

        // Kotlin string templates: "v$version" interpolates the local variable,
        // "v\$version" is the literal string v$version.
        val vConcrete = "v$version"       // e.g. "v1.47.2"
        val vTemplate = "v\$version"      // literal "v$version" template
        val bareTemplate = "\$version"    // literal "$version" template
        for (archName in listOf("64bit", "32bit", "arm64")) {
            val staticUrl = getUrl(staticArch, archName) ?: continue
            val autoupdateUrl = getUrl(autoupdateArch, archName) ?: continue

            if (staticUrl.contains(vConcrete) && !autoupdateUrl.contains(vTemplate)) {
                warnings.add(
                    "autoupdate.architecture.$archName.url lost 'v' prefix: " +
                    "static URL contains '$vConcrete' but autoupdate template has '$bareTemplate' without 'v'. " +
                    "Fix: change '$bareTemplate' to '$vTemplate' in the autoupdate URL."
                )
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

    /** Known Scoop manifest top-level fields. */
    private val KNOWN_SCOOP_FIELDS = setOf(
        "version", "description", "homepage", "license",
        "url", "hash", "extract_dir", "extract_to",
        "architecture", "bin", "shortcuts", "persist",
        "pre_install", "post_install", "uninstaller",
        "installer", "innosetup", "notes",
        "checkver", "autoupdate", "depends",
        "suggest", "conflict", "env_add_path", "env_set",
    )

    /** Detects fields not recognized by Scoop. */
    private fun validateUnknownFields(obj: JsonObject, errors: MutableList<String>) {
        val unknown = obj.keys.filter { it !in KNOWN_SCOOP_FIELDS }
        for (field in unknown) {
            errors.add("Unknown field '$field' is not a standard Scoop manifest field. Remove it.")
        }
    }

    /** Validates persist field against the configured policy (force/none/auto). */
    private fun validatePersistPolicy(obj: JsonObject, errors: MutableList<String>) {
        val hasPersist = obj.containsKey("persist")
        when (persistMode) {
            "force" -> {
                if (!hasPersist) {
                    errors.add("Persist policy is 'force' but the manifest has no 'persist' field. You MUST add a 'persist' field identifying config/data directories or files.")
                }
            }
            "none" -> {
                if (hasPersist) {
                    errors.add("Persist policy is 'none' but the manifest contains a 'persist' field. Remove the 'persist' field entirely.")
                }
            }
            // auto: no validation
        }
    }

    /**
     * Warns if a URL ends with .exe but lacks a `#/` fragment (like #/dl.7z or #/app.exe).
     * Such manifests will fail because Scoop treats raw .exe URLs as portable executables,
     * not installers, and the "bin" path will point to the wrong filename.
     */
    private fun validateExeUrlFragment(obj: JsonObject, warnings: MutableList<String>) {
        fun checkUrl(urlVal: String, path: String) {
            // URL already has a fragment → ok
            if (urlVal.contains("#/")) return
            if (urlVal.endsWith(".exe", ignoreCase = true)) {
                // Raw .exe without fragment - could be portable or installer
                // Only warn if there's no other indication this is intentional
                if (!obj.containsKey("innosetup") && !obj.containsKey("installer")) {
                    warnings.add(
                        "Field '$path' ends with .exe without a #/ fragment. " +
                            "For NSIS/InnoSetup installers, add #/dl.7z. For portable EXEs, add #/app.exe. " +
                            "If this is a standalone portable executable, the #/ fragment is recommended for clean shim names."
                    )
                }
            }
        }

        // Check top-level url
        stringListOrNull(obj["url"])?.forEach { checkUrl(it, "url") }
        // Check architecture urls
        obj["architecture"]?.jsonObject?.forEach { (arch, archVal) ->
            stringListOrNull((archVal as? JsonObject)?.get("url"))?.forEach {
                checkUrl(it, "architecture.$arch.url")
            }
        }
    }

    /**
     * Detects `$version` / `$matchHead` template variables in static fields
     * (outside `autoupdate`). These cause install failures because Scoop
     * only substitutes them during autoupdate, not during normal install.
     */
    private fun validateTemplateVariablesInStaticFields(obj: JsonObject, errors: MutableList<String>) {
        val templateVars = Regex("\\$(?:version|matchHead|matchTail|dotVersion|underscoreVersion)")
        // Check top-level fields (excluding autoupdate)
        for ((key, value) in obj) {
            if (key == "autoupdate") continue
            checkTemplateVarsRecursive(value, key, templateVars, errors)
        }
    }

    private fun checkTemplateVarsRecursive(
        element: JsonElement,
        path: String,
        regex: Regex,
        errors: MutableList<String>,
    ) {
        when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    val content = element.content
                    if (regex.containsMatchIn(content)) {
                        errors.add(
                            "Field '$path' contains template variable (\$version/\$matchHead) " +
                                "outside autoupdate. This will not be substituted at install time and will cause errors. " +
                                "Use the actual value instead."
                        )
                    }
                }
            }
            is JsonObject -> {
                for ((key, value) in element) {
                    checkTemplateVarsRecursive(value, "$path.$key", regex, errors)
                }
            }
            is JsonArray -> {
                element.forEachIndexed { idx, value ->
                    checkTemplateVarsRecursive(value, "$path[$idx]", regex, errors)
                }
            }
            is JsonNull -> {}
        }
    }

    /**
     * Validates that PowerShell variables inside installer.script are properly escaped.
     *
     * `$PLUGINSDIR` (and similar NSIS-internal names) are literal directory names,
     * not PowerShell variables. Without backtick escaping, PowerShell expands them
     * to empty strings, causing "file not found" errors during install.
     */
    private fun validateInstallerScriptEscaping(obj: JsonObject, errors: MutableList<String>) {
        // Literal PowerShell variable names that need backtick escaping
        val psLiteralVars = listOf("\$PLUGINSDIR")
        fun checkScriptLines(lines: List<String>, path: String) {
            for (line in lines) {
                for (varName in psLiteralVars) {
                    // Match $varName that is NOT preceded by backtick
                    val pattern = Regex("(?<!`)(" + Regex.escape(varName) + ")")
                    if (pattern.containsMatchIn(line)) {
                        errors.add(
                            "Field '$path' contains unescaped '$varName'. " +
                            "In PowerShell this will be expanded to empty string. " +
                            "Use backtick: `$varName (e.g. `\$PLUGINSDIR)"
                        )
                    }
                }
            }
        }
        // Check architecture.*.installer.script
        obj["architecture"]?.jsonObject?.forEach { (archName, archVal) ->
            val installer = (archVal as? JsonObject)?.get("installer")?.jsonObject ?: return@forEach
            val script = installer["script"]?.jsonArray ?: return@forEach
            val lines = script.mapNotNull { it.jsonPrimitive.contentOrNull }
            checkScriptLines(lines, "architecture.$archName.installer.script")
        }
        // Also check top-level pre_install
        obj["pre_install"]?.jsonArray?.let { arr ->
            val lines = arr.mapNotNull { it.jsonPrimitive.contentOrNull }
            checkScriptLines(lines, "pre_install")
        }
    }

    /** Wraps a sub-validator in try/catch so one bad sub-validator doesn't abort the entire validation. */
    private fun safeValidate(name: String, warnings: MutableList<String>, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            warnings.add("Internal validation error in $name: ${e.message}")
        }
    }

    /** Reads a JsonElement that may be a string or array of strings, returning null if absent. */
    private fun stringListOrNull(element: JsonElement?): List<String>? {
        if (element == null) return null
        return when (element) {
            is JsonPrimitive -> listOf(element.content)
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> null
        }
    }

    /** Returns the first URL from an element that may be a string or array, null if absent. */
    private fun firstUrlOrNull(element: JsonElement?): String? {
        if (element == null) return null
        return when (element) {
            is JsonPrimitive -> element.content
            is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.content
            else -> null
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
