package com.scoopai.core.tools.api

interface WebFetcher {
    suspend fun fetchWebPage(url: String): String
    suspend fun fetchJson(url: String): String
    suspend fun resolveRedirect(url: String): String
}
