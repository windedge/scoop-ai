package com.scoopai.core.agent

import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.WebFetcher
import com.scoopai.core.agent.nodes.RawAssetInfo
import com.scoopai.core.agent.nodes.cleanDescription
import com.scoopai.core.agent.nodes.filterWindowsAssets
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parse owner and repo from various GitHub URL formats.
 * Supports both github.com/owner/repo and api.github.com/repos/owner/repo.
 * Returns null if the URL is not a valid GitHub URL.
 */
internal fun parseOwnerRepo(url: String): Pair<String, String>? {
    val regex = Regex("""(?:https?://)?(?:api\.)?github\.com/(?:repos/)?([^/?#\s]+)/([^/?#\s]+)""")
    val match = regex.find(url) ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2].removeSuffix(".git")
    if (repo.isBlank()) return null
    return Pair(owner, repo)
}

/**
 * GitHub URL 解析正则 (kept for backward compatibility, use parseOwnerRepo for new code)
 */
private val GITHUB_URL_REGEX = Regex("""github\.com/([^/]+)/([^/\s?#]+)""")

/**
 * 源码 zip URL 模板
 */
private fun sourceArchiveUrl(owner: String, repo: String, tag: String): String =
    "https://github.com/$owner/$repo/archive/refs/tags/$tag.zip"

/**
 * prefetch 搜索的所有配置模式（一次性搜索）
 */
internal val ALL_CONFIG_PATTERNS = listOf(
    "XDG_CONFIG_HOME", "APPDATA", "LOCALAPPDATA", "user.home", ".config",
    "env::var", "getenv", "os.Getenv", "System.getenv",
    "_CONFIG_DIR", "_CONFIG_PATH", "_CONFIG_HOME", "_DATA_DIR",
    "_HOME", "_LIB", "_CFG", "_PATH", "_CACHE",
    "QSettings", "QStandardPaths", ".ini", "config.ini", "app.config", "appsettings",
    "SaveSettings", "LoadSettings", "WriteAllText", "preferences", "ConfigurationManager",
    "SettingStore", "GetConfigPath", "ProgramData"
).joinToString(",")

// Social/forum links that should not be used as homepage
private val SOCIAL_HOSTNAMES = setOf(
    "discord.gg", "discord.com",
    "slack.com",
    "twitter.com", "x.com",
    "reddit.com",
    "facebook.com",
    "t.me", "telegram.org",
)

// Checksums file patterns (case-insensitive matching)
private val CHECKSUMS_PATTERNS = listOf(
    Regex("""(?i)^(checksums?\.txt|sha256sums?(\.txt)?|md5sums?(\.txt)?|sha512sums?(\.txt)?|\.sha256|\.sha512|\.md5)$"""),
    Regex("""(?i)\.(sha256|sha512|md5)$"""),
)

/**
 * Clean the homepage URL: remove social/forum links, fallback to GitHub repo URL.
 */
internal fun cleanHomepage(homepage: String?, owner: String, repo: String): String {
    if (homepage.isNullOrBlank()) {
        return "https://github.com/$owner/$repo"
    }
    val lower = homepage.lowercase()
    for (social in SOCIAL_HOSTNAMES) {
        if (social in lower) {
            return "https://github.com/$owner/$repo"
        }
    }
    return homepage.trimEnd('/')
}

/**
 * Detect a checksums/hash file among release assets.
 * Returns the asset name if found, empty string otherwise.
 */
internal fun detectChecksumsFile(assets: List<AssetSnapshot>): String {
    for (asset in assets) {
        for (pattern in CHECKSUMS_PATTERNS) {
            if (pattern.containsMatchIn(asset.name)) {
                return asset.name
            }
        }
    }
    return ""
}

/**
 * Detect a RELEASES file (Squirrel format) among release assets.
 * Case-sensitive: only matches exactly "RELEASES" (Squirrel convention uses uppercase).
 * Returns the asset name if found, empty string otherwise.
 */
internal fun detectReleasesFile(assets: List<AssetSnapshot>): String {
    for (asset in assets) {
        if (asset.name == "RELEASES") {
            return asset.name
        }
    }
    return ""
}

/**
 * Extract version number from a git tag, stripping the common 'v' prefix.
 */
