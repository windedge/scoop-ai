package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseOutputTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------
    // FlexibleBinSerializer
    // ---------------------------------------------------------------

    @Test
    fun `bin as single string is wrapped in array`() {
        val input = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":"app.exe"}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertEquals(1, output.bin.size)
        assertEquals("app.exe", (output.bin[0] as JsonPrimitive).content)
    }

    @Test
    fun `bin as string array is unchanged`() {
        val input = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":["app.exe","alias"]}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertEquals(2, output.bin.size)
        assertEquals("app.exe", (output.bin[0] as JsonPrimitive).content)
        assertEquals("alias", (output.bin[1] as JsonPrimitive).content)
    }

    @Test
    fun `bin as nested array is unchanged`() {
        val input = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":[["app.exe","App Name"]]}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertEquals(1, output.bin.size)
        val nested = output.bin[0] as JsonArray
        assertEquals("app.exe", nested[0].jsonPrimitive.content)
        assertEquals("App Name", nested[1].jsonPrimitive.content)
    }

    @Test
    fun `bin as empty array stays empty`() {
        val input = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":[]}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertTrue(output.bin.isEmpty())
    }

    @Test
    fun `bin defaults to empty list when absent`() {
        val input = """{"status":"ok","strategy":"portable_zip","entries":[]}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertTrue(output.bin.isEmpty())
    }

    // ---------------------------------------------------------------
    // ResearchOutput serialization round-trip
    // ---------------------------------------------------------------

    @Test
    fun `ResearchOutput round-trip`() {
        val original = ResearchOutput(
            status = "ok",
            project = "sharkdp/fd",
            description = "A simple, fast and user-friendly alternative to find",
            homepage = "https://github.com/sharkdp/fd",
            license = LicenseInfo(value = "MIT", source = "GitHub API"),
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    filename = "fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )

        val jsonStr = json.encodeToString(ResearchOutput.serializer(), original)
        val restored = json.decodeFromString<ResearchOutput>(jsonStr)

        assertEquals(original.status, restored.status)
        assertEquals(original.project, restored.project)
        assertEquals(original.version, restored.version)
        assertEquals(original.license?.value, restored.license?.value)
        assertEquals(original.windows_assets.size, restored.windows_assets.size)
        assertEquals(original.windows_assets[0].url, restored.windows_assets[0].url)
        assertEquals(original.windows_assets[0].architecture, restored.windows_assets[0].architecture)
    }

    @Test
    fun `ResearchOutput with minimal fields round-trip`() {
        val original = ResearchOutput(
            status = "no_windows_assets",
            alternatives = "Consider using WSL or a Linux VM."
        )

        val jsonStr = json.encodeToString(ResearchOutput.serializer(), original)
        val restored = json.decodeFromString<ResearchOutput>(jsonStr)

        assertEquals("no_windows_assets", restored.status)
        assertTrue(restored.windows_assets.isEmpty())
    }

    // ---------------------------------------------------------------
    // AnalyzeOutput serialization round-trip
    // ---------------------------------------------------------------

    @Test
    fun `AnalyzeOutput round-trip with all fields`() {
        val original = AnalyzeOutput(
            status = "ok",
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    hash = "abc123def456",
                    filename = "fd-v10.2.0-x86_64-pc-windows-msvc.zip",
                    extract_dir = "fd-v10.2.0-x86_64-pc-windows-msvc"
                )
            ),
            bin = listOf(
                JsonPrimitive("fd.exe"),
                JsonArray(listOf(JsonPrimitive("fd.exe"), JsonPrimitive("fd")))
            ),
            persist = listOf(".fd"),
            shortcuts = listOf(listOf("fd.exe", "FD")),
            installer_type = "zip"
        )

        val jsonStr = json.encodeToString(AnalyzeOutput.serializer(), original)
        val restored = json.decodeFromString<AnalyzeOutput>(jsonStr)

        assertEquals(original.status, restored.status)
        assertEquals(original.strategy, restored.strategy)
        assertEquals(original.entries.size, restored.entries.size)
        assertEquals(original.entries[0].hash, restored.entries[0].hash)
        assertEquals(original.persist.size, restored.persist.size)
        assertEquals(original.shortcuts.size, restored.shortcuts.size)
    }

    // ---------------------------------------------------------------
    // Individual value objects
    // ---------------------------------------------------------------

    @Test
    fun `LicenseInfo round-trip`() {
        val original = LicenseInfo(value = "GPL-3.0-or-later", source = "LICENSE file")
        val jsonStr = json.encodeToString(LicenseInfo.serializer(), original)
        val restored = json.decodeFromString<LicenseInfo>(jsonStr)
        assertEquals(original.value, restored.value)
        assertEquals(original.source, restored.source)
    }

    @Test
    fun `WindowsAsset round-trip`() {
        val original = WindowsAsset(
            url = "https://example.com/app.zip",
            filename = "app.zip",
            architecture = "64bit",
            asset_type = "zip",
            source = "release_api"
        )
        val jsonStr = json.encodeToString(WindowsAsset.serializer(), original)
        val restored = json.decodeFromString<WindowsAsset>(jsonStr)
        assertEquals(original.url, restored.url)
        assertEquals(original.architecture, restored.architecture)
    }

    @Test
    fun `AssetEntry round-trip`() {
        val original = AssetEntry(
            architecture = "32bit",
            url = "https://example.com/app32.zip",
            hash = "abcdef0123456789",
            extract_dir = "app"
        )
        val jsonStr = json.encodeToString(AssetEntry.serializer(), original)
        val restored = json.decodeFromString<AssetEntry>(jsonStr)
        assertEquals(original.architecture, restored.architecture)
        assertEquals(original.extract_dir, restored.extract_dir)
    }
}
