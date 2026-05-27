package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseOutputTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------
    // FlexibleBinSerializer
    // ---------------------------------------------------------------

    @Test
    fun `bin as string array is unchanged`() {
        val input = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":["app.exe","alias"]}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertEquals(2, output.bin.size)
        assertEquals("app.exe", output.bin[0])
        assertEquals("alias", output.bin[1])
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
            status = ResearchStatus.OK,
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
    fun `ResearchOutput checksums_filename round-trip`() {
        val original = ResearchOutput(
            checksums_filename = "checksums.txt",
        )
        val jsonStr = json.encodeToString(ResearchOutput.serializer(), original)
        val restored = json.decodeFromString<ResearchOutput>(jsonStr)
        assertEquals("checksums.txt", restored.checksums_filename)
    }

    @Test
    fun `ResearchOutput checksums_filename defaults to empty when missing from JSON`() {
        val input = """{"status":"ok","project":"test"}"""
        val restored = json.decodeFromString<ResearchOutput>(input)
        assertEquals("", restored.checksums_filename)
    }

    @Test
    fun `ResearchOutput with minimal fields round-trip`() {
        val original = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            alternatives = "Consider using WSL or a Linux VM."
        )

        val jsonStr = json.encodeToString(ResearchOutput.serializer(), original)
        val restored = json.decodeFromString<ResearchOutput>(jsonStr)

        assertEquals(ResearchStatus.NO_WINDOWS_ASSETS, restored.status)
        assertTrue(restored.windows_assets.isEmpty())
    }

    // ---------------------------------------------------------------
    // AnalyzeOutput serialization round-trip
    // ---------------------------------------------------------------

    @Test
    fun `AnalyzeOutput round-trip with all fields`() {
        val original = AnalyzeOutput(
            status = AnalyzeStatus.OK,
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
            bin = listOf("fd.exe"),
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

    @Test
    fun `ResearchOutput with suggested_app_name round-trip`() {
        val original = ResearchOutput(
            status = ResearchStatus.OK,
            project = "ggml-org/llama.cpp",
            version = "b9264",
            suggested_app_name = "llama-cpp-sycl"
        )

        val jsonStr = json.encodeToString(ResearchOutput.serializer(), original)
        val restored = json.decodeFromString<ResearchOutput>(jsonStr)

        assertEquals("llama-cpp-sycl", restored.suggested_app_name)
    }

    @Test
    fun `ResearchOutput suggested_app_name defaults to null`() {
        val input = """{"status":"ok","project":"sharkdp/fd"}"""
        val output = json.decodeFromString<ResearchOutput>(input)
        assertEquals(null, output.suggested_app_name)
    }

    @Test
    fun `AnalyzeOutput with suggested_app_name round-trip`() {
        val original = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            suggested_app_name = "llama-cpp-sycl"
        )

        val jsonStr = json.encodeToString(AnalyzeOutput.serializer(), original)
        val restored = json.decodeFromString<AnalyzeOutput>(jsonStr)

        assertEquals("llama-cpp-sycl", restored.suggested_app_name)
    }

    @Test
    fun `AnalyzeOutput suggested_app_name defaults to null`() {
        val input = """{"status":"ok","strategy":"portable_zip"}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertEquals(null, output.suggested_app_name)
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

    // ---------------------------------------------------------------
    // env_set / env_targets fields
    // ---------------------------------------------------------------

    @Test
    fun `ResearchOutput with persist_targets and env_targets round-trip`() {
        val original = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/bat",
            version = "0.26.1",
            persist_targets = listOf("config", "syntaxes", "themes"),
            env_targets = mapOf("BAT_CONFIG_DIR" to "config")
        )

        val jsonStr = json.encodeToString(ResearchOutput.serializer(), original)
        val restored = json.decodeFromString<ResearchOutput>(jsonStr)

        assertEquals(listOf("config", "syntaxes", "themes"), restored.persist_targets)
        assertEquals(mapOf("BAT_CONFIG_DIR" to "config"), restored.env_targets)
    }

    @Test
    fun `ResearchOutput env_targets defaults to empty map`() {
        val input = """{"status":"ok","project":"sharkdp/fd"}"""
        val output = json.decodeFromString<ResearchOutput>(input)
        assertTrue(output.env_targets.isEmpty())
    }

    @Test
    fun `ResearchOutput persist_targets defaults to empty list`() {
        val input = """{"status":"ok","project":"sharkdp/fd"}"""
        val output = json.decodeFromString<ResearchOutput>(input)
        assertTrue(output.persist_targets.isEmpty())
    }

    @Test
    fun `AnalyzeOutput with env_set round-trip`() {
        val original = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://github.com/sharkdp/bat/releases/download/v0.26.1/bat-v0.26.1-x86_64-pc-windows-msvc.zip",
                    hash = "abc123",
                    extract_dir = "bat-v0.26.1-x86_64-pc-windows-msvc"
                )
            ),
            bin = listOf("bat.exe"),
            persist = listOf("config", "syntaxes", "themes"),
            pre_install = listOf(
                "if (!(Test-Path \"\$persist_dir\\config\")) { New-Item \"\$dir\\config\" -ItemType Directory | Out-Null }",
                "if (!(Test-Path \"\$persist_dir\\syntaxes\")) { New-Item \"\$dir\\syntaxes\" -ItemType Directory | Out-Null }",
                "if (!(Test-Path \"\$persist_dir\\themes\")) { New-Item \"\$dir\\themes\" -ItemType Directory | Out-Null }"
            ),
            env_set = mapOf("BAT_CONFIG_DIR" to "\$dir")
        )

        val jsonStr = json.encodeToString(AnalyzeOutput.serializer(), original)
        val restored = json.decodeFromString<AnalyzeOutput>(jsonStr)

        assertEquals(original.persist, restored.persist)
        assertEquals(original.env_set, restored.env_set)
        assertEquals(3, restored.pre_install.size)
    }

    @Test
    fun `AnalyzeOutput env_set defaults to empty map`() {
        val input = """{"status":"ok","strategy":"portable_zip"}"""
        val output = json.decodeFromString<AnalyzeOutput>(input)
        assertTrue(output.env_set.isEmpty())
    }
}
