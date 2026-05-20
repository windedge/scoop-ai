package com.scoopai.core.agent

/**
 * Centralized, type-safe definition of which tools are available in each phase.
 *
 * This is the **single source of truth** for phase-tool assignment.
 * Both [ScoopManifestGenerator][com.scoopai.core.ScoopManifestGenerator]
 * and [DefaultToolRegistryFactory][com.scoopai.tools.DefaultToolRegistryFactory]
 * must reference these descriptors instead of hard-coding tool name strings.
 *
 * Tool names are declared as constants so that renaming a tool method
 * causes a compile-time error everywhere it is referenced.
 */
object PhaseTools {

    // ── Tool name constants (single source of truth) ──────────────────

    // Research tools
    const val PARSE_GITHUB_URL = "parseGitHubUrl"
    const val FETCH_REPO_INFO = "fetchRepoInfo"
    const val FETCH_LATEST_RELEASE = "fetchLatestRelease"
    const val FETCH_ALL_RELEASES = "fetchAllReleases"
    const val LIST_RELEASE_ASSETS = "listReleaseAssets"
    const val FETCH_FILE_CONTENT = "fetchFileContent"
    const val FETCH_WEB_PAGE = "fetchWebPage"
    const val RESOLVE_REDIRECT = "resolveRedirect"
    const val FETCH_JSON = "fetchJson"

    // Analyze tools
    const val COMPUTE_SHA256 = "computeSha256FromUrl"
    const val LIST_ZIP_CONTENTS = "listZipContents"
    const val FIND_EXECUTABLES = "findExecutables"
    const val DETECT_INSTALLER_TYPE = "detectInstallerType"

    // Generate tools
    const val VALIDATE = "validate"
    const val WRITE = "write"
    const val READ = "read"

    // Shared
    const val ASK_USER = "__ask_user__"

    // ── Phase tool sets ───────────────────────────────────────────────

    /** Tools available during the Research phase. */
    val RESEARCH_TOOL_NAMES: Set<String> = setOf(
        PARSE_GITHUB_URL,
        FETCH_REPO_INFO,
        FETCH_LATEST_RELEASE,
        FETCH_ALL_RELEASES,
        LIST_RELEASE_ASSETS,
        FETCH_FILE_CONTENT,
        FETCH_WEB_PAGE,
        RESOLVE_REDIRECT,
        FETCH_JSON,
        ASK_USER,
    )

    /** Tools available during the Analysis phase. */
    val ANALYZE_TOOL_NAMES: Set<String> = setOf(
        COMPUTE_SHA256,
        LIST_ZIP_CONTENTS,
        FIND_EXECUTABLES,
        DETECT_INSTALLER_TYPE,
        ASK_USER,
    )

    /** Tools available during the Generation phase. */
    val GENERATE_TOOL_NAMES: Set<String> = setOf(
        VALIDATE,
        WRITE,
        READ,
        ASK_USER,
    )
}
