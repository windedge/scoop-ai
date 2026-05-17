package com.scoopai.core.tools.api

interface ArchiveInspector {
    suspend fun listZipContents(url: String): String
    suspend fun findExecutables(url: String): String
    suspend fun detectInstallerType(url: String): String
}
