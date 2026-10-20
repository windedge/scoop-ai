package com.scoopai.core

import com.scoopai.core.agent.PhaseTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Network tools resolve from the agent-level registry (no host callback), so wiring them is a
 * name filter gated on the session being an Assist session.
 */
class AssistNetworkToolsWiringTest {

    private val allNetworkTools = listOf(
        namedTool(PhaseTools.PARSE_GITHUB_URL),
        namedTool(PhaseTools.FETCH_REPO_INFO),
        namedTool(PhaseTools.FETCH_LATEST_RELEASE),
        namedTool(PhaseTools.FETCH_ALL_RELEASES),
        namedTool(PhaseTools.LIST_RELEASE_ASSETS),
        namedTool(PhaseTools.FETCH_FILE_CONTENT),
        namedTool(PhaseTools.FETCH_WEB_PAGE),
        namedTool(PhaseTools.FETCH_JSON),
        namedTool(PhaseTools.RESOLVE_REDIRECT),
    )

    // -----------------------------------------------------------------------
    // Whitelist definition
    // -----------------------------------------------------------------------

    @Test
    fun `assist network whitelist contains exactly the nine read-only tools`() {
        assertEquals(
            setOf(
                "parseGitHubUrl",
                "fetchRepoInfo",
                "fetchLatestRelease",
                "fetchAllReleases",
                "listReleaseAssets",
                "fetchFileContent",
                "fetchWebPage",
                "fetchJson",
                "resolveRedirect",
            ),
            PhaseTools.ASSIST_NETWORK_TOOL_NAMES,
            "the assist whitelist must match the approved read-only tool set",
        )
    }

    @Test
    fun `assist network whitelist excludes download hash archive and manifest write tools`() {
        val banned = setOf(
            PhaseTools.COMPUTE_SHA256,
            PhaseTools.LIST_ARCHIVE_CONTENTS,
            PhaseTools.FIND_EXECUTABLES,
            PhaseTools.DETECT_INSTALLER_TYPE,
            PhaseTools.SEARCH_TEXT_IN_ARCHIVE,
        )
        assertTrue(
            PhaseTools.ASSIST_NETWORK_TOOL_NAMES.none { it in banned },
            "heavy download/hash/archive tools must stay out of the assist whitelist",
        )
    }

    // -----------------------------------------------------------------------
    // assistOnly gating
    // -----------------------------------------------------------------------

    @Test
    fun `assistOnly true picks every read-only network tool from the registry`() {
        val result = assistNetworkTools(assistOnly = true, tools = allNetworkTools)

        assertEquals(
            allNetworkTools.map { it.name }.toSet(),
            result.map { it.name }.toSet(),
            "an Assist session must wire all nine network tools into the assist subgraph",
        )
    }

    @Test
    fun `assistOnly false wires no network tools`() {
        val result = assistNetworkTools(assistOnly = false, tools = allNetworkTools)

        assertTrue(
            result.isEmpty(),
            "a Generate session's repair loop must not gain network access",
        )
    }

    @Test
    fun `registry missing some network tools degrades to the available subset`() {
        val partial = listOf(
            namedTool(PhaseTools.FETCH_REPO_INFO),
            namedTool(PhaseTools.COMPUTE_SHA256),
            namedTool(PhaseTools.FETCH_WEB_PAGE),
        )

        val result = assistNetworkTools(assistOnly = true, tools = partial)

        assertEquals(
            listOf(PhaseTools.FETCH_REPO_INFO, PhaseTools.FETCH_WEB_PAGE),
            result.map { it.name },
            "only whitelisted tools present in the registry are wired",
        )
    }
}
