package com.scoopai.core.agent.nodes

import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.LicenseInfo
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.WindowsAsset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicNodesTest {

    private fun buildTestManifest(url: String): JsonObject {
        val manifestStr = """{"version":"1.0.0","architecture":{"64bit":{"url":"$url","hash":"abc123"}},"bin":["app.exe"]}"""
        return Json.parseToJsonElement(manifestStr).jsonObject
    }

    // ---------------------------------------------------------------
    // detectArchitecture
    // ---------------------------------------------------------------

    @Test
    fun `detectArchitecture x86_64 returns 64bit`() {
        assertEquals("64bit", detectArchitecture("tool-x86_64-pc-windows-msvc.zip"))
    }

    @Test
    fun `detectArchitecture x64 returns 64bit`() {
        assertEquals("64bit", detectArchitecture("tool-x64-windows.zip"))
    }

    @Test
    fun `detectArchitecture amd64 returns 64bit`() {
        assertEquals("64bit", detectArchitecture("tool-amd64-windows.zip"))
    }

    @Test
    fun `detectArchitecture win64 returns 64bit`() {
        assertEquals("64bit", detectArchitecture("tool-win64.zip"))
    }

    @Test
    fun `detectArchitecture x86 returns 32bit`() {
        assertEquals("32bit", detectArchitecture("tool-x86-windows.zip"))
    }

    @Test
    fun `detectArchitecture i686 returns 32bit`() {
        assertEquals("32bit", detectArchitecture("tool-i686-pc-windows-msvc.zip"))
    }

    @Test
    fun `detectArchitecture win32 returns 32bit`() {
        assertEquals("32bit", detectArchitecture("tool-win32.zip"))
    }

    @Test
    fun `detectArchitecture arm64 returns arm64`() {
        assertEquals("arm64", detectArchitecture("tool-arm64.zip"))
    }

    @Test
    fun `detectArchitecture aarch64 returns arm64`() {
        assertEquals("arm64", detectArchitecture("tool-aarch64-windows.zip"))
    }

    @Test
    fun `detectArchitecture no arch returns empty string`() {
        assertEquals("", detectArchitecture("tool-portable.zip"))
    }

    // ---------------------------------------------------------------
    // filterWindowsAssets
    // ---------------------------------------------------------------

    @Test
    fun `filterWindowsAssets keeps zip exe msi`() {
        val assets = listOf(
            RawAssetInfo("https://example.com/app.zip", "app.zip"),
            RawAssetInfo("https://example.com/app.exe", "app.exe"),
            RawAssetInfo("https://example.com/app.msi", "app.msi"),
        )
        val result = filterWindowsAssets(assets)
        assertEquals(3, result.size)
    }

    @Test
    fun `filterWindowsAssets excludes darwin linux macos`() {
        val assets = listOf(
            RawAssetInfo("https://example.com/app-darwin-amd64.tar.gz", "app-darwin-amd64.tar.gz"),
            RawAssetInfo("https://example.com/app-linux-amd64.tar.gz", "app-linux-amd64.tar.gz"),
            RawAssetInfo("https://example.com/app-macos-amd64.zip", "app-macos-amd64.zip"),
            RawAssetInfo("https://example.com/app-windows-amd64.zip", "app-windows-amd64.zip"),
        )
        val result = filterWindowsAssets(assets)
        assertEquals(1, result.size)
        assertEquals("app-windows-amd64.zip", result[0].filename)
    }

    @Test
    fun `filterWindowsAssets excludes signatures and checksums`() {
        val assets = listOf(
            RawAssetInfo("https://example.com/app.zip", "app.zip"),
            RawAssetInfo("https://example.com/app.zip.sha256", "app.zip.sha256"),
            RawAssetInfo("https://example.com/app.zip.sig", "app.zip.sig"),
            RawAssetInfo("https://example.com/app.zip.asc", "app.zip.asc"),
            RawAssetInfo("https://example.com/app.zip.pem", "app.zip.pem"),
        )
        val result = filterWindowsAssets(assets)
        assertEquals(1, result.size)
        assertEquals("app.zip", result[0].filename)
    }

    @Test
    fun `filterWindowsAssets excludes source archives`() {
        val assets = listOf(
            RawAssetInfo("https://example.com/app.zip", "app.zip"),
            RawAssetInfo("https://example.com/app-source.tar.gz", "app-source.tar.gz"),
            RawAssetInfo("https://example.com/app.src.tar.gz", "app.src.tar.gz"),
        )
        val result = filterWindowsAssets(assets)
        assertEquals(1, result.size)
        assertEquals("app.zip", result[0].filename)
    }

    // ---------------------------------------------------------------
    // filterExecutables
    // ---------------------------------------------------------------

    @Test
    fun `filterExecutables excludes helper updater uninstall`() {
        val exes = listOf("app.exe", "app-helper.exe", "updater.exe", "uninstall.exe")
        val result = filterExecutables(exes)
        assertEquals(listOf("app.exe"), result)
    }

    @Test
    fun `filterExecutables excludes proxy crash service`() {
        val exes = listOf("app.exe", "proxy.exe", "crashhandler.exe", "service.exe")
        val result = filterExecutables(exes)
        assertEquals(listOf("app.exe"), result)
    }

    @Test
    fun `filterExecutables keeps multiple user-facing exes`() {
        val exes = listOf("app.exe", "app-cli.exe")
        val result = filterExecutables(exes)
        assertEquals(listOf("app.exe", "app-cli.exe"), result)
    }

    // ---------------------------------------------------------------
    // needsAlias
    // ---------------------------------------------------------------

    @Test
    fun `needsAlias clean short name returns false`() {
        assertFalse(needsAlias("app.exe"))
    }

    @Test
    fun `needsAlias long name returns true`() {
        assertTrue(needsAlias("very-long-application-name-that-exceeds-limit.exe"))
    }

    @Test
    fun `needsAlias versioned name returns true`() {
        assertTrue(needsAlias("app-1.2.3.exe"))
    }

    @Test
    fun `needsAlias platform prefix returns true`() {
        assertTrue(needsAlias("cli-app-windows-amd64.exe"))
    }

    @Test
    fun `needsAlias short underscore name returns true`() {
        // localsend_app.exe is actually not clean per our rules — underscored names need alias
        assertTrue(needsAlias("localsend_app.exe"))
    }

    // ---------------------------------------------------------------
    // routeInstallerType
    // ---------------------------------------------------------------

    @Test
    fun `routeInstallerType zip returns ZIP`() {
        assertEquals(InstallerRoute.ZIP, routeInstallerType("zip", isElectron = false))
    }

    @Test
    fun `routeInstallerType msi returns MSI`() {
        assertEquals(InstallerRoute.MSI, routeInstallerType("msi", isElectron = false))
    }

    @Test
    fun `routeInstallerType innosetup returns INNOSETUP`() {
        assertEquals(InstallerRoute.INNOSETUP, routeInstallerType("innosetup", isElectron = false))
    }

    @Test
    fun `routeInstallerType nsis non-electron returns NSIS`() {
        assertEquals(InstallerRoute.NSIS, routeInstallerType("nsis", isElectron = false))
    }

    @Test
    fun `routeInstallerType nsis electron returns NSIS_ELECTRON`() {
        assertEquals(InstallerRoute.NSIS_ELECTRON, routeInstallerType("nsis", isElectron = true))
    }

    @Test
    fun `routeInstallerType empty defaults to ZIP`() {
        assertEquals(InstallerRoute.ZIP, routeInstallerType("", isElectron = false))
    }

    @Test
    fun `routeInstallerType portable_exe returns PORTABLE_EXE`() {
        assertEquals(InstallerRoute.PORTABLE_EXE, routeInstallerType("portable_exe", isElectron = false))
    }

    // ---------------------------------------------------------------
    // applyInstallerTransformations
    // ---------------------------------------------------------------

    @Test
    fun `applyInstallerTransformations NSIS appends hash dl 7z`() {
        val manifest = buildTestManifest(url = "https://example.com/app-setup.exe")
        val result = applyInstallerTransformations(manifest, InstallerRoute.NSIS)
        val url = result.getValue("architecture")
            .jsonObject.getValue("64bit").jsonObject
            .getValue("url").jsonPrimitive.content
        assertTrue(url.endsWith("#/dl.7z"), "NSIS URL should end with #/dl.7z, got: $url")
    }

    @Test
    fun `applyInstallerTransformations ZIP leaves URL unchanged`() {
        val manifest = buildTestManifest(url = "https://example.com/app.zip")
        val result = applyInstallerTransformations(manifest, InstallerRoute.ZIP)
        val url = result.getValue("architecture")
            .jsonObject.getValue("64bit").jsonObject
            .getValue("url").jsonPrimitive.content
        assertEquals("https://example.com/app.zip", url)
    }

    @Test
    fun `applyInstallerTransformations MSI adds extract_dir with backslashes`() {
        val manifest = buildTestManifest(url = "https://example.com/app.msi")
        val result = applyInstallerTransformations(manifest, InstallerRoute.MSI)
        val arch64 = result.getValue("architecture").jsonObject.getValue("64bit").jsonObject
        val extractDir = arch64["extract_dir"]?.jsonPrimitive?.content
        assertTrue(extractDir != null && extractDir.contains("\\"), "MSI extract_dir should use backslashes, got: $extractDir")
    }

    @Test
    fun `applyInstallerTransformations PORTABLE_EXE appends hash app exe`() {
        val manifest = buildTestManifest(url = "https://example.com/app.exe")
        val result = applyInstallerTransformations(manifest, InstallerRoute.PORTABLE_EXE)
        val url = result.getValue("architecture")
            .jsonObject.getValue("64bit").jsonObject
            .getValue("url").jsonPrimitive.content
        assertTrue(url.endsWith("#/app.exe"), "Portable EXE URL should end with #/app.exe, got: $url")
    }

    // ---------------------------------------------------------------
    // prioritizeAssets
    // ---------------------------------------------------------------

    @Test
    fun `prioritizeAssets prefers all over provider-specific`() {
        val assets = listOf(
            WindowsAsset(url = "a1", filename = "terraformer-google-windows-amd64.zip", architecture = "64bit", asset_type = "zip"),
            WindowsAsset(url = "a2", filename = "terraformer-all-windows-amd64.zip", architecture = "64bit", asset_type = "zip"),
        )
        val result = prioritizeAssets(assets)
        assertEquals("terraformer-all-windows-amd64.zip", result[0].filename)
    }

    // -------------------------------------------------------------
    // assembleManifestSkeleton
    // -------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton flat single entry`() {
        val research = ResearchOutput(
            project = "test-app",
            description = "A test app",
            homepage = "https://example.com",
            license = LicenseInfo(value = "MIT"),
            version = "1.2.3",
            suggested_app_name = "test-app",
        )
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/app.zip", hash = "abc123", filename = "app.zip"),
            ),
            bin = listOf("app.exe"),
        )
        val json = assembleManifestSkeleton(research, analyze)
        val obj = parseJsonObject(json)
        assertEquals("1.2.3", obj["version"]?.jsonPrimitive?.content)
        assertEquals("A test app", obj["description"]?.jsonPrimitive?.content)
        assertEquals("https://example.com", obj["homepage"]?.jsonPrimitive?.content)
        assertEquals("MIT", obj["license"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/app.zip", obj["url"]?.jsonPrimitive?.content)
        assertEquals("abc123", obj["hash"]?.jsonPrimitive?.content)
        assertEquals("app.exe", obj["bin"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertTrue(obj.containsKey("url"), "flat url should be at top level")
        assertFalse(obj.containsKey("architecture"), "single entry should NOT have architecture block")
    }

    @Test
    fun `assembleManifestSkeleton flat with extract_dir`() {
        val research = ResearchOutput(
            version = "1.0.0",
        )
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "", url = "https://example.com/app.msi", hash = "abc123", filename = "app.msi", extract_dir = "PFiles\\App"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertEquals("PFiles\\App", obj["extract_dir"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton multi architecture block`() {
        val research = ResearchOutput(version = "2.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/app-x64.zip", hash = "hash64"),
                AssetEntry(architecture = "32bit", url = "https://example.com/app-x86.zip", hash = "hash32"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertTrue(obj.containsKey("architecture"), "multi-arch should have architecture block")
        val arch = obj["architecture"]?.jsonObject
        assertEquals("https://example.com/app-x64.zip", arch?.get("64bit")?.jsonObject?.get("url")?.jsonPrimitive?.content)
        assertEquals("hash32", arch?.get("32bit")?.jsonObject?.get("hash")?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton with optional fields`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            persist = listOf("config"),
            shortcuts = listOf(listOf("app.exe", "My App")),
            pre_install = listOf("New-Item -Path \"config\" -ItemType File -ErrorAction SilentlyContinue"),
            env_set = mapOf("MY_APP_HOME" to "${'$'}dir"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertEquals("config", obj["persist"]?.jsonArray?.single()?.jsonPrimitive?.content)
        val shortcut = obj["shortcuts"]?.jsonArray?.single()?.jsonArray
        assertEquals("app.exe", shortcut?.get(0)?.jsonPrimitive?.content)
        assertEquals("My App", shortcut?.get(1)?.jsonPrimitive?.content)
        assertEquals("New-Item -Path \"config\" -ItemType File -ErrorAction SilentlyContinue", obj["pre_install"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("${'$'}dir", obj["env_set"]?.jsonObject?.get("MY_APP_HOME")?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton omits empty optional fields`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertFalse(obj.containsKey("persist"))
        assertFalse(obj.containsKey("shortcuts"))
        assertFalse(obj.containsKey("pre_install"))
        assertFalse(obj.containsKey("env_set"))
    }

    // ---------------------------------------------------------------
    // Per-arch installer_scripts (NSIS Electron)
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton embeds per-arch installer scripts inside architecture block`() {
        val research = ResearchOutput(version = "1.0.1")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/app-setup-1.0.1.exe#/dl.7z", hash = "h1"),
                AssetEntry(architecture = "32bit", url = "https://example.com/app-setup-1.0.1.exe#/dl.7z", hash = "h1"),
            ),
            bin = emptyList(),
            shortcuts = listOf(listOf("App.exe", "App")),
            pre_install = listOf("Remove-Item \"${'$'}dir\\Uninstall*\" -Force -Recurse"),
            installer_scripts = mapOf(
                "64bit" to listOf(
                    "Expand-7zipArchive \"${'$'}dir\\`${'$'}PLUGINSDIR\\app-64.7z\" \"${'$'}dir\"",
                    "Remove-Item \"${'$'}dir\\`${'$'}PLUGINSDIR\" -Force -Recurse",
                ),
                "32bit" to listOf(
                    "Expand-7zipArchive \"${'$'}dir\\`${'$'}PLUGINSDIR\\app-32.7z\" \"${'$'}dir\"",
                    "Remove-Item \"${'$'}dir\\`${'$'}PLUGINSDIR\" -Force -Recurse",
                ),
            ),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        val arch = obj["architecture"]!!.jsonObject

        // 64bit installer.script
        val installer64 = arch["64bit"]!!.jsonObject["installer"]!!.jsonObject
        val script64 = installer64["script"]!!.jsonArray
        assertEquals(2, script64.size)
        assertTrue(script64[0].jsonPrimitive.content.contains("app-64.7z"))
        assertTrue(script64[1].jsonPrimitive.content.contains("PLUGINSDIR"))

        // 32bit installer.script
        val installer32 = arch["32bit"]!!.jsonObject["installer"]!!.jsonObject
        val script32 = installer32["script"]!!.jsonArray
        assertEquals(2, script32.size)
        assertTrue(script32[0].jsonPrimitive.content.contains("app-32.7z"))
        assertTrue(script32[1].jsonPrimitive.content.contains("PLUGINSDIR"))

        // Global pre_install still present
        val preInstall = obj["pre_install"]!!.jsonArray
        assertEquals(1, preInstall.size)
        assertTrue(preInstall[0].jsonPrimitive.content.contains("Uninstall"))
    }

    @Test
    fun `assembleManifestSkeleton with empty installer_scripts omits installer block`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "u", hash = "h"),
                AssetEntry(architecture = "32bit", url = "u2", hash = "h2"),
            ),
            bin = listOf("app.exe"),
            installer_scripts = emptyMap(),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        val arch64 = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertFalse(arch64.containsKey("installer"))
        val arch32 = obj["architecture"]!!.jsonObject["32bit"]!!.jsonObject
        assertFalse(arch32.containsKey("installer"))
    }

    @Test
    fun `assembleManifestSkeleton with partial installer_scripts only adds to matching architectures`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "u", hash = "h"),
                AssetEntry(architecture = "32bit", url = "u2", hash = "h2"),
            ),
            bin = listOf("app.exe"),
            installer_scripts = mapOf(
                "64bit" to listOf("Expand-7zipArchive \"app-64.7z\" \"${'$'}dir\""),
            ),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        val arch = obj["architecture"]!!.jsonObject

        // 64bit has installer
        assertTrue(arch["64bit"]!!.jsonObject.containsKey("installer"))

        // 32bit does NOT have installer
        assertFalse(arch["32bit"]!!.jsonObject.containsKey("installer"))
    }
}

private fun parseJsonObject(json: String): JsonObject =
    Json.parseToJsonElement(json).jsonObject
