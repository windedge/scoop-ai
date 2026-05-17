package com.scoopai.core.tools.api

interface GitHubApi {
    suspend fun parseGitHubUrl(url: String): String
    suspend fun fetchRepoInfo(owner: String, repo: String): String
    suspend fun fetchLatestRelease(owner: String, repo: String): String
    suspend fun fetchAllReleases(owner: String, repo: String, perPage: Int = 5): String
    suspend fun listReleaseAssets(owner: String, repo: String, tag: String): String
    suspend fun fetchFileContent(owner: String, repo: String, path: String): String
}
