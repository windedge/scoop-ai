package com.scoopai.tools.archive

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.tools.api.ArchiveInspector
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@LLMDescription("Archive inspection tools for ZIP, MSI and installer analysis")
class ZipArchiveInspector : ArchiveInspector, ToolSet {

    companion object {
        private const val MAX_DOWNLOAD_BYTES: Long = 200L * 1024 * 1024 // 200 MB
        private const val MAX_DEPTH = 3
    }

    // -----------------------------------------------------------------------
    // Download cache — same URL downloaded only once per agent run
    // -----------------------------------------------------------------------

    private val downloadCache = mutableMapOf<String, Path>()

    fun cleanup() {
        downloadCache.values.forEach { Files.deleteIfExists(it) }
        downloadCache.clear()
    }

    private fun getOrDownload(url: String): Path {
        downloadCache[url]?.let { cached ->
            if (Files.exists(cached)) return cached
            downloadCache.remove(url)
        }
        val tempFile = downloadToTempFile(url)
        downloadCache[url] = tempFile
        return tempFile
    }

    private fun downloadToTempFile(url: String): Path {
        val tempFile = Files.createTempFile("scoop-ai-", ".tmp")
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
                "zip", "7z", "tar" -> listZip(urlNotNull)
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
            // Fast: check URL extension first
            val type = fileType(urlNotNull)
            if (type == "msi" || type == "zip" || type == "7z" || type == "tar") {
                return buildJsonObject {
                    put("url", urlNotNull)
                    put("installer_type", type)
                }.toString()
            }

            // Read first 1MB from cached file
            val file = getOrDownload(urlNotNull)
            val headerBytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024) }
            val headerContent = String(headerBytes, Charsets.ISO_8859_1)

            val installerType = when {
                headerContent.contains("Inno Setup", ignoreCase = true) -> "innosetup"
                headerContent.contains("Nullsoft", ignoreCase = true) -> "nsis"
                else -> "unknown"
            }

            buildJsonObject {
                put("url", urlNotNull)
                put("installer_type", installerType)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("error", "Failed to detect installer type: ${e.message}") }.toString()
        }
    }

    // -----------------------------------------------------------------------
    // ZIP listing
    // -----------------------------------------------------------------------

    private fun listZip(url: String): String {
        val file = getOrDownload(url)
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
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (e in entries) add(e) })
        }.toString()
    }

    private fun listZipPaths(url: String): List<String> {
        val file = getOrDownload(url)
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
    // MSI listing
    // -----------------------------------------------------------------------

    private fun listMsi(url: String): String {
        val file = getOrDownload(url)
        val files = listMsiPathsFromFile(file)
        return buildJsonObject {
            put("url", url)
            put("files", buildJsonArray { for (f in files) add(f) })
        }.toString()
    }

    private fun listMsiPaths(url: String): List<String> = listMsiPathsFromFile(getOrDownload(url))

    private fun listMsiPathsFromFile(msiFile: Path): List<String> {
        return if (isLessmsiAvailable()) {
            runLessmsiQueryPaths(msiFile)
        } else {
            runMsiexecExtractAndList(msiFile)
        }
    }

    /** lessmsi available? */
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

    /** Fast: query Directory + Component + File tables to build full paths. */
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
            if (parent.isNullOrBlank() || parent == id) name ?: id
            else "${resolveDir(parent)}/$name"
        }

        val compToDir = parseCsvRows(compCsv).associate { row ->
            row.getOrNull(0)!! to row.getOrNull(1)!!
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

    /** Fallback: msiexec /a extract + walk directory tree. */
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

    /** Parse CSV rows, skipping header. */
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
