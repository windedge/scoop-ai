package com.scoopai.core.tools.api

interface WebFetcher {
    suspend fun fetchWebPage(url: String? = null): String
    suspend fun fetchJson(url: String? = null): String
    suspend fun resolveRedirect(url: String? = null): String
}