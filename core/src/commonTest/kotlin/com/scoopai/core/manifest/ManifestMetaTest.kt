package com.scoopai.core.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestMetaTest {

    // ---------------------------------------------------------------
    // Basic parsing
    // ---------------------------------------------------------------

    @Test
    fun `parses version and url from simple manifest`() {
        val manifest = """{"version":"1.2.3","url":"https://example.com/app.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("1.2.3", meta.version)
        assertEquals("https://example.com/app.zip", meta.url)
    }

    @Test
    fun `parses manifest with architecture block`() {
        val manifest = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc"}},"bin":["fd.exe"]}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("10.2.0", meta.version)
        // url should be null because only architecture has url, not top-level
        assertNull(meta.url)
    }

    // ---------------------------------------------------------------
    // Boundary: missing fields
    // ---------------------------------------------------------------

    @Test
    fun `returns null version when version field missing`() {
        val manifest = """{"url":"https://example.com/app.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertNull(meta.version)
        assertEquals("https://example.com/app.zip", meta.url)
    }

    @Test
    fun `returns null url when url field missing`() {
        val manifest = """{"version":"1.2.3"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("1.2.3", meta.version)
        assertNull(meta.url)
    }

    @Test
    fun `returns nulls for empty object`() {
        val manifest = """{}"""
        val meta = parseManifestMeta(manifest)
        assertNull(meta.version)
        assertNull(meta.url)
    }

    // ---------------------------------------------------------------
    // Boundary: url with escaped characters
    // ---------------------------------------------------------------

    @Test
    fun `parses url with escaped quotes in the manifest string`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app v1.0.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("1.0", meta.version)
        assertEquals("https://example.com/app v1.0.zip", meta.url)
    }

    @Test
    fun `parses url with query parameters`() {
        val manifest = """{"version":"2.0","url":"https://github.com/owner/repo/releases/download/v2.0/app.exe?download=1"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("2.0", meta.version)
        assertEquals("https://github.com/owner/repo/releases/download/v2.0/app.exe?download=1", meta.url)
    }

    // ---------------------------------------------------------------
    // Boundary: malformed JSON
    // ---------------------------------------------------------------

    @Test
    fun `returns nulls for malformed JSON`() {
        val manifest = """{invalid json"""
        val meta = parseManifestMeta(manifest)
        assertNull(meta.version)
        assertNull(meta.url)
    }

    @Test
    fun `returns nulls for empty string`() {
        val manifest = ""
        val meta = parseManifestMeta(manifest)
        assertNull(meta.version)
        assertNull(meta.url)
    }

    // ---------------------------------------------------------------
    // Boundary: regex-vulnerable cases
    // These cases would break the old regex-based parser but work with
    // kotlinx.serialization.
    // ---------------------------------------------------------------

    @Test
    fun `parses url with escaped backslash before quote`() {
        val manifest = """{"version":"1.0","url":"https://example.com/path\\to\\app.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("1.0", meta.version)
        assertEquals("https://example.com/path\\to\\app.zip", meta.url)
    }

    @Test
    fun `parses url with unicode characters`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app-α.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("1.0", meta.version)
        assertEquals("https://example.com/app-α.zip", meta.url)
    }

    @Test
    fun `parses version with plus sign`() {
        val manifest = """{"version":"1.0.0+build.123","url":"https://example.com/app.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("1.0.0+build.123", meta.version)
        assertEquals("https://example.com/app.zip", meta.url)
    }

    @Test
    fun `parses version with leading v when present`() {
        val manifest = """{"version":"v1.2.3","url":"https://example.com/app.zip"}"""
        val meta = parseManifestMeta(manifest)
        assertEquals("v1.2.3", meta.version)
    }

    // ---------------------------------------------------------------
    // Data class semantics
    // ---------------------------------------------------------------

    @Test
    fun `ManifestMeta data class equality`() {
        val a = ManifestMeta("1.0", "https://example.com/a.zip")
        val b = ManifestMeta("1.0", "https://example.com/a.zip")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ManifestMeta data class with both nulls`() {
        val meta = ManifestMeta(null, null)
        assertNull(meta.version)
        assertNull(meta.url)
    }
}
