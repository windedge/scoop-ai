package com.scoopai.core.tools.api

interface ArchiveInspector {
    /** List file paths inside an archive (ZIP, MSI, etc.). Auto-detects type from URL. */
    suspend fun listArchiveContents(url: String? = null): String
    /** Find .exe files inside an archive (ZIP, MSI, etc.). Auto-detects type from URL. */
    suspend fun findExecutables(url: String? = null): String
    /** Detect installer type (innosetup/nsis/msi/zip/unknown). */
    suspend fun detectInstallerType(url: String? = null): String

    /**
     * Search for text patterns inside a source code archive (zip/tar.gz) WITHOUT
     * extracting to disk. Scans only text-like files up to [maxFileSizeBytes].
     *
     * @param url URL of the source archive (typically a GitHub release/tag source zip).
     * @param patterns Regex patterns to search for (e.g. `"XDG_CONFIG_HOME"`, `"electron-store"`).
     * @param includeGlobs Glob patterns to restrict which files are scanned
     *        (e.g. `listOf("*.json", "*.rs", "*.go")`). Empty = scan all text files.
     * @param maxResults Maximum number of match entries to return.
     * @param maxFileSizeBytes Skip files larger than this (default 256 KB).
     * @return JSON string with matches: `{ "matches": [{ "file": "path", "line": 42, "snippet": "...", "pattern": "..." }], "files_scanned": N }`
     */
    suspend fun searchTextInArchive(
        url: String? = null,
        patterns: String? = null,
        includeGlobs: String? = null,
        maxResults: Int = 15,
        maxFileSizeBytes: Long = 256 * 1024,
    ): String
}
