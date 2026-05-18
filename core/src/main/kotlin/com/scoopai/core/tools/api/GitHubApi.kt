package com.scoopai.core.tools.api

interface GitHubApi {
    suspend fun parseGitHubUrl(url: String? = null): String
    suspend fun fetchRepoInfo(owner: String? = null, repo: String? = null): String
    suspend fun fetchLatestRelease(owner: String? = null, repo: String? = null): String
    suspend fun fetchAllReleases(owner: String? = null, repo: String? = null, perPage: Int = 5): String
    suspend fun listReleaseAssets(owner: String? = null, repo: String? = null, tag: String? = null): String
    suspend fun fetchFileContent(owner: String? = null, repo: String? = null, path: String? = null): String
}