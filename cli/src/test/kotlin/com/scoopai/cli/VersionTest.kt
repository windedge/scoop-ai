package com.scoopai.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VersionTest {

    // ---------------------------------------------------------------
    // VersionInfo.format
    // ---------------------------------------------------------------

    @Test
    fun `format returns scoop-ai with version and commit`() {
        val info = VersionInfo("0.1.0", "abcdef1")
        assertEquals("scoop-ai 0.1.0 (commit abcdef1)", info.format())
    }

    @Test
    fun `format with unknown commit`() {
        val info = VersionInfo("0.1.0", "unknown")
        assertEquals("scoop-ai 0.1.0 (commit unknown)", info.format())
    }

    // ---------------------------------------------------------------
    // resolveVersion - gitRevisionProvider returns null
    // ---------------------------------------------------------------

    @Test
    fun `resolveVersion with null git revision gives unknown commit`() {
        val result = resolveVersion("0.1.0") { null }
        assertEquals("0.1.0", result.version)
        assertEquals("unknown", result.commit)
    }

    // ---------------------------------------------------------------
    // resolveVersion - gitRevisionProvider returns a valid hash
    // ---------------------------------------------------------------

    @Test
    fun `resolveVersion with valid git hash`() {
        val result = resolveVersion("0.1.0") { "abc1234" }
        assertEquals("abc1234", result.commit)
    }

    // ---------------------------------------------------------------
    // resolveVersion - trimming behavior
    // ---------------------------------------------------------------

    @Test
    fun `resolveVersion trims trailing newline from git revision`() {
        val result = resolveVersion("0.1.0") { "abc1234\n" }
        assertEquals("abc1234", result.commit)
    }

    @Test
    fun `resolveVersion trims surrounding whitespace from git revision`() {
        val result = resolveVersion("0.1.0") { "  abc1234  " }
        assertEquals("abc1234", result.commit)
    }

    // ---------------------------------------------------------------
    // resolveVersion - empty string fallback
    // ---------------------------------------------------------------

    @Test
    fun `resolveVersion with empty string git revision gives unknown commit`() {
        val result = resolveVersion("0.1.0") { "" }
        assertEquals("unknown", result.commit)
    }

    // ---------------------------------------------------------------
    // resolveVersion - configuredCommit parameter (build-time injection)
    // ---------------------------------------------------------------

    @Test
    fun `resolveVersion with configuredCommit returns that commit`() {
        val result = resolveVersion("0.1.0", configuredCommit = "build123") { null }
        assertEquals("build123", result.commit)
    }

    @Test
    fun `resolveVersion with null configuredCommit falls back to gitRevisionProvider`() {
        val result = resolveVersion("0.1.0", configuredCommit = null) { "git123" }
        assertEquals("git123", result.commit)
    }

    @Test
    fun `resolveVersion with blank configuredCommit falls back to gitRevisionProvider`() {
        val result = resolveVersion("0.1.0", configuredCommit = "") { "git456" }
        assertEquals("git456", result.commit)
    }

    @Test
    fun `resolveVersion with configuredCommit trimmed`() {
        val result = resolveVersion("0.1.0", configuredCommit = "  commit789\n  ") { null }
        assertEquals("commit789", result.commit)
    }
}
