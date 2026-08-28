package com.scoopai.core.agent

import kotlinx.serialization.Serializable

@Serializable
data class PrefetchResult(
    val isGitHub: Boolean,
    val owner: String = "",
    val repo: String = "",
    val originalUrl: String,

    val repoInfo: RepoInfoSnapshot? = null,
    val latestRelease: ReleaseSnapshot? = null,
    val stableRelease: ReleaseSnapshot? = null,
    val repoTree: List<String>? = null,
    val persistSearch: PersistSearchSnapshot? = null,

    val windowsAssets: List<PrefetchedAsset> = emptyList(),
    val detectedChecksumsFile: String = "",
    val detectedReleasesFile: String = "",
    val homepageCleaned: String = "",
    val version: String = "",
    val recommendedDescription: String? = null,

    val errors: List<String> = emptyList(),
)

@Serializable
data class RepoInfoSnapshot(
    val fullName: String,
    val description: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val defaultBranch: String? = null,
    val stars: Int? = null,
)

@Serializable
data class ReleaseSnapshot(
    val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<AssetSnapshot> = emptyList(),
)

@Serializable
data class AssetSnapshot(
    val name: String,
    val downloadUrl: String,
    val size: Long? = null,
)

@Serializable
data class PrefetchedAsset(
    val url: String,
    val filename: String,
    val architecture: String,
    val assetType: String,
    val size: Long? = null,
)

@Serializable
data class PersistSearchSnapshot(
    val matches: List<PersistSearchMatch> = emptyList(),
    val filesScanned: Int = 0,
)

@Serializable
data class PersistSearchMatch(
    val file: String,
    val line: Int? = null,
    val snippet: String,
    val pattern: String,
)