internal fun extractVersion(tagName: String?): String {
    if (tagName.isNullOrBlank()) return ""
    return tagName.removePrefix("v")
}

/**
 * Check if a version string contains a pre-release suffix.
 * Matches: alpha, beta, rc, pre, preview, dev, snapshot (case-insensitive)
 * in the segment after the first `-`.
 */
internal fun isPrereleaseVersion(version: String): Boolean {
    if (version.isBlank()) return false
    val regex = Regex("""(?i)(?:-alpha[\d.]*|-beta[\d.]*|-rc[\d.]*|-preview[\d.]*|-pre[\d.]*|-dev[\d.]*|-snapshot[\d.]*)""")
    return regex.containsMatchIn(version)
}

/**
 * Select the first non-prerelease release from a list (assumed sorted newest-first).
 * Returns null if all are prereleases.
 */
internal fun selectStableRelease(releases: List<ReleaseSnapshot>): ReleaseSnapshot? {
    return releases.firstOrNull { !it.prerelease }
}

// ---------------------------------------------------------------------------
// executePrefetch — 节点内联逻辑抽取，策略图只做调用
// ---------------------------------------------------------------------------

/**
 * 执行 prefetch 节点逻辑的独立函数。若 [gitHubApi] 为 null 或执行失败，
 * 返回 [PrefetchResult] 默认（isGitHub = false）。
 */
internal suspend fun executePrefetch(
    url: String,
    gitHubApi: GitHubApi?,
    archiveInspector: ArchiveInspector? = null,
): PrefetchResult {
    if (gitHubApi == null) {
        return PrefetchResult(isGitHub = false, originalUrl = url)
    }
    return runCatching { prefetchResearchData(url, gitHubApi, archiveInspector = archiveInspector) }
        .getOrElse {
            PrefetchResult(isGitHub = false, originalUrl = url)
        }
}

// ---------------------------------------------------------------------------
// JSON 解析辅助（GitHub API 响应 → 快照数据类）
// ---------------------------------------------------------------------------

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class GitHubRepoResponse(
    val full_name: String = "",
    val description: String? = null,
    val homepage: String? = null,
    val license: GitHubLicense? = null,
    val default_branch: String? = null,
    val stargazers_count: Int? = null,
)

@Serializable
private data class GitHubLicense(val spdx_id: String? = null)

@Serializable
private data class GitHubReleaseResponse(
    val tag_name: String = "",
    val name: String? = null,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GitHubAssetResponse> = emptyList(),
)

@Serializable
private data class GitHubAssetResponse(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long? = null,
)

@Serializable
private data class SearchMatchResponse(
    val file: String = "",
    val line: Int? = null,
    val snippet: String = "",
    val pattern: String = "",
)

@Serializable
private data class SearchResponse(
    val matches: List<SearchMatchResponse> = emptyList(),
    val files_scanned: Int = 0,
)

private fun parseRepoInfo(jsonStr: String): RepoInfoSnapshot {
    val resp = json.decodeFromString<GitHubRepoResponse>(jsonStr)
    return RepoInfoSnapshot(
        fullName = resp.full_name,
        description = resp.description,
        homepage = resp.homepage,
        license = resp.license?.spdx_id,
        defaultBranch = resp.default_branch,
        stars = resp.stargazers_count,
    )
}

private fun parseRelease(jsonStr: String): ReleaseSnapshot {
    val resp = json.decodeFromString<GitHubReleaseResponse>(jsonStr)
    return ReleaseSnapshot(
        tagName = resp.tag_name,
        name = resp.name,
        prerelease = resp.prerelease,
        body = resp.body?.take(2000),
        assets = resp.assets.map { AssetSnapshot(it.name, it.browser_download_url, it.size) },
    )
}

private fun parseReleasesList(jsonStr: String): List<ReleaseSnapshot> {
    val resp = json.decodeFromString<List<GitHubReleaseResponse>>(jsonStr)
    return resp.map { r ->
        ReleaseSnapshot(
            tagName = r.tag_name,
            name = r.name,
            prerelease = r.prerelease,
            body = r.body?.take(500),
            assets = r.assets.map { AssetSnapshot(it.name, it.browser_download_url, it.size) },
        )
    }
}

