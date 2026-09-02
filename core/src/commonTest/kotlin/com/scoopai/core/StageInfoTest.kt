package com.scoopai.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StageInfoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---------------------------------------------------------------
    // StageInfo serialization
    // ---------------------------------------------------------------

    @Test
    fun `StageInfo serializes to JSON with 5 fields`() {
        val stage = StageInfo(
            phaseName = "research",
            durationMs = 5234,
            inputTokens = 1500,
            outputTokens = 320,
            toolCallCount = 4,
        )
        val jsonStr = json.encodeToString(StageInfo.serializer(), stage)
        assertTrue(jsonStr.contains("\"phaseName\""))
        assertTrue(jsonStr.contains("\"research\""))
        assertTrue(jsonStr.contains("\"durationMs\""))
        assertTrue(jsonStr.contains("5234"))
        assertTrue(jsonStr.contains("\"inputTokens\""))
        assertTrue(jsonStr.contains("1500"))
        assertTrue(jsonStr.contains("\"outputTokens\""))
        assertTrue(jsonStr.contains("320"))
        assertTrue(jsonStr.contains("\"toolCallCount\""))
        assertTrue(jsonStr.contains("4"))
    }

    @Test
    fun `StageInfo round-trip serialization`() {
        val original = StageInfo(
            phaseName = "analyze",
            durationMs = 12000,
            inputTokens = 3000,
            outputTokens = 800,
            toolCallCount = 7,
        )
        val jsonStr = json.encodeToString(StageInfo.serializer(), original)
        val restored = json.decodeFromString<StageInfo>(jsonStr)
        assertEquals(original, restored)
    }

    @Test
    fun `StageInfo with zero values round-trip`() {
        val original = StageInfo(
            phaseName = "generate",
            durationMs = 0,
            inputTokens = 0,
            outputTokens = 0,
            toolCallCount = 0,
        )
        val jsonStr = json.encodeToString(StageInfo.serializer(), original)
        val restored = json.decodeFromString<StageInfo>(jsonStr)
        assertEquals(original, restored)
    }

    @Test
    fun `StageInfo with large values round-trip`() {
        val original = StageInfo(
            phaseName = "research",
            durationMs = 999999999,
            inputTokens = Long.MAX_VALUE,
            outputTokens = Long.MAX_VALUE,
            toolCallCount = Int.MAX_VALUE,
        )
        val jsonStr = json.encodeToString(StageInfo.serializer(), original)
        val restored = json.decodeFromString<StageInfo>(jsonStr)
        assertEquals(original, restored)
    }

    // ---------------------------------------------------------------
    // ManifestResult serialization with stages
    // ---------------------------------------------------------------

    @Test
    fun `ManifestResult serializes with stages field`() {
        val result = ManifestResult(
            manifest = "{}",
            tokenUsage = TokenUsage(100, 50),
            durationMs = 30000,
            stages = listOf(
                StageInfo("research", 5000, 100, 50, 2),
                StageInfo("analyze", 8000, 200, 100, 5),
            ),
        )
        val jsonStr = json.encodeToString(ManifestResult.serializer(), result)
        assertTrue(jsonStr.contains("\"stages\""))
        assertTrue(jsonStr.contains("\"research\""))
        assertTrue(jsonStr.contains("\"analyze\""))
        assertTrue(jsonStr.contains("\"phaseName\""))
        assertTrue(jsonStr.contains("\"durationMs\""))
        assertTrue(jsonStr.contains("\"inputTokens\""))
        assertTrue(jsonStr.contains("\"outputTokens\""))
        assertTrue(jsonStr.contains("\"toolCallCount\""))
        assertTrue(jsonStr.contains("\"tokenUsage\""))
        assertTrue(jsonStr.contains("\"manifest\""))
        assertTrue(jsonStr.contains("\"suggestedAppName\""))
        assertTrue(jsonStr.contains("\"noAssets\""))
        assertTrue(jsonStr.contains("\"durationMs\""))
    }

    @Test
    fun `ManifestResult with empty stages`() {
        val result = ManifestResult(
            manifest = "{}",
            stages = emptyList(),
        )
        val jsonStr = json.encodeToString(ManifestResult.serializer(), result)
        assertTrue(jsonStr.contains("\"stages\""))
        // Verify stages is an empty array
        val restored = json.decodeFromString<ManifestResult>(jsonStr)
        assertEquals(emptyList<StageInfo>(), restored.stages)
    }

    @Test
    fun `ManifestResult round-trip with full data`() {
        val original = ManifestResult(
            manifest = "{\"version\":\"1.0\"}",
            suggestedAppName = "my-app",
            noAssets = false,
            tokenUsage = TokenUsage(500, 200),
            durationMs = 45000,
            stages = listOf(
                StageInfo("research", 10000, 200, 80, 3),
                StageInfo("analyze", 15000, 300, 120, 6),
                StageInfo("generate", 12000, 100, 60, 0),
                StageInfo("assist", 8000, 50, 30, 0),
            ),
        )
        val jsonStr = json.encodeToString(ManifestResult.serializer(), original)
        val restored = json.decodeFromString<ManifestResult>(jsonStr)
        assertEquals(original.manifest, restored.manifest)
        assertEquals(original.suggestedAppName, restored.suggestedAppName)
        assertEquals(original.noAssets, restored.noAssets)
        assertEquals(original.tokenUsage?.totalInputTokens, restored.tokenUsage?.totalInputTokens)
        assertEquals(original.tokenUsage?.totalOutputTokens, restored.tokenUsage?.totalOutputTokens)
        assertEquals(original.durationMs, restored.durationMs)
        assertEquals(original.stages.size, restored.stages.size)
        assertEquals(original.stages[0].phaseName, restored.stages[0].phaseName)
        assertEquals(original.stages[0].durationMs, restored.stages[0].durationMs)
        assertEquals(original.stages[0].inputTokens, restored.stages[0].inputTokens)
        assertEquals(original.stages[0].outputTokens, restored.stages[0].outputTokens)
        assertEquals(original.stages[0].toolCallCount, restored.stages[0].toolCallCount)
    }

    @Test
    fun `ManifestResult backwards compatible without stages field`() {
        // JSON without "stages" field should decode with default empty list
        val input = """{"manifest":"{}","suggestedAppName":"test","noAssets":false,"durationMs":1000}"""
        val restored = json.decodeFromString<ManifestResult>(input)
        assertEquals(emptyList<StageInfo>(), restored.stages)
    }
}
