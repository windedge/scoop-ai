package com.scoopai.cli

import com.scoopai.core.ManifestResult
import com.scoopai.core.StageInfo
import com.scoopai.core.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonFormatterTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `format produces valid JSON`() {
        val result = ManifestResult(
            manifest = "{}",
            tokenUsage = TokenUsage(100, 50),
            durationMs = 30000,
            stages = emptyList(),
        )
        val output = JsonFormatter.format(result)
        // Should parse without exception
        val parsed = json.parseToJsonElement(output).jsonObject
        assertNotNull(parsed)
    }

    @Test
    fun `output contains all expected fields`() {
        val result = ManifestResult(
            manifest = "{\"version\":\"1.0\"}",
            suggestedAppName = "my-app",
            noAssets = false,
            tokenUsage = TokenUsage(500, 200),
            durationMs = 45000,
            stages = listOf(
                StageInfo("research", 10000, 200, 80, 3),
                StageInfo("analyze", 15000, 300, 120, 6),
            ),
        )
        val output = JsonFormatter.format(result)
        val parsed = json.parseToJsonElement(output).jsonObject

        assertTrue(parsed.containsKey("manifest"), "Output should contain 'manifest'")
        assertTrue(parsed.containsKey("suggestedAppName"), "Output should contain 'suggestedAppName'")
        assertTrue(parsed.containsKey("noAssets"), "Output should contain 'noAssets'")
        assertTrue(parsed.containsKey("durationMs"), "Output should contain 'durationMs'")
        assertTrue(parsed.containsKey("tokenUsage"), "Output should contain 'tokenUsage'")
        assertTrue(parsed.containsKey("stages"), "Output should contain 'stages'")
    }

    @Test
    fun `stages is an array with 5-field objects`() {
        val result = ManifestResult(
            manifest = "{}",
            stages = listOf(
                StageInfo("research", 10000, 200, 80, 3),
            ),
        )
        val output = JsonFormatter.format(result)
        val parsed = json.parseToJsonElement(output).jsonObject
        val stagesArray = parsed["stages"]!!.jsonArray

        assertEquals(1, stagesArray.size)
        val stageObj = stagesArray[0].jsonObject
        assertTrue(stageObj.containsKey("phaseName"))
        assertTrue(stageObj.containsKey("durationMs"))
        assertTrue(stageObj.containsKey("inputTokens"))
        assertTrue(stageObj.containsKey("outputTokens"))
        assertTrue(stageObj.containsKey("toolCallCount"))

        assertEquals("research", stageObj["phaseName"]!!.jsonPrimitive.content)
        assertEquals(10000, stageObj["durationMs"]!!.jsonPrimitive.long)
        assertEquals(200, stageObj["inputTokens"]!!.jsonPrimitive.long)
        assertEquals(80, stageObj["outputTokens"]!!.jsonPrimitive.long)
        assertEquals(3, stageObj["toolCallCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `output ends with newline`() {
        val result = ManifestResult(manifest = "{}")
        val output = JsonFormatter.format(result)
        assertTrue(output.endsWith("\n"), "Output should end with newline")
    }

    @Test
    fun `empty stages produces JSON with empty array`() {
        val result = ManifestResult(
            manifest = "{}",
            stages = emptyList(),
        )
        val output = JsonFormatter.format(result)
        val parsed = json.parseToJsonElement(output).jsonObject
        val stagesArray = parsed["stages"]!!.jsonArray
        assertTrue(stagesArray.isEmpty(), "Empty stages should produce empty array")
    }

    @Test
    fun `output is pretty-printed with 2-space indent`() {
        val result = ManifestResult(manifest = "{}")
        val output = JsonFormatter.format(result)
        // Pretty-printed JSON should have newlines inside the object
        assertTrue(output.contains("\n  "), "Output should be pretty-printed with 2-space indent")
    }

    @Test
    fun `manifests with noAssets produces valid JSON`() {
        val result = ManifestResult(
            manifest = "",
            noAssets = true,
            durationMs = 5000,
        )
        val output = JsonFormatter.format(result)
        val parsed = json.parseToJsonElement(output).jsonObject
        assertEquals(true, parsed["noAssets"]!!.jsonPrimitive.boolean)
        assertEquals(5000, parsed["durationMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun `manifests with null tokenUsage omits tokenUsage field`() {
        val result = ManifestResult(
            manifest = "{}",
            tokenUsage = null,
        )
        val output = JsonFormatter.format(result)
        val parsed = json.parseToJsonElement(output).jsonObject
        // tokenUsage should be null (JsonNull) in JSON when it's null in the result
        assertTrue(parsed["tokenUsage"] is JsonNull, "tokenUsage should be JsonNull when null in result")
    }
}