private fun parsePersistSearch(jsonStr: String): PersistSearchSnapshot {
    val resp = json.decodeFromString<SearchResponse>(jsonStr)
    return PersistSearchSnapshot(
        matches = resp.matches.take(10).map { PersistSearchMatch(it.file, it.line, it.snippet, it.pattern) },
        filesScanned = resp.files_scanned,
    )
}

// ---------------------------------------------------------------------------
// prefetchResearchData 核心函数
// ---------------------------------------------------------------------------

/**
 * 在 LLM research 阶段之前，由确定性代码一次性并行收集所有可预取的数据。
 */
suspend fun prefetchResearchData(
    url: String,
    gitHubApi: GitHubApi,
    webFetcher: WebFetcher? = null,
    archiveInspector: ArchiveInspector? = null,
): PrefetchResult = coroutineScope {
    // 1. 解析 GitHub URL
    val parsed = parseOwnerRepo(url)
    if (parsed == null) {
        return@coroutineScope PrefetchResult(
            isGitHub = false,
            originalUrl = url,
        )
    }
    val owner = parsed.first
    val repo = parsed.second

    val errors = mutableListOf<String>()

    // 2. 并行 API 调用
    val repoInfoDeferred = async { runCatching { parseRepoInfo(gitHubApi.fetchRepoInfo(owner, repo)) }.getOrNull() }
    val releaseDeferred = async { runCatching { parseRelease(gitHubApi.fetchLatestRelease(owner, repo)) }.getOrNull() }

    val repoInfo = repoInfoDeferred.await()
    val latestRelease = releaseDeferred.await()

    // 3. 条件调用：如果 latest 是 prerelease，获取所有 release 找 stable
    val stableRelease = if (latestRelease?.prerelease == true) {
        runCatching {
            val allReleasesJson = gitHubApi.fetchAllReleases(owner, repo, 10)
            parseReleasesList(allReleasesJson).let { releases ->
                selectStableRelease(releases)
            }
        }.getOrNull()
    } else null

    // 4. 获取 effective release 和 source archive URL
    val effectiveRelease = stableRelease ?: latestRelease
    val tagName = effectiveRelease?.tagName ?: ""

    // 5. 并行：searchTextInArchive（如果 archiveInspector 可用）
    val persistSearchDeferred = if (archiveInspector != null && tagName.isNotBlank()) {
        async {
            runCatching {
                val sourceUrl = sourceArchiveUrl(owner, repo, tagName)
                val resultJson = archiveInspector.searchTextInArchive(
                    url = sourceUrl,
                    patterns = ALL_CONFIG_PATTERNS,
                )
                parsePersistSearch(resultJson)
            }.getOrNull()
        }
    } else null

    val persistSearch = persistSearchDeferred?.await()

    // 6. 确定性后处理
    val rawAssets = effectiveRelease?.assets?.map { RawAssetInfo(it.downloadUrl, it.name) } ?: emptyList()
    val windowsAssets = filterWindowsAssets(rawAssets).map { wa ->
        PrefetchedAsset(
            url = wa.url,
            filename = wa.filename,
            architecture = wa.architecture,
            assetType = wa.asset_type,
            size = null, // WindowsAsset 不携带 size 信息，由后续查找填充
        )
    }

    val homepageCleaned = cleanHomepage(repoInfo?.homepage, owner, repo)
    val detectedChecksumsFile = detectChecksumsFile(effectiveRelease?.assets ?: emptyList())
    val detectedReleasesFile = detectReleasesFile(effectiveRelease?.assets ?: emptyList())
    val version = extractVersion(tagName)
    val recommendedDescription = cleanDescription(repoInfo?.description)

    PrefetchResult(
        isGitHub = true,
        owner = owner,
        repo = repo,
        originalUrl = url,
        repoInfo = repoInfo,
        latestRelease = effectiveRelease,
        stableRelease = stableRelease,
        persistSearch = persistSearch,
        windowsAssets = windowsAssets,
        detectedChecksumsFile = detectedChecksumsFile,
        detectedReleasesFile = detectedReleasesFile,
        homepageCleaned = homepageCleaned,
        version = version,
        recommendedDescription = recommendedDescription,
        errors = errors,
    )
}
