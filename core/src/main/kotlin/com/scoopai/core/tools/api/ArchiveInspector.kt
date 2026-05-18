package com.scoopai.core.tools.api

interface ArchiveInspector {
    suspend fun listZipContents(url: String? = null): String
    suspend fun findExecutables(url: String? = null): String
    suspend fun detectInstallerType(url: String? = null): String
}