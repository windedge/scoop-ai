package com.scoopai.core.tools.api

interface ArchiveInspector {
    /** List file paths inside an archive (ZIP, MSI, etc.). Auto-detects type from URL. */
    suspend fun listArchiveContents(url: String? = null): String
    /** Find .exe files inside an archive (ZIP, MSI, etc.). Auto-detects type from URL. */
    suspend fun findExecutables(url: String? = null): String
    /** Detect installer type (innosetup/nsis/msi/zip/unknown). */
    suspend fun detectInstallerType(url: String? = null): String
}
