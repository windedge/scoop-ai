package com.scoopai.tools.archive

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.agent.tools.ToolCallBudget
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private val nsisLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/** Text-like file extensions that should be scanned by searchTextInArchive. */
private val TEXT_EXTENSIONS = setOf(
    ".cs", ".rs", ".go", ".ts", ".js", ".jsx", ".tsx",
    ".json", ".toml", ".yaml", ".yml", ".xml", ".md",
    ".py", ".rb", ".java", ".kt", ".scala", ".c", ".cpp", ".h", ".hpp",
    ".sh", ".bat", ".ps1", ".cfg", ".ini", ".conf",
    ".txt", ".html", ".css", ".scss", ".less",
    ".mod", ".sum", ".lock", ".env", ".properties",
)

/** Result of probing a nested archive (archive within an archive). */
private data class NestedArchiveInfo(
    val name: String,
    val files: List<String>,
)

/** Extensions of archive files we can recursively inspect. */
private val ARCHIVE_EXTENSIONS = setOf(".zip", ".7z")

private fun isArchiveFile(name: String): Boolean {
    val lower = name.lowercase()
    return ARCHIVE_EXTENSIONS.any { lower.endsWith(it) }
}

@LLMDescription("Archive inspection tools for ZIP, MSI and installer analysis")
class ZipArchiveInspector(
    private val cache: ScoopCacheManager,
    /** Optional per-run budget to limit expensive tool calls. */
    private val budget: ToolCallBudget? = null,
) : ArchiveInspector, ToolSet {

    companion object {
        private const val MAX_DEPTH = 3
        private const val MAX_VERIFICATION_SIZE_BYTES = 100L * 1024 * 1024 // 100MB
    }

    // -----------------------------------------------------------------------
    // URL type detection
    // -----------------------------------------------------------------------

    private fun fileType(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")
        return when {
            path.endsWith(".msi", ignoreCase = true) -> "msi"
            path.endsWith(".zip", ignoreCase = true) -> "zip"
            path.endsWith(".7z", ignoreCase = true) -> "7z"
            path.endsWith(".tar.gz", ignoreCase = true) ||
                path.endsWith(".tgz", ignoreCase = true) -> "tar"
            path.endsWith(".exe", ignoreCase = true) -> "exe"
            else -> "unknown"
        }
    }

    // -----------------------------------------------------------------------
    // Tool: listArchiveContents (unified ZIP + MSI)
    // -----------------------------------------------------------------------

    @Tool
    @LLMDescription("Download an archive (ZIP, MSI, etc.) and list its file paths. Auto-detects archive type from URL. Returns JSON with file list.")
    override suspend fun listArchiveContents(
        @LLMDescription("URL of the archive to inspect")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required." }
        return try {
            when (fileType(urlNotNull)) {
                "msi" -> listMsi(urlNotNull)
                "zip" -> listZip(urlNotNull)
                "7z" -> list7z(urlNotNull)
                "tar" -> listTar(urlNotNull)
                "exe" -> listExeWithDetection(urlNotNull)
                else -> listZip(urlNotNull) // try ZIP as fallback
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to list archive contents: ${e.message ?: e.javaClass.name}")
            }.toString()
        }
    }

    // -----------------------------------------------------------------------
    // Tool: findExecutables (unified ZIP + MSI)
    // -----------------------------------------------------------------------

    @Tool
    @LLMDescription("Find all .exe files inside an archive (ZIP, MSI, etc.). Auto-detects archive type. Returns JSON array of exe paths.")
    override suspend fun findExecutables(
        @LLMDescription("URL of the archive to inspect")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required." }
        return try {
            val files = when (fileType(urlNotNull)) {
                "msi" -> listMsiPaths(urlNotNull)
                "tar" -> listTarPaths(urlNotNull)
                "exe" -> listExePathsWithDetection(urlNotNull)
                "7z" -> list7zPaths(urlNotNull)
                else -> listZipPaths(urlNotNull)
            }
            val exes = files.filter { it.substringAfterLast('/').endsWith(".exe", ignoreCase = true) }

            buildJsonArray { for (exe in exes) add(exe) }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to find executables: ${e.message ?: e.javaClass.name}")
            }.toString()
        }
    }

    // -----------------------------------------------------------------------
    // Tool: detectInstallerType
    // -----------------------------------------------------------------------

    @Tool
    @LLMDescription("Detect installer type from URL extension or file content. Returns zip/msi/innosetup/nsis/unknown.")
    override suspend fun detectInstallerType(
        @LLMDescription("URL of the file to check")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required." }
        return try {
            val type = fileType(urlNotNull)
            if (type == "msi" || type == "zip" || type == "7z" || type == "tar") {
                return buildJsonObject {
                    put("url", urlNotNull)
                    put("installer_type", type)
                }.toString()
            }

            // Lightweight: only download first 1MB for installer type detection.
            // Falls back to full download only when needed for deeper inspection
            // (InnoSetup verification, NSIS inner files enumeration).
            val headerBytes = cache.peekHeader(urlNotNull, 1024 * 1024)
            val headerContent = String(headerBytes, Charsets.ISO_8859_1)

            val suspectedType = when {
                headerContent.contains("Inno Setup", ignoreCase = true) -> "innosetup"
                headerContent.contains("Nullsoft", ignoreCase = true) -> "nsis"
                else -> "unknown"
            }

            // Deeper verification needs the full file — skip for very large files (>100MB)
            // where downloading the full file just for verification is wasteful.
            // peekHeader has already confirmed the installer type from the first 1MB.
            val installerType = when (suspectedType) {
                "innosetup" -> {
                    val contentLength = cache.getContentLength(urlNotNull)
                    if (contentLength > MAX_VERIFICATION_SIZE_BYTES) {
                        "innosetup"
                    } else {
                        val file = cache.getOrDownload(urlNotNull)
                        val canList = tryInnounpList(file)
                        if (canList) "innosetup" else "innosetup_unsupported"
                    }
                }
                "nsis" -> {
                    val contentLength = cache.getContentLength(urlNotNull)
                    if (contentLength > MAX_VERIFICATION_SIZE_BYTES) {
                        "nsis"
                    } else {
                        val file = cache.getOrDownload(urlNotNull)
                        val canList = testNsisExtractability(file)
                        if (canList) "nsis" else "nsis_unsupported"
                    }
                }
                else -> "unknown"
            }

            val result = buildJsonObject {
                put("url", urlNotNull)
                put("installer_type", installerType)
                if (installerType == "innosetup_unsupported") {
                    put("warning", "Inno Setup version not supported by innounp. Scoop install will fail with 'innosetup: true'. Consider using the .zip asset instead, or ask the user.")
                }
                if (installerType == "nsis_unsupported") {
                    put("warning", "NSIS installer cannot be extracted by 7z. Scoop's \"#/dl.7z\" trick will NOT work. Consider using a different asset (ZIP, MSI) or adding a silent install script.")
                }
                // If NSIS, include inner file details for the AI
                if (installerType == "nsis") {
                    val file = cache.getOrDownload(urlNotNull)
                    val innerFiles = try7zNsisContent(file)
                    if (innerFiles.isNotEmpty()) {
                        put("nsis_type", if (innerFiles.any { it.contains("\$PLUGINSDIR", ignoreCase = true) }) "electron" else "standard")
                        put("inner_files", buildJsonArray { for (f in innerFiles) add(f) })
                        val exes = innerFiles.filter { it.endsWith(".exe", ignoreCase = true) }
                        if (exes.isNotEmpty()) {
                            put("inner_exes", buildJsonArray { for (e in exes) add(e) })
                        }
                    }
                }
            }
            return result.toString()
        } catch (e: Exception) {
            buildJsonObject { put("error", "Failed to detect installer type: ${e.message}") }.toString()
        }
    }

    // -----------------------------------------------------------------------
    // Tool: searchTextInArchive
    // -----------------------------------------------------------------------

    @Tool
    @LLMDescription("Search for text patterns inside a source code archive (zip/tar.gz) WITHOUT extracting to disk. Scans only text-like files. Returns JSON with matching files and line snippets.")
    override suspend fun searchTextInArchive(
        @LLMDescription("URL of the source code archive (zip/tar.gz)")
        url: String?,
        @LLMDescription("Comma-separated regex patterns to search for, e.g. 'XDG_CONFIG_HOME,electron-store,app.getPath'")
        patterns: String?,
        @LLMDescription("Comma-separated glob patterns to restrict file types, e.g. '*.json,*.rs,*.go'. Empty = all text files.")
        includeGlobs: String?,
        @LLMDescription("Maximum number of match results (default 15)")
        maxResults: Int,
        @LLMDescription("Maximum file size to scan in bytes (default 262144 = 256KB)")
        maxFileSizeBytes: Long,
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide the URL of a source code archive." }
        val patternsNotNull = validateNotNull(patterns) { "Parameter 'patterns' is required. Provide comma-separated regex patterns to search for." }

        // Check budget before making HTTP call
        if (budget != null && !budget.tryAcquire("searchTextInArchive", ToolCallBudget.SEARCH_TEXT_IN_ARCHIVE_LIMIT)) {
            return ToolCallBudget.exhaustedMessage("searchTextInArchive", ToolCallBudget.SEARCH_TEXT_IN_ARCHIVE_LIMIT)
        }

        return try {
            val patternList = patternsNotNull.split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (patternList.isEmpty()) {
                return buildJsonObject { put("error", "No valid patterns provided.") }.toString()
            }

            val compiledPatterns = patternList.mapNotNull { pat ->
                try { Regex(pat) to pat } catch (_: Exception) { null }
            }
            if (compiledPatterns.isEmpty()) {
                return buildJsonObject { put("error", "No valid regex patterns in: $patternsNotNull") }.toString()
            }

            val globList = includeGlobs?.split(',')?.map { it.trim().removePrefix("*.") }?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()

            val effectiveMaxResults = if (maxResults <= 0) 15 else maxResults
            val effectiveMaxFileSize = if (maxFileSizeBytes <= 0) 256 * 1024 else maxFileSizeBytes

            val matches = searchZipForPatterns(
                url = urlNotNull,
                patterns = compiledPatterns,
                extensionFilter = globList,
                maxResults = effectiveMaxResults,
                maxFileSizeBytes = effectiveMaxFileSize,
            )
            matches.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to search archive: ${e.message ?: e.javaClass.name}")
            }.toString()
        }
    }

    // -----------------------------------------------------------------------
    // searchTextInArchive internals
    // -----------------------------------------------------------------------

    private suspend fun searchZipForPatterns(
        url: String,
        patterns: List<Pair<Regex, String>>,
        extensionFilter: Set<String>,
        maxResults: Int,
        maxFileSizeBytes: Long,
    ): JsonObject {
        val file = cache.getOrDownload(url)
        val matches = mutableListOf<JsonObject>()
        var filesScanned = 0
        val maxFiles = 500 // Safety limit

        val type = fileType(url)
        when (type) {
            "zip" -> searchZipStream(file, patterns, extensionFilter, maxResults, maxFileSizeBytes, maxFiles, matches) { filesScanned = it }
            "tar" -> searchTarStream(file, patterns, extensionFilter, maxResults, maxFileSizeBytes, maxFiles, matches) { filesScanned = it }
            else -> {
                // Try zip as fallback
                try {
                    searchZipStream(file, patterns, extensionFilter, maxResults, maxFileSizeBytes, maxFiles, matches) { filesScanned = it }
                } catch (_: Exception) {
                    return buildJsonObject {
                        put("error", "Unsupported archive type: $type")
                        put("url", url)
                    }
                }
            }
        }

        return buildJsonObject {
            put("url", url)
            put("files_scanned", filesScanned)
            put("patterns_used", buildJsonArray { patterns.forEach { (_, name) -> add(name) } })
            put("match_count", matches.size)
            put("matches", buildJsonArray { matches.forEach { add(it) } })
        }
    }

    private fun searchZipStream(
        file: java.nio.file.Path,
        patterns: List<Pair<Regex, String>>,
        extensionFilter: Set<String>,
        maxResults: Int,
        maxFileSizeBytes: Long,
        maxFiles: Int,
        matches: MutableList<JsonObject>,
        onScanned: (Int) -> Unit,
    ) {
        var scanned = 0
        Files.newInputStream(file).use { fis ->
            ZipInputStream(fis).use { zis ->
                generateSequence { zis.nextEntry }
                    .takeWhile { matches.size < maxResults && scanned < maxFiles }
                    .forEach { entry ->
                        if (entry.isDirectory) {
                            zis.closeEntry()
                            return@forEach
                        }
                        val name = entry.name
                        val ext = name.substringAfterLast('.', "")
                        // Skip binary files
                        if (ext.isNotEmpty() && ext !in TEXT_EXTENSIONS && "." + ext !in TEXT_EXTENSIONS) {
                            zis.closeEntry()
                            return@forEach
                        }
                        // Apply extension filter
                        if (extensionFilter.isNotEmpty() && ext !in extensionFilter) {
                            zis.closeEntry()
                            return@forEach
                        }
                        // Skip large files
                        if (entry.size > maxFileSizeBytes && entry.size > 0) {
                            zis.closeEntry()
                            return@forEach
                        }

                        try {
                            // Read bytes without closing the ZipInputStream
                            val bytes = zis.readNBytes(maxFileSizeBytes.toInt() + 1)
                            if (bytes.size > maxFileSizeBytes) {
                                zis.closeEntry()
                                return@forEach
                            }
                            val content = String(bytes, Charsets.UTF_8)
                            scanned++
                            searchContentForPatterns(name, content, patterns, maxResults - matches.size, matches)
                        } catch (_: Exception) {
                            // Skip files that can't be read as text
                        }
                        zis.closeEntry()
                    }
            }
        }
        onScanned(scanned)
    }

    private fun searchTarStream(
        file: java.nio.file.Path,
        patterns: List<Pair<Regex, String>>,
        extensionFilter: Set<String>,
        maxResults: Int,
        maxFileSizeBytes: Long,
        maxFiles: Int,
        matches: MutableList<JsonObject>,
        onScanned: (Int) -> Unit,
    ) {
        var scanned = 0
        try {
            val process = ProcessBuilder("tar", "xzf", file.toAbsolutePath().toString(), "--to-stdout")
                .redirectErrorStream(true)
                .start()
            // tar --to-stdout concatenates all files; can't separate them.
            // Instead, use tar -tzf to list, then extract individually.
            // Simpler: list files first, then use 7z or tar to extract each one.
            // For now, fall back to a simpler approach: read the full tar listing.
            process.destroyForcibly()

            // Alternative: use Apache commons-compress or just scan via listing + individual extraction
            // For simplicity, scan by listing paths and noting which patterns match in file names.
            // TODO: full content search for tar.gz requires per-file extraction support
            val listProcess = ProcessBuilder("tar", "tzf", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            val listing = listProcess.inputStream.bufferedReader().readText()
            listProcess.waitFor(30, TimeUnit.SECONDS)

            val filePaths = listing.lines().filter { it.isNotBlank() }.filter { !it.endsWith("/") }
            for (path in filePaths) {
                if (scanned >= maxFiles) break
                val ext = path.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext !in TEXT_EXTENSIONS) continue
                if (extensionFilter.isNotEmpty() && ext !in extensionFilter) continue

                // For tar, we can only do filename-based matching for now
                for ((regex, patternName) in patterns) {
                    if (regex.containsMatchIn(path)) {
                        matches.add(buildJsonObject {
                            put("file", path)
                            put("pattern", patternName)
                            put("match_type", "filename")
                        })
                        if (matches.size >= maxResults) break
                    }
                }
                scanned++
            }
        } catch (_: Exception) {
            // tar not available
        }
        onScanned(scanned)
    }

    /** Search text content of a single file against all patterns.
     * Returns a multi-line snippet with 5 lines of context before and after each match
     * (similar to `grep -C 5`). Total snippet length is capped at 2000 characters.
     */
    private fun searchContentForPatterns(
        filePath: String,
        content: String,
        patterns: List<Pair<Regex, String>>,
        remaining: Int,
        matches: MutableList<JsonObject>,
    ) {
        var added = 0
        val lines = content.lines()
        val totalLines = lines.size
        for ((regex, patternName) in patterns) {
            if (added >= remaining) break
            for ((idx, line) in lines.withIndex()) {
                if (added >= remaining) break
                if (regex.containsMatchIn(line)) {
                    val contextBefore = ((idx - 5).coerceAtLeast(0) until idx)
                        .joinToString("\n") { lines[it] }
                    val contextAfter = ((idx + 1)..(idx + 5).coerceAtMost(totalLines - 1))
                        .joinToString("\n") { lines[it] }
                    val snippet = buildString {
                        if (contextBefore.isNotEmpty()) {
                            append(contextBefore)
                            append("\n")
                        }
                        append(line)
                        if (contextAfter.isNotEmpty()) {
                            append("\n")
                            append(contextAfter)
                        }
                    }.take(800).trim()
                    matches.add(buildJsonObject {
                        put("file", filePath)
                        put("line", idx + 1)
                        put("snippet", snippet)
                        put("pattern", patternName)
                    })
                    added++
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // InnoSetup listing (via innounp)
    // -----------------------------------------------------------------------

    private suspend fun listInnosetup(url: String): String {
        val files = listInnosetupPaths(url)
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (f in files) add(f) })
        }.toString()
    }

    private suspend fun listInnosetupPaths(url: String): List<String> {
        val file = cache.getOrDownload(url)
        return try {
            val proc = ProcessBuilder("innounp", "-s", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            if (proc.exitValue() != 0) return emptyList()
            // innounp -s output: lines like "  {app}\\bin\\app.exe"
            // Skip header lines (program info, archive info, blank lines)
            output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }  // {app}\\... paths
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // InnoSetup verification via innounp
    // -----------------------------------------------------------------------

    /**
     * Try running `innounp -v` on the file to verify it can be unpacked.
     * Returns true if innounp can list files, false if it fails.
     */
    private fun tryInnounpList(file: java.nio.file.Path): Boolean {
        return try {
            val proc = ProcessBuilder("innounp", "-v", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(15, TimeUnit.SECONDS)
            proc.exitValue() == 0 && !output.contains("corrupted", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------------
    // NSIS extractability test
    // -----------------------------------------------------------------------

    /**
     * Test if a cached NSIS installer can be listed by 7z.
     *
     * Returns `true` if `7z l` exits with code 0 (7z can read the file),
     * `false` if 7z fails (file is not extractable, or 7z is not available).
     *
     * Some NSIS 3 installers cannot be extracted by 7z (e.g. spyder 6.1.4).
     * This test detects those cases so the agent can choose an alternative strategy.
     */
    internal fun testNsisExtractability(filePath: Path): Boolean {
        return try {
            val proc = ProcessBuilder("7z", "l", filePath.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            if (proc.exitValue() == 0) {
                true
            } else {
                nsisLogger.warn { "[7z] extractability test failed with exit ${proc.exitValue()} for ${filePath.fileName}: ${output.take(200)}" }
                false
            }
        } catch (e: Exception) {
            nsisLogger.warn { "[7z] extractability test exception: ${e.message}" }
            false
        }
    }

    // -----------------------------------------------------------------------
    // NSIS listing (via 7z — reads NSIS installer internals)
    // -----------------------------------------------------------------------

    /**
     * Run `7z l` on a file and return the list of file paths inside it.
     * 7z can read NSIS, InnoSetup, and many other archive formats.
     * Returns empty list if 7z is not available or the file is not readable.
     */
    private fun run7zList(file: Path): List<String> {
        return try {
            val proc = ProcessBuilder("7z", "l", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            if (proc.exitValue() != 0) {
                nsisLogger.warn { "[7z] exit code ${proc.exitValue()} for ${file.fileName}: ${output.take(300)}" }
                emptyList()
            } else {
                parse7zOutput(output)
            }
        } catch (e: Exception) {
            nsisLogger.warn { "[7z] execute failed: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Parse the output of `7z l` to extract file paths.
     *
     * 7z listing output format:
     * ```
     *    Date      Time    Attr         Size   Compressed  Name
     * ------------------- ----- ------------ ------------  ------------------------
     * 2024-01-01 12:00:00 .....         1234         5678  twine.exe
     * 2024-01-01 12:00:00 D....            0            0  \$PLUGINSDIR
     * 2024-01-01 12:00:00 .....       987654       123456  \$PLUGINSDIR/app-64.7z
     * ```
     */
    private fun parse7zOutput(output: String): List<String> {
        val files = mutableListOf<String>()
        var inFileSection = false

        for (line in output.lines()) {
            when {
                line.trimStart().startsWith("---") && line.contains("-------") -> {
                    inFileSection = !inFileSection
                }
                inFileSection -> {
                    if (line.isBlank()) continue
                    // Skip the column header line
                    if (line.contains("Name") && !line.contains("---")) continue

                    // 7z output format:
                    //    Date      Time    Attr         Size   Compressed  Name
                    //  2024-01-01 12:00:00 .....         1234         5678  dir/path
                    //  2024-01-01 12:00:00 D....            0            0  dir/
                    //                     .....                      6931  \$PLUGINSDIR\System.dll
                    //
                    // Extract path: find the LAST sequence "digits + 2+spaces" then take
                    // everything after it. This works even when date/time are absent.
                    val match = Regex("""(\d+)\s{2,}(.+)$""").findAll(line).lastOrNull()
                    if (match != null) {
                        val path = match.groupValues[2].trim()
                        if (path.isNotBlank()) {
                            // Normalize backslashes to forward slashes for consistency
                            files.add(path.replace('\\', '/'))
                        }
                    }
                }
            }
        }
        return files
    }

    /**
     * Try to list contents of a file using 7z (works for NSIS installers).
     * Returns empty list if 7z cannot read the file.
     */
    private fun try7zNsisContent(file: Path): List<String> =
        run7zList(file)

    /**
     * List an .exe file by detecting its installer type first.
     * Returns JSON with file list and installer type info.
     */
    private suspend fun listExeWithDetection(url: String): String {
        // Try detectInstallerType content-scanning first
        val file = cache.getOrDownload(url)
        val headerBytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024) }
        val headerContent = String(headerBytes, Charsets.ISO_8859_1)

        return when {
            headerContent.contains("Inno Setup", ignoreCase = true) -> listInnosetup(url)
            headerContent.contains("Nullsoft", ignoreCase = true) -> listNsis(url)
            else -> {
                // Fallback: try 7z listing, then innounp
                val n7z = run7zList(file)
                if (n7z.isNotEmpty()) {
                    buildJsonObject {
                        put("url", url)
                        put("files", buildJsonArray { for (f in n7z) add(f) })
                        put("note", "Detected via 7z fallback")
                    }.toString()
                } else {
                    listInnosetup(url)
                }
            }
        }
    }

    /**
     * Same as [listExeWithDetection] but returns flat path list for findExecutables.
     */
    private suspend fun listExePathsWithDetection(url: String): List<String> {
        val file = cache.getOrDownload(url)
        val headerBytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024) }
        val headerContent = String(headerBytes, Charsets.ISO_8859_1)

        return when {
            headerContent.contains("Inno Setup", ignoreCase = true) -> listInnosetupPaths(url)
            headerContent.contains("Nullsoft", ignoreCase = true) -> listNsisPaths(url)
            else -> {
                val n7z = run7zList(file)
                if (n7z.isNotEmpty()) n7z else listInnosetupPaths(url)
            }
        }
    }

    /**
     * List files inside a NSIS installer using 7z.
     */
    private suspend fun listNsis(url: String): String {
        val file = cache.getOrDownload(url)
        val files = run7zList(file)
        val hasPluginsdir = files.any { it.contains("\$PLUGINSDIR", ignoreCase = true) }

        return buildJsonObject {
            put("url", url)
            put("installer_type", "nsis")
            put("nsis_type", if (hasPluginsdir) "electron" else "standard")
            put("files", buildJsonArray { for (f in files) add(f) })
            if (hasPluginsdir) {
                put("hint", "Electron NSIS installer: the actual .exe is inside \$PLUGINSDIR/*.7z. " +
                    "Use 'url#/dl.7z' + Expand-7zipArchive in pre_install to extract it.")
            }
        }.toString()
    }

    /**
     * Same as [listNsis] but returns flat path list for findExecutables.
     */
    private suspend fun listNsisPaths(url: String): List<String> {
        val file = cache.getOrDownload(url)
        return run7zList(file)
    }

    // -----------------------------------------------------------------------
    // 7z listing (uses system 7z command)
    // -----------------------------------------------------------------------

    private suspend fun list7z(url: String): String {
        val file = cache.getOrDownload(url)
        val files = run7zList(file)
        val nestedResult = probeNestedArchive(file, files)
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (f in files) add(f) })
            if (nestedResult != null) {
                put("nested_archive", buildJsonObject {
                    put("name", nestedResult.name)
                    put("files", buildJsonArray { for (f in nestedResult.files) add(f) })
                })
            }
        }.toString()
    }

    private suspend fun list7zPaths(url: String): List<String> {
        val file = cache.getOrDownload(url)
        return run7zList(file)
    }

    // -----------------------------------------------------------------------
    // ZIP listing
    // -----------------------------------------------------------------------

    private suspend fun listZip(url: String): String {
        val file = cache.getOrDownload(url)
        val entries = mutableListOf<String>()
        Files.newInputStream(file).use { fis ->
            ZipInputStream(fis).use { zis ->
                generateSequence { zis.nextEntry }
                    .forEach { entry ->
                        if (entry.name.count { it == '/' } <= MAX_DEPTH) {
                            entries.add(entry.name)
                        }
                        zis.closeEntry()
                    }
            }
        }
        val nestedResult = probeNestedArchive(file, entries)
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (e in entries) add(e) })
            if (nestedResult != null) {
                put("nested_archive", buildJsonObject {
                    put("name", nestedResult.name)
                    put("files", buildJsonArray { for (f in nestedResult.files) add(f) })
                })
            }
        }.toString()
    }

    private suspend fun listZipPaths(url: String): List<String> {
        val file = cache.getOrDownload(url)
        val entries = mutableListOf<String>()
        Files.newInputStream(file).use { fis ->
            ZipInputStream(fis).use { zis ->
                generateSequence { zis.nextEntry }
                    .forEach { entry ->
                        entries.add(entry.name)
                        zis.closeEntry()
                    }
            }
        }
        return entries
    }


    // -----------------------------------------------------------------------
    // Nested archive detection
    // -----------------------------------------------------------------------

    /**
     * Probe a single-nested archive if the outer archive contains exactly one archive file.
     * Extracts the inner archive to a temp directory and lists its contents using 7z.
     * Returns null if there's no nested archive or if extraction/listing fails.
     */
    private fun probeNestedArchive(outerFile: Path, outerEntries: List<String>): NestedArchiveInfo? {
        val archiveEntries = outerEntries.filter { isArchiveFile(it) }
        if (archiveEntries.size != 1) return null

        val nestedName = archiveEntries[0]
        val extractDir = try {
            Files.createTempDirectory("scoop-ai-nested-")
        } catch (e: Exception) {
            return null
        }

        try {
            val innerFile = extractDir.resolve(nestedName.substringAfterLast('/'))

            // Extract the nested archive entry from the outer archive
            val extractOk = if (outerFile.toString().lowercase().endsWith(".zip")) {
                // Use ZipFile for random access extraction from outer zip
                try {
                    ZipFile(outerFile.toFile()).use { zf ->
                        val entry = zf.getEntry(nestedName) ?: return null
                        zf.getInputStream(entry).use { input ->
                            Files.copy(input, innerFile, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                // Use 7z for extraction from outer 7z
                try {
                    val proc = ProcessBuilder(
                        "7z", "e", "-y",
                        outerFile.toAbsolutePath().toString(),
                        "-o${extractDir.toAbsolutePath()}"
                    ).redirectErrorStream(true).start()
                    val output = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(30, TimeUnit.SECONDS)
                    proc.exitValue() == 0 && Files.exists(innerFile)
                } catch (e: Exception) {
                    false
                }
            }

            if (!extractOk || !Files.exists(innerFile) || Files.size(innerFile) == 0L) return null

            // List inner archive contents
            val innerFiles = if (nestedName.lowercase().endsWith(".7z")) {
                run7zList(innerFile)
            } else {
                // Use ZipInputStream for zip inner archives (avoids 7z output parsing quirks)
                try {
                    val innerEntries = mutableListOf<String>()
                    Files.newInputStream(innerFile).use { fis ->
                        ZipInputStream(fis).use { zis ->
                            generateSequence { zis.nextEntry }
                                .forEach { entry ->
                                    innerEntries.add(entry.name)
                                    zis.closeEntry()
                                }
                        }
                    }
                    innerEntries
                } catch (e: Exception) {
                    return null
                }
            }
            if (innerFiles.isEmpty()) return null

            return NestedArchiveInfo(name = nestedName, files = innerFiles)
        } catch (e: Exception) {
            return null
        } finally {
            // Clean up temp directory
            try {
                Files.walk(extractDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // tar.gz listing (uses system tar command)
    // -----------------------------------------------------------------------

    private suspend fun listTar(url: String): String {
        val files = listTarPaths(url)
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (f in files) add(f) })
        }.toString()
    }

    private suspend fun listTarPaths(url: String): List<String> {
        val file = cache.getOrDownload(url)
        return try {
            val process = ProcessBuilder("tar", "tzf", file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            output.lineSequence()
                .filter { it.isNotBlank() }
                .filter { it.count { c -> c == '/' } <= MAX_DEPTH }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // MSI listing
    // -----------------------------------------------------------------------

    private suspend fun listMsi(url: String): String {
        val file = cache.getOrDownload(url)
        val files = listMsiPathsFromFile(file)
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (f in files) add(f) })
        }.toString()
    }

    private suspend fun listMsiPaths(url: String): List<String> = listMsiPathsFromFile(cache.getOrDownload(url))

    private fun listMsiPathsFromFile(msiFile: Path): List<String> {
        return if (isLessmsiAvailable()) {
            runLessmsiQueryPaths(msiFile)
        } else {
            runMsiexecExtractAndList(msiFile)
        }
    }

    private fun isLessmsiAvailable(): Boolean {
        return try {
            val proc = ProcessBuilder("lessmsi", "h")
                .redirectErrorStream(true)
                .start()
            proc.waitFor(10, TimeUnit.SECONDS)
            proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runLessmsiQueryPaths(msiFile: Path): List<String> {
        val dirCsv = lessmsiTable(msiFile, "Directory")
        val compCsv = lessmsiTable(msiFile, "Component")
        val fileCsv = lessmsiTable(msiFile, "File")

        val dirRaw = parseCsvRows(dirCsv).associate { row ->
            row.getOrNull(0)!! to (row.getOrNull(1) to row.getOrNull(2))
        }

        val dirCache = mutableMapOf<String, String>()
        fun resolveDir(id: String): String = dirCache.getOrPut(id) {
            val (parent, name) = dirRaw[id] ?: (null to id)
            // Skip virtual root directories: TARGETDIR (DefaultDir="SourceDir"), or self-referencing
            if (parent.isNullOrBlank() || parent == id) ""
            else {
                val parentPath = resolveDir(parent)
                // Skip SourceDir — it's a virtual MSI root that doesn't exist on disk
                if (name == "SourceDir") parentPath
                else if (parentPath.isEmpty()) name ?: ""
                else "$parentPath/${name ?: ""}"
            }
        }

        val compToDir = parseCsvRows(compCsv).associate { row ->
            row.getOrNull(0)!! to row.getOrNull(2)!!
        }

        return parseCsvRows(fileCsv).mapNotNull { row ->
            val compId = row.getOrNull(1) ?: return@mapNotNull null
            val fileName = row.getOrNull(2) ?: return@mapNotNull null
            val dirId = compToDir[compId]
            val dirPath = dirId?.let { resolveDir(it) } ?: ""
            "$dirPath/$fileName"
        }
    }

    private fun lessmsiTable(msiFile: Path, table: String): String {
        val proc = ProcessBuilder("lessmsi", "l", "-t", table, msiFile.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor(60, TimeUnit.SECONDS)
        if (proc.exitValue() != 0) {
            throw IllegalStateException("lessmsi l -t $table failed: ${output.take(300)}")
        }
        return output
    }

    private fun runMsiexecExtractAndList(msiFile: Path): List<String> {
        val extractDir = Files.createTempDirectory("scoop-ai-msi-")
        try {
            val proc = ProcessBuilder(
                "msiexec", "/a", msiFile.toAbsolutePath().toString(),
                "/qn", "TARGETDIR=${extractDir.toAbsolutePath()}"
            )
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw IllegalStateException("msiexec timed out")
            }
            if (proc.exitValue() != 0) {
                throw IllegalStateException("msiexec exited with ${proc.exitValue()}: ${output.take(300)}")
            }

            return Files.walk(extractDir)
                .filter { Files.isRegularFile(it) }
                .map { extractDir.relativize(it).toString().replace('\\', '/') }
                .filter { it.isNotBlank() }
                .sorted()
                .toList()
        } finally {
            Files.walk(extractDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun parseCsvRows(csv: String): List<List<String>> {
        return csv.lines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = mutableListOf<String>()
                val sb = StringBuilder()
                var inQuotes = false
                for (ch in line) {
                    when {
                        ch == '"' -> inQuotes = !inQuotes
                        ch == ',' && !inQuotes -> {
                            fields.add(sb.toString().trim())
                            sb.clear()
                        }
                        else -> sb.append(ch)
                    }
                }
                fields.add(sb.toString().trim())
                fields
            }
    }
}
