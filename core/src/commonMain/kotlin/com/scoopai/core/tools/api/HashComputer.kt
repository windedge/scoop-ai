package com.scoopai.core.tools.api

interface HashComputer {
    suspend fun computeSha256FromUrl(url: String? = null): String
}