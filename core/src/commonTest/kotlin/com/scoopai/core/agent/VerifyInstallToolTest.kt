package com.scoopai.core.agent

import com.scoopai.core.tools.api.ScoopVerifier
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [VerifyInstallTool]: the SimpleTool wrapper that exposes the
 * install verifier to the review agent as the `verify_install` tool.
 */
class VerifyInstallToolTest {

    private class FakeVerifier : ScoopVerifier {
        var calls: Int = 0
        var lastManifest: String? = null
        var lastAppName: String? = null
        var response: String = """{"success":true,"app_name":"x","exit_code":0,"output":"ok"}"""

        override suspend fun verifyInstall(manifestJson: String, appName: String): String {
            calls++
            lastManifest = manifestJson
            lastAppName = appName
            return response
        }
    }

    // -----------------------------------------------------------------------
    // Schema name
    // -----------------------------------------------------------------------

    @Test
    fun `tool schema name is verify_install`() {
        val verifier = FakeVerifier()
        val tool = VerifyInstallTool(verifier) { """{"version":"1.0"}""" }

        assertEquals("verify_install", tool.name, "Koog schema name must be the snake_case verify_install")
        assertEquals("verify_install", VerifyInstallTool.TOOL_NAME)
    }

    @Test
    fun `tool descriptor requires no parameters beyond reason`() {
        val tool = VerifyInstallTool(FakeVerifier()) { """{"version":"1.0"}""" }

        val descriptor = tool.descriptor
        assertEquals(0, descriptor.requiredParameters.size, "reason is optional, no required parameters")
        assertTrue(
            descriptor.optionalParameters.any { it.name == "reason" },
            "descriptor must expose the optional reason parameter",
        )
    }

    // -----------------------------------------------------------------------
    // Execute: manifest + fixed app name forwarding
    // -----------------------------------------------------------------------

    @Test
    fun `execute passes the current manifest and the fixed verify app name to the verifier`() = runBlocking {
        val verifier = FakeVerifier()
        val manifest = """{"version":"1.2.3","bin":["app.exe"]}"""
        val tool = VerifyInstallTool(verifier) { manifest }

        val result = tool.execute(VerifyInstallArgs(reason = "checking installability"))

        assertEquals(1, verifier.calls, "verifier must be invoked exactly once")
        assertEquals(manifest, verifier.lastManifest, "verifier must receive the manifest captured at review time")
        assertEquals(VerifyInstallTool.VERIFY_APP_NAME, verifier.lastAppName, "appName must be the fixed test name")
        assertEquals(verifier.response, result, "tool result must be the verifier JSON output")
    }

    @Test
    fun `execute reads the manifest freshly on every call`() = runBlocking {
        val verifier = FakeVerifier()
        var current = """{"version":"1.0.0"}"""
        val tool = VerifyInstallTool(verifier) { current }

        tool.execute(VerifyInstallArgs())
        current = """{"version":"2.0.0"}"""
        tool.execute(VerifyInstallArgs())

        assertEquals("""{"version":"2.0.0"}""", verifier.lastManifest, "each review round must install its own manifest")
        assertEquals(2, verifier.calls)
    }

    // -----------------------------------------------------------------------
    // Args
    // -----------------------------------------------------------------------

    @Test
    fun `reason is optional and defaults to empty`() {
        assertEquals("", VerifyInstallArgs().reason)
        assertEquals("motivation", VerifyInstallArgs(reason = "motivation").reason)
    }

    @Test
    fun `args round-trip through JSON with and without reason`() {
        val json = Json
        assertEquals(
            VerifyInstallArgs(reason = "deep extract_dir"),
            json.decodeFromString<VerifyInstallArgs>(json.encodeToString(VerifyInstallArgs(reason = "deep extract_dir"))),
        )
        assertEquals(
            VerifyInstallArgs(),
            json.decodeFromString<VerifyInstallArgs>("{}"),
            "model may omit reason; it must decode with the default",
        )
    }

    @Test
    fun `descriptor reason parameter is a string type`() {
        val tool = VerifyInstallTool(FakeVerifier()) { "{}" }
        val reason = tool.descriptor.optionalParameters.first { it.name == "reason" }
        assertEquals(ToolParameterType.String, reason.type)
    }

    // -----------------------------------------------------------------------
    // W4: blank manifest guard
    // -----------------------------------------------------------------------

    @Test
    fun `blank manifest returns error JSON without invoking the verifier`() = runBlocking {
        val verifier = FakeVerifier()
        // Providers that never set a manifest (e.g. the generate repair loop in the
        // simplified scheme) must not burn install budget or call the verifier.
        val tool = VerifyInstallTool(verifier) { "" }

        val result = tool.execute(VerifyInstallArgs())

        assertEquals(0, verifier.calls, "blank manifest must not invoke the verifier")
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertEquals("false", parsed["success"].toString(), "blank guard must report success=false")
        assertTrue(parsed["error"]!!.toString().contains("No manifest"), "blank guard must explain the failure")
    }

    @Test
    fun `blank manifest guard returns error JSON without calling verifier even with non-blank reason`() = runBlocking {
        val verifier = FakeVerifier()
        val tool = VerifyInstallTool(verifier) { "   " }

        val result = tool.execute(VerifyInstallArgs(reason = "user asked"))

        assertEquals(0, verifier.calls, "whitespace-only manifest must not invoke the verifier")
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertEquals("false", parsed["success"].toString())
    }

    // -----------------------------------------------------------------------
    // W3: oversized verifier output truncation
    // -----------------------------------------------------------------------

    @Test
    fun `oversized verifier output is truncated and keeps the trailing error line`() = runBlocking {
        val verifier = FakeVerifier()
        val errorLine = "ERROR Download failed (404): the manifest checksum does not match"
        // Output far beyond the cap, with the critical error line at the very end.
        val bigOutput = ("progress line\n").repeat(50_000) + errorLine
        verifier.response = buildJsonObject {
            put("success", false)
            put("app_name", "scoop-ai-verify")
            put("exit_code", 1)
            put("output", bigOutput)
        }.toString()
        val tool = VerifyInstallTool(verifier) { """{"version":"1.0"}""" }

        val result = tool.execute(VerifyInstallArgs())

        assertEquals(1, verifier.calls)
        assertTrue(result.length <= VerifyInstallTool.OUTPUT_CAP_CHARS, "truncated result must fit the cap")
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertEquals("false", parsed["success"].toString(), "top-level success must survive truncation")
        assertEquals("1", parsed["exit_code"].toString(), "top-level exit_code must survive truncation")
        val out = parsed["output"]!!.toString()
        assertTrue(out.contains("... (truncated) ..."), "truncated output must carry a truncation marker")
        assertTrue(out.contains(errorLine), "trailing error line must survive in the tail snippet")
        assertTrue(out.contains("progress line"), "head snippet must survive")
    }

    @Test
    fun `small verifier output passes through unchanged without truncation`() = runBlocking {
        val verifier = FakeVerifier()
        val small = """{"success":true,"app_name":"scoop-ai-verify","exit_code":0,"output":"install ok"}"""
        verifier.response = small
        val tool = VerifyInstallTool(verifier) { """{"version":"1.0"}""" }

        val result = tool.execute(VerifyInstallArgs())

        assertEquals(small, result, "small output must pass through byte-identical")
    }
}
