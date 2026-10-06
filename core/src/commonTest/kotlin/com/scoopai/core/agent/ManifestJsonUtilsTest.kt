package com.scoopai.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TDD tests for [formatManifestJson], the library-level manifest formatter that
 * makes ManifestResult.manifest a canonical 4-space pretty-printed JSON object
 * regardless of how the LLM produced it (compact, fenced, wrapped, double-encoded).
 */
class ManifestJsonUtilsTest {

    private val compact = """{"version":"1.0","url":"https://example.com/app.zip","hash":"abc123"}"""

    private val pretty4 = """{
    "version": "1.0",
    "url": "https://example.com/app.zip",
    "hash": "abc123"
}"""

    // ---------------------------------------------------------------
    // basic formatting
    // ---------------------------------------------------------------

    @Test
    fun `compact JSON is pretty-printed with 4-space indent`() {
        assertEquals(pretty4, formatManifestJson(compact))
    }

    @Test
    fun `already 4-space formatted input is idempotent`() {
        assertEquals(pretty4, formatManifestJson(pretty4))
    }

    // ---------------------------------------------------------------
    // wrapper unwrapping
    // ---------------------------------------------------------------

    @Test
    fun `wrapper object with manifest field is unwrapped and formatted`() {
        val wrapped = """{"status":"ok","manifest":"{\"version\":\"1.0\",\"url\":\"https://example.com/app.zip\",\"hash\":\"abc123\"}"}"""
        val result = formatManifestJson(wrapped)
        assertEquals(pretty4, result)
    }

    @Test
    fun `object with manifest field and version is NOT treated as a wrapper`() {
        // {"manifest": ..., "version": ...} is a real manifest (has a version), keep it as-is
        val input = """{"manifest":"something","version":"1.0","url":"https://example.com/app.zip"}"""
        val result = formatManifestJson(input)
        assertTrue(result.contains(""".*"version": "1\.0".*""".toRegex()))
        assertTrue(result.contains("\"manifest\": \"something\""), "manifest field must be kept: $result")
    }

    // ---------------------------------------------------------------
    // double-encoding unwrapping
    // ---------------------------------------------------------------

    @Test
    fun `double-encoded JSON string is unwrapped one level and formatted`() {
        val doubleEncoded = """"{\"version\":\"1.0\",\"url\":\"https://example.com/app.zip\",\"hash\":\"abc123\"}""""
        assertEquals(pretty4, formatManifestJson(doubleEncoded))
    }

    // ---------------------------------------------------------------
    // code fence stripping
    // ---------------------------------------------------------------

    @Test
    fun `code fence is stripped before formatting`() {
        assertEquals(pretty4, formatManifestJson("```json\n$compact\n```"))
    }

    @Test
    fun `code fence without json language tag is stripped too`() {
        assertEquals(pretty4, formatManifestJson("```\n$compact\n```"))
    }

    @Test
    fun `code fence wrapping a wrapped manifest is unwrapped end to end`() {
        val wrapped = """{"status":"ok","manifest":"{\"version\":\"1.0\",\"url\":\"https://example.com/app.zip\",\"hash\":\"abc123\"}"}"""
        assertEquals(pretty4, formatManifestJson("```json\n$wrapped\n```"))
    }

    // ---------------------------------------------------------------
    // failure handling
    // ---------------------------------------------------------------

    @Test
    fun `invalid JSON is returned unchanged`() {
        val bad = "this is not json {"
        assertEquals(bad, formatManifestJson(bad))
    }

    @Test
    fun `plain string without JSON content is returned unchanged`() {
        val plain = "\"just a string\""
        assertEquals(plain, formatManifestJson(plain))
    }

    @Test
    fun `JSON array is returned unchanged`() {
        val arr = """[1,2,3]"""
        assertEquals(arr, formatManifestJson(arr))
    }

    @Test
    fun `empty input is returned unchanged`() {
        assertEquals("", formatManifestJson(""))
        assertEquals("   ", formatManifestJson("   "))
    }

    @Test
    fun `wrapper with invalid inner content is returned unchanged`() {
        val bad = """{"status":"ok","manifest":"not json"}"""
        assertEquals(bad, formatManifestJson(bad))
    }

    @Test
    fun `whitespace around compact JSON does not break formatting`() {
        assertEquals(pretty4, formatManifestJson("  \n$compact\n  "))
    }

    // ---------------------------------------------------------------
    // withPhaseMeta: inject _meta.activities into the onPhaseOutput channel
    // ---------------------------------------------------------------

    @Test
    fun `withPhaseMeta injects top-level _meta activities`() {
        val result = withPhaseMeta(compact, listOf("Appended #/dl.7z for the NSIS installer", "Added autoupdate.hash"))

        val obj = Json.parseToJsonElement(result).jsonObject
        val meta = obj["_meta"]!!.jsonObject
        val activities = meta["activities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("Appended #/dl.7z for the NSIS installer", "Added autoupdate.hash"),
            activities,
        )
    }

    @Test
    fun `withPhaseMeta preserves existing manifest fields`() {
        val result = withPhaseMeta(compact, listOf("did a thing"))

        val obj = Json.parseToJsonElement(result).jsonObject
        assertEquals("1.0", obj["version"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/app.zip", obj["url"]!!.jsonPrimitive.content)
        assertEquals("abc123", obj["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `withPhaseMeta with null activities returns the input unchanged`() {
        assertEquals(compact, withPhaseMeta(compact, null))
    }

    @Test
    fun `withPhaseMeta with empty activities returns the input unchanged`() {
        assertEquals(compact, withPhaseMeta(compact, emptyList()))
    }

    @Test
    fun `withPhaseMeta with unparseable manifest returns the input unchanged`() {
        val bad = "this is not json {"
        assertEquals(bad, withPhaseMeta(bad, listOf("did a thing")))
    }

    @Test
    fun `withPhaseMeta overwrites an existing _meta key`() {
        val withMeta = """{"version":"1.0","_meta":{"stale":"value"}}"""

        val result = withPhaseMeta(withMeta, listOf("fresh activity"))

        val meta = Json.parseToJsonElement(result).jsonObject["_meta"]!!.jsonObject
        assertEquals(listOf("fresh activity"), meta["activities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(!meta.containsKey("stale"), "stale _meta content must be replaced")
    }
}
