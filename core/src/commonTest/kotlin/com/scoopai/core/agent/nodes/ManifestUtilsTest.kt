package com.scoopai.core.agent.nodes

import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.LicenseInfo
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.WindowsAsset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestUtilsTest {

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
    fun `assembleManifestSkeleton single entry wraps url hash in architecture block`() {
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
        // url/hash should be inside architecture block, NOT at top level
        assertTrue(obj.containsKey("architecture"), "single entry should have architecture block")
        val arch = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app.zip", arch["url"]?.jsonPrimitive?.content)
        assertEquals("abc123", arch["hash"]?.jsonPrimitive?.content)
        assertFalse(obj.containsKey("url"), "url should NOT be at top level")
        assertFalse(obj.containsKey("hash"), "hash should NOT be at top level")
        assertEquals("app.exe", obj["bin"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton single entry with extract_dir lifts shared extract_dir to top level`() {
        val research = ResearchOutput(
            version = "1.0.0",
        )
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/app.msi", hash = "abc123", filename = "app.msi", extract_dir = "PFiles\\App"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        // url/hash should be in architecture block
        assertTrue(obj.containsKey("architecture"))
        val arch = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app.msi", arch["url"]?.jsonPrimitive?.content)
        assertEquals("abc123", arch["hash"]?.jsonPrimitive?.content)
        assertFalse(arch.containsKey("extract_dir"), "shared extract_dir should NOT be inside arch block")
        // extract_dir lifted to top level (shared since single entry)
        assertEquals("PFiles\\App", obj["extract_dir"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton multi entry architecture block`() {
        val research = ResearchOutput(version = "2.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/app-x64.zip", hash = "hash64"),
                AssetEntry(architecture = "32bit", url = "https://example.com/app-x86.zip", hash = "hash32"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertTrue(obj.containsKey("architecture"), "always has architecture block")
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

    // ---------------------------------------------------------------
    // replaceVersionWithTemplate
    // ---------------------------------------------------------------

    @Test
    fun `replaceVersionWithTemplate preserves v prefix in both path and filename`() {
        val url = "https://github.com/JetpackDuba/Gitnuro/releases/download/v1.5.0/Gitnuro_Windows_Portable_v1.5.0.zip"
        val result = replaceVersionWithTemplate(url, "1.5.0")
        assertEquals(
            "https://github.com/JetpackDuba/Gitnuro/releases/download/v\$version/Gitnuro_Windows_Portable_v\$version.zip",
            result
        )
    }

    @Test
    fun `replaceVersionWithTemplate handles no v prefix`() {
        val url = "https://github.com/BurntSushi/ripgrep/releases/download/15.1.0/ripgrep-15.1.0-x86_64-pc-windows-msvc.zip"
        val result = replaceVersionWithTemplate(url, "15.1.0")
        assertEquals(
            "https://github.com/BurntSushi/ripgrep/releases/download/\$version/ripgrep-\$version-x86_64-pc-windows-msvc.zip",
            result
        )
    }

    @Test
    fun `replaceVersionWithTemplate handles URL-encoded version`() {
        val url = "https://github.com/k0sproject/k0s/releases/download/v1.35.4%2Bk0s.0/k0s-v1.35.4%2Bk0s.0-amd64.exe"
        val result = replaceVersionWithTemplate(url, "1.35.4+k0s.0")
        assertEquals(
            "https://github.com/k0sproject/k0s/releases/download/v\$version/k0s-v\$version-amd64.exe",
            result
        )
    }

    @Test
    fun `replaceVersionWithTemplate preserves fragment suffix`() {
        val url = "https://github.com/k0sproject/k0s/releases/download/v1.35.4%2Bk0s.0/k0s-v1.35.4%2Bk0s.0-amd64.exe#/k0s.exe"
        val result = replaceVersionWithTemplate(url, "1.35.4+k0s.0")
        assertTrue(result.endsWith("#/k0s.exe"), "fragment should be preserved")
        assertEquals(
            "https://github.com/k0sproject/k0s/releases/download/v\$version/k0s-v\$version-amd64.exe#/k0s.exe",
            result
        )
    }

    @Test
    fun `replaceVersionWithTemplate handles extract_dir`() {
        val extractDir = "bat-v0.26.1-x86_64-pc-windows-msvc"
        val result = replaceVersionWithTemplate(extractDir, "0.26.1")
        assertEquals("bat-v\$version-x86_64-pc-windows-msvc", result)
    }

    @Test
    fun `replaceVersionWithTemplate returns original when version not found`() {
        val url = "https://example.com/app.zip"
        val result = replaceVersionWithTemplate(url, "1.0.0")
        assertEquals(url, result)
    }

    @Test
    fun `replaceVersionWithTemplate with blank version returns original`() {
        val url = "https://example.com/app.zip"
        assertEquals(url, replaceVersionWithTemplate(url, ""))
    }

    @Test
    fun `replaceVersionWithTemplate replaces version in extract_dir`() {
        val extractDir = "mask-0.11.7-x86_64-pc-windows-msvc"
        val result = replaceVersionWithTemplate(extractDir, "0.11.7")
        assertEquals("mask-\$version-x86_64-pc-windows-msvc", result)
    }

    // ---------------------------------------------------------------
    // generateAutoupdateArchitectureBlock
    // ---------------------------------------------------------------

    @Test
    fun `generateAutoupdateArchitectureBlock single arch wraps in architecture block`() {
        val entries = listOf(
            AssetEntry(architecture = "64bit", url = "https://github.com/owner/repo/releases/download/v1.0.0/app-v1.0.0.zip", hash = "h", extract_dir = "app-v1.0.0"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "1.0.0")
        // Always architecture wrapper
        assertTrue(result.containsKey("architecture"), "should have architecture wrapper")
        val arch = result["architecture"]!!.jsonObject
        assertEquals(1, arch.size)
        val arch64 = arch["64bit"]!!.jsonObject
        assertEquals(
            "https://github.com/owner/repo/releases/download/v\$version/app-v\$version.zip",
            arch64["url"]?.jsonPrimitive?.content
        )
        assertEquals("app-v\$version", arch64["extract_dir"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generateAutoupdateArchitectureBlock multi arch`() {
        val entries = listOf(
            AssetEntry(architecture = "64bit", url = "https://github.com/owner/repo/releases/download/v1.0.0/app-x64-1.0.0.zip", hash = "h64"),
            AssetEntry(architecture = "32bit", url = "https://github.com/owner/repo/releases/download/v1.0.0/app-x86-1.0.0.zip", hash = "h32"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "1.0.0")
        val arch = result["architecture"]!!.jsonObject
        assertEquals(2, arch.size)
        val url64 = arch["64bit"]!!.jsonObject["url"]?.jsonPrimitive?.content
        val url32 = arch["32bit"]!!.jsonObject["url"]?.jsonPrimitive?.content
        assertTrue(url64!!.contains("\$version"), "64bit URL should have \$version")
        assertTrue(url32!!.contains("\$version"), "32bit URL should have \$version")
    }

    // ---------------------------------------------------------------
    // generateCheckver
    // ---------------------------------------------------------------

    @Test
    fun `generateCheckver with project returns full github URL`() {
        val result = generateCheckver("https://laragon.org", "leokhoa/laragon")
        assertNotNull(result)
        assertEquals("https://github.com/leokhoa/laragon", result!!["github"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generateCheckver with github homepage and no project infers URL`() {
        val result = generateCheckver("https://github.com/owner/repo")
        assertNotNull(result)
        assertEquals("https://github.com/owner/repo", result!!["github"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generateCheckver with non-github homepage and no project returns null`() {
        assertEquals(null, generateCheckver("https://example.com"))
    }

    @Test
    fun `generateCheckver with blank homepage and blank project returns null`() {
        assertEquals(null, generateCheckver(""))
    }

    @Test
    fun `generateCheckver project takes priority over homepage`() {
        val result = generateCheckver("https://laragon.org", "leokhoa/laragon")
        assertNotNull(result)
        assertEquals("https://github.com/leokhoa/laragon", result!!["github"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------
    // assembleManifestSkeleton — autoupdate+checkver presence
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton embeds autoupdate and checkver for GitHub project`() {
        val research = ResearchOutput(
            version = "1.5.0",
            homepage = "https://github.com/JetpackDuba/Gitnuro",
        )
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://github.com/JetpackDuba/Gitnuro/releases/download/v1.5.0/Gitnuro_Windows_Portable_v1.5.0.zip", hash = "abc123"),
            ),
            bin = listOf("Gitnuro.exe"),
        )
        val json = parseJsonObject(assembleManifestSkeleton(research, analyze))
        // checkver present with full github URL
        val checkver = json["checkver"]!!.jsonObject
        assertEquals("https://github.com/JetpackDuba/Gitnuro", checkver["github"]!!.jsonPrimitive.content)
        // autoupdate present with correct template (always architecture wrapper)
        val autoupdate = json["autoupdate"]!!.jsonObject
        assertTrue(autoupdate.containsKey("architecture"), "autoupdate should have architecture wrapper")
        val arch64 = autoupdate["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals(
            "https://github.com/JetpackDuba/Gitnuro/releases/download/v\$version/Gitnuro_Windows_Portable_v\$version.zip",
            arch64["url"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `assembleManifestSkeleton does not add checkver for non-GitHub project`() {
        val research = ResearchOutput(
            version = "1.0.0",
            homepage = "https://example.com",
        )
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "https://example.com/app.zip", hash = "h")),
            bin = listOf("app.exe"),
        )
        val json = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertFalse(json.containsKey("checkver"), "non-GitHub should not have checkver")
        assertTrue(json.containsKey("autoupdate"), "always has autoupdate block")
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
    fun `assembleManifestSkeleton with env_add_path emits env_add_path field`() {
        val research = ResearchOutput(version = "3.1.1")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://github.com/pyenv-win/pyenv-win/archive/v3.1.1.zip", hash = "abc123", extract_dir = "pyenv-win-3.1.1"),
            ),
            bin = emptyList(),
            env_add_path = listOf("pyenv-win\\bin", "pyenv-win\\shims"),
            env_set = mapOf(
                "PYENV" to "\${'$'}dir\\pyenv-win",
                "PYENV_ROOT" to "\${'$'}dir\\pyenv-win",
            ),
            persist = listOf("pyenv-win\\version", "pyenv-win\\shims"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // env_add_path present with correct values
        val envAddPath = obj["env_add_path"]?.jsonArray
        assertNotNull(envAddPath)
        assertEquals(2, envAddPath.size)
        assertEquals("pyenv-win\\bin", envAddPath[0].jsonPrimitive.content)
        assertEquals("pyenv-win\\shims", envAddPath[1].jsonPrimitive.content)

        // env_set also present
        assertEquals("\${'$'}dir\\pyenv-win", obj["env_set"]?.jsonObject?.get("PYENV")?.jsonPrimitive?.content)

        // bin should be absent since empty
        assertFalse(obj.containsKey("bin"))
    }

    @Test
    fun `assembleManifestSkeleton omits env_add_path when empty`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertFalse(obj.containsKey("env_add_path"))
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

    // ---------------------------------------------------------------
    // env_set auto-fill from env_targets
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton auto-fills env_set and persist from env_targets`() {
        val research = ResearchOutput(
            version = "1.0.0",
            env_targets = mapOf("KOMGA_CONFIG_DIR" to "config"),
        )
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // env_set should be auto-filled from env_targets
        val envSet = obj["env_set"]?.jsonObject
        assertNotNull(envSet)
        // config dir will be persisted (auto-added from env_targets), so use $persist_dir
        assertEquals("\${'$'}persist_dir\\config", envSet["KOMGA_CONFIG_DIR"]?.jsonPrimitive?.content)

        // persist should include "config"
        val persist = obj["persist"]?.jsonArray
        assertNotNull(persist, "persist should be present when env_targets adds dirs")
        assertTrue(persist!!.any { it.jsonPrimitive.content == "config" }, "persist should contain 'config'")
    }

    @Test
    fun `assembleManifestSkeleton does not override existing env_set with env_targets`() {
        val research = ResearchOutput(
            version = "1.0.0",
            env_targets = mapOf("KOMGA_CONFIG_DIR" to "config"),
        )
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            env_set = mapOf("KOMGA_CONFIG_DIR" to "\${'$'}dir\\custom"),
            persist = listOf("custom"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // LLM's explicit env_set should be preserved, NOT overridden by env_targets
        // But $dir is normalized to $persist_dir because "custom" is in persist
        assertEquals(
            "\${'$'}persist_dir\\custom",
            obj["env_set"]?.jsonObject?.get("KOMGA_CONFIG_DIR")?.jsonPrimitive?.content
        )
        // persist should NOT get "config" from env_targets when env_set is already present
        assertEquals(1, obj["persist"]?.jsonArray?.size)
        assertEquals("custom", obj["persist"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `env_set uses $persist_dir when target dir is in analyze persist`() {
        // env_target dir that is ALSO explicitly in analyze.persist should use $persist_dir
        val research = ResearchOutput(
            version = "1.0.0",
            env_targets = mapOf("MY_APP_CONFIG" to "config"),
        )
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            persist = listOf("config"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        val envSet = obj["env_set"]?.jsonObject
        assertNotNull(envSet)
        // config IS in allPersistDirs (explicitly in analyze.persist)
        assertEquals("\${'$'}persist_dir\\config", envSet["MY_APP_CONFIG"]?.jsonPrimitive?.content)
    }

    @Test
    fun `env_set uses $persist_dir when target dir is auto-added to persist from env_targets`() {
        // env_target dir that is NOT in analyze.persist but gets auto-added should also use $persist_dir
        val research = ResearchOutput(
            version = "1.0.0",
            env_targets = mapOf("MY_APP_DATA" to "data"),
        )
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            persist = listOf("config"),  // some other dir, not "data"
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        val envSet = obj["env_set"]?.jsonObject
        assertNotNull(envSet)
        // "data" IS in allPersistDirs (via env_target non-blank values), so uses $persist_dir
        assertEquals("\${'$'}persist_dir\\data", envSet["MY_APP_DATA"]?.jsonPrimitive?.content)

        // persist should include both "config" and "data"
        val persist = obj["persist"]?.jsonArray
        assertNotNull(persist)
        assertTrue(persist!!.any { it.jsonPrimitive.content == "config" })
        assertTrue(persist.any { it.jsonPrimitive.content == "data" })
    }

    // ---------------------------------------------------------------
    // env_set normalization: $dir → $persist_dir when dir is in persist
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton normalizes env_set $dir to $persist_dir when dir is in persist`() {
        // LLM provided env_set with $dir\config, and "config" is in analyze.persist
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            env_set = mapOf("MYAPP_CONFIG_DIR" to "\${'$'}dir\\config"),
            persist = listOf("config"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        val envSet = obj["env_set"]?.jsonObject
        assertNotNull(envSet)
        // "config" is persisted, so $dir should be replaced with $persist_dir
        assertEquals("\${'$'}persist_dir\\config", envSet["MYAPP_CONFIG_DIR"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton keeps $dir in env_set when dir not in persist`() {
        // LLM provided env_set with $dir\logs, but "logs" is NOT in analyze.persist
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            env_set = mapOf("MYAPP_LOG_DIR" to "\${'$'}dir\\logs"),
            persist = listOf("config"),  // some other dir, not "logs"
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        val envSet = obj["env_set"]?.jsonObject
        assertNotNull(envSet)
        // "logs" is NOT in persist, so $dir should be kept as-is
        assertEquals("\${'$'}dir\\logs", envSet["MYAPP_LOG_DIR"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton keeps $persist_dir in env_set when already correct`() {
        // LLM already correctly used $persist_dir
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
            env_set = mapOf("MYAPP_CONFIG_DIR" to "\${'$'}persist_dir"),
            persist = listOf("config"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        val envSet = obj["env_set"]?.jsonObject
        assertNotNull(envSet)
        // Already uses $persist_dir, should remain unchanged
        assertEquals("\${'$'}persist_dir", envSet["MYAPP_CONFIG_DIR"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton empty env_targets does not add env_set`() {
        val research = ResearchOutput(
            version = "1.0.0",
            env_targets = emptyMap(),
        )
        val analyze = AnalyzeOutput(
            entries = listOf(AssetEntry(architecture = "64bit", url = "u", hash = "h")),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertFalse(obj.containsKey("env_set"), "should not have env_set when env_targets is empty")
        assertFalse(obj.containsKey("persist"), "should not have persist when nothing to persist")
    }

    // ---------------------------------------------------------------
    // extract_dir: shared across all architectures → lifted to top level
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton lifts shared extract_dir out of architecture block`() {
        val research = ResearchOutput(version = "1.4")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/SuperF4-1.4-64bit.zip", hash = "h64", extract_dir = "SuperF4"),
                AssetEntry(architecture = "32bit", url = "https://example.com/SuperF4-1.4-32bit.zip", hash = "h32", extract_dir = "SuperF4"),
            ),
            bin = listOf("SuperF4.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // extract_dir should be at top level, NOT inside architecture block
        assertEquals("SuperF4", obj["extract_dir"]?.jsonPrimitive?.content)
        val arch64 = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        val arch32 = obj["architecture"]!!.jsonObject["32bit"]!!.jsonObject
        assertFalse(arch64.containsKey("extract_dir"), "64bit should not have extract_dir when shared")
        assertFalse(arch32.containsKey("extract_dir"), "32bit should not have extract_dir when shared")
    }

    @Test
    fun `assembleManifestSkeleton keeps extract_dir inside arch block when different`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "u64", hash = "h64", extract_dir = "app-64"),
                AssetEntry(architecture = "32bit", url = "u32", hash = "h32", extract_dir = "app-32"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // extract_dir should NOT be at top level when different
        assertFalse(obj.containsKey("extract_dir"))
        val arch64 = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        val arch32 = obj["architecture"]!!.jsonObject["32bit"]!!.jsonObject
        assertEquals("app-64", arch64["extract_dir"]?.jsonPrimitive?.content)
        assertEquals("app-32", arch32["extract_dir"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton does not lift extract_dir when only some entries have it`() {
        val research = ResearchOutput(version = "1.0.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "u64", hash = "h64", extract_dir = "app"),
                AssetEntry(architecture = "32bit", url = "u32", hash = "h32"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // Mixed: some have extract_dir, some don't → keep inside arch blocks
        assertFalse(obj.containsKey("extract_dir"))
        val arch64 = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        val arch32 = obj["architecture"]!!.jsonObject["32bit"]!!.jsonObject
        assertEquals("app", arch64["extract_dir"]?.jsonPrimitive?.content)
        assertFalse(arch32.containsKey("extract_dir"))
    }

    // ---------------------------------------------------------------
    // normalizeEnvSet
    // ---------------------------------------------------------------

    @Test
    fun `normalizeEnvSet replaces dir with persist_dir when dir is in persist`() {
        // Input JSON has $dir\config → valid JSON requires \\ for backslash
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}dir\\config"},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("config"), persist)
    }

    @Test
    fun `normalizeEnvSet replaces dir and adds new dir to persist`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_LOGS":"${'$'}dir\\logs"},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\logs", envSet["MYAPP_LOGS"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("logs" in persist)
        assertTrue("config" in persist)
    }

    @Test
    fun `normalizeEnvSet handles manifest without env_set`() {
        val input = """{"version":"1.0"}"""
        val result = normalizeEnvSet(input)
        assertEquals(input, result)
    }

    @Test
    fun `normalizeEnvSet replaces dir and creates persist when persist missing`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}dir\\config"}}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("config"), persist)
    }

    @Test
    fun `normalizeEnvSet handles invalid JSON gracefully`() {
        val input = "not json"
        val result = normalizeEnvSet(input)
        assertEquals(input, result)
    }

    @Test
    fun `normalizeEnvSet handles empty env_set`() {
        val input = """{"version":"1.0","env_set":{},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        assertTrue(json["env_set"]!!.jsonObject.isEmpty())
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("config"), persist)
    }

    @Test
    fun `normalizeEnvSet replaces multiple dir values when all in persist`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}dir\\config","MYAPP_DATA":"${'$'}dir\\data"},"persist":["config","data"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        assertEquals("${'$'}persist_dir\\data", envSet["MYAPP_DATA"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("config" in persist)
        assertTrue("data" in persist)
    }

    @Test
    fun `normalizeEnvSet replaces all dir values and adds missing to persist`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}dir\\config","MYAPP_LOGS":"${'$'}dir\\logs"},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        assertEquals("${'$'}persist_dir\\logs", envSet["MYAPP_LOGS"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("config" in persist)
        assertTrue("logs" in persist)
    }

    @Test
    fun `normalizeEnvSet handles dir with nested path`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}dir\\subdir\\config"},"persist":["subdir\\config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\subdir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("subdir\\config" in persist)
    }

    @Test
    fun `normalizeEnvSet preserves dir without trailing backslash`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_HOME":"${'$'}dir"},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}dir", envSet["MYAPP_HOME"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("config"), persist)
    }

    @Test
    fun `normalizeEnvSet preserves persist_dir values`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}persist_dir\\config"},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("config"), persist)
    }

    @Test
    fun `normalizeEnvSet does not duplicate dirs in persist`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_X":"${'$'}dir\\config","MYAPP_Y":"${'$'}dir\\config"},"persist":["config"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_X"]!!.jsonPrimitive.content)
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_Y"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("config"), persist)
    }

    @Test
    fun `normalizeEnvSet handles mixed dir and non-dir values`() {
        val input = """{"version":"1.0","env_set":{"MYAPP_CONFIG":"${'$'}dir\\config","MYAPP_PATH":"C:\\Program Files\\MyApp"},"persist":["data"]}"""
        val result = normalizeEnvSet(input)
        val json = Json.parseToJsonElement(result).jsonObject
        val envSet = json["env_set"]!!.jsonObject
        assertEquals("${'$'}persist_dir\\config", envSet["MYAPP_CONFIG"]!!.jsonPrimitive.content)
        assertEquals("C:\\Program Files\\MyApp", envSet["MYAPP_PATH"]!!.jsonPrimitive.content)
        val persist = json["persist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("data" in persist)
        assertTrue("config" in persist)
    }

    // ---------------------------------------------------------------
    // autoupdate: always uses architecture wrapper
    // ---------------------------------------------------------------

    @Test
    fun `generateAutoupdateArchitectureBlock single entry wraps in architecture`() {
        val entries = listOf(
            AssetEntry(architecture = "64bit", url = "https://github.com/leokhoa/laragon/releases/download/8.6.1/laragon-wamp.exe", hash = "h"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "8.6.1")
        // Always architecture wrapper
        assertTrue(result.containsKey("architecture"), "should have architecture wrapper")
        val arch64 = result["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://github.com/leokhoa/laragon/releases/download/\$version/laragon-wamp.exe",
            arch64["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generateAutoupdateArchitectureBlock single entry with extract_dir wraps in architecture`() {
        val entries = listOf(
            AssetEntry(architecture = "64bit", url = "https://example.com/app-1.0.zip", hash = "h", extract_dir = "app-1.0"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "1.0")
        assertTrue(result.containsKey("architecture"), "should have architecture wrapper")
        val arch64 = result["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app-\$version.zip", arch64["url"]?.jsonPrimitive?.content)
        assertEquals("app-\$version", arch64["extract_dir"]?.jsonPrimitive?.content)
    }

    // ---------------------------------------------------------------
    // Versioned bin: per-arch bin with aliases when exe contains version
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton with versioned bin puts bin inside architecture block`() {
        val research = ResearchOutput(version = "3.1.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/xdelta3-3.1.0-x86_64.exe.zip", hash = "h64", filename = "xdelta3-3.1.0-x86_64.exe.zip"),
                AssetEntry(architecture = "32bit", url = "https://example.com/xdelta3-3.1.0-i686.exe.zip", hash = "h32", filename = "xdelta3-3.1.0-i686.exe.zip"),
            ),
            bin = listOf("xdelta3-3.1.0-x86_64.exe"),
            versioned_bin = true,
            bin_aliases = listOf("xdelta3"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))

        // No top-level bin
        assertFalse(obj.containsKey("bin"), "versioned bin should NOT be at top level")
        // No pre_install
        assertFalse(obj.containsKey("pre_install"), "versioned bin should NOT generate pre_install")

        // Bin is inside each arch block with alias
        val arch64 = obj["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        val arch32 = obj["architecture"]!!.jsonObject["32bit"]!!.jsonObject
        assertTrue(arch64.containsKey("bin"), "64bit arch should have bin")
        assertTrue(arch32.containsKey("bin"), "32bit arch should have bin")

        // Bin format: [["versioned.exe", "alias"]]
        val bin64 = arch64["bin"]!!.jsonArray
        assertEquals(1, bin64.size)
        val binEntry = bin64[0].jsonArray
        assertEquals("xdelta3-3.1.0-x86_64.exe", binEntry[0].jsonPrimitive.content)
        assertEquals("xdelta3", binEntry[1].jsonPrimitive.content)
    }

    @Test
    fun `generateAutoupdateArchitectureBlock with versioned bin includes bin in autoupdate`() {
        val entries = listOf(
            AssetEntry(architecture = "64bit", url = "https://example.com/xdelta3-3.1.0-x86_64.exe.zip", hash = "h64", filename = "xdelta3-3.1.0-x86_64.exe.zip"),
            AssetEntry(architecture = "32bit", url = "https://example.com/xdelta3-3.1.0-i686.exe.zip", hash = "h32", filename = "xdelta3-3.1.0-i686.exe.zip"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "3.1.0", versionedBin = true, binAliases = listOf("xdelta3"))

        val arch = result["architecture"]!!.jsonObject
        val arch64 = arch["64bit"]!!.jsonObject
        val arch32 = arch["32bit"]!!.jsonObject

        // 64bit autoupdate should have bin with $version template
        assertTrue(arch64.containsKey("bin"))
        val bin64 = arch64["bin"]!!.jsonArray
        assertEquals(1, bin64.size)
        val binEntry = bin64[0].jsonArray
        val binExe = binEntry[0].jsonPrimitive.content
        assertTrue(binExe.contains("\$version"), "autoupdate bin should use \$version: $binExe")
        assertEquals("xdelta3", binEntry[1].jsonPrimitive.content)

        // 32bit also
        assertTrue(arch32.containsKey("bin"))
    }

    // ---------------------------------------------------------------
    // enforceArchitectureWrap
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // isArchSpecificUrl
    // ---------------------------------------------------------------

    @Test
    fun `isArchSpecificUrl returns false for noarch URL without arch keywords`() {
        assertFalse(isArchSpecificUrl("https://example.com/audioswitcher-1.8.zip"))
    }

    @Test
    fun `isArchSpecificUrl returns false for noarch URL with version only`() {
        assertFalse(isArchSpecificUrl("https://example.com/tool-portable.zip"))
    }

    @Test
    fun `isArchSpecificUrl returns true for URL with x64`() {
        assertTrue(isArchSpecificUrl("https://example.com/app-x64.zip"))
    }

    @Test
    fun `isArchSpecificUrl returns true for URL with win64`() {
        assertTrue(isArchSpecificUrl("https://example.com/app-win64.exe"))
    }

    @Test
    fun `isArchSpecificUrl returns true for URL with arm64`() {
        assertTrue(isArchSpecificUrl("https://example.com/app-arm64.zip"))
    }

    @Test
    fun `isArchSpecificUrl returns true for URL with x86`() {
        assertTrue(isArchSpecificUrl("https://example.com/app-x86.zip"))
    }

    @Test
    fun `isArchSpecificUrl ignores fragment suffix when checking URL`() {
        // #/dl.7z fragment should not prevent arch detection
        assertTrue(isArchSpecificUrl("https://example.com/app-win64.exe#/dl.7z"))
    }

    @Test
    fun `isArchSpecificUrl returns true for MSI installer with x64`() {
        assertTrue(isArchSpecificUrl("https://example.com/Monokle-win-2.4.8-x64.exe"))
    }

    @Test
    fun `isArchSpecificUrl returns false for pure ZIP without arch keywords`() {
        assertFalse(isArchSpecificUrl("https://example.com/audioswitcher-1.8.zip"))
    }

    // ---------------------------------------------------------------
    // enforceArchitectureWrap — noarch protection
    // ---------------------------------------------------------------

    @Test
    fun `enforceArchitectureWrap skips noarch URL without arch keywords`() {
        val input = """{"version":"1.8","url":"https://example.com/audioswitcher-1.8.zip","hash":"abc123","bin":["AudioSwitcher.exe"]}"""
        val result = enforceArchitectureWrap(input)
        assertEquals(input, result)  // 不变
    }

    @Test
    fun `enforceArchitectureWrap wraps URL with x64 keyword`() {
        val input = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        assertTrue(json.containsKey("architecture"))
        assertFalse(json.containsKey("url"))
        val bit64 = json["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app-x64.zip", bit64["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enforceArchitectureWrap wraps URL with win64 keyword`() {
        val input = """{"version":"1.0","url":"https://example.com/app-win64.exe#/dl.7z","hash":"abc123","bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        assertTrue(json.containsKey("architecture"))
        val bit64 = json["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app-win64.exe#/dl.7z", bit64["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enforceArchitectureWrap wraps MSI installer with x64 keyword`() {
        val input = """{"version":"2.4.8","url":"https://example.com/Monokle-win-2.4.8-x64.exe","hash":"def456"}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        assertTrue(json.containsKey("architecture"))
        assertFalse(json.containsKey("url"))
    }

    @Test
    fun `enforceArchitectureWrap moves top-level url and hash into architecture 64bit`() {
        val input = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        // 顶层不应有 url/hash
        assertFalse(json.containsKey("url"))
        assertFalse(json.containsKey("hash"))
        // architecture.64bit 应有 url/hash
        val arch = json["architecture"]!!.jsonObject
        val bit64 = arch["64bit"]!!.jsonObject
        assertEquals("https://example.com/app-x64.zip", bit64["url"]!!.jsonPrimitive.content)
        assertEquals("abc123", bit64["hash"]!!.jsonPrimitive.content)
        // 其他字段保持
        assertEquals("1.0", json["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enforceArchitectureWrap moves extract_dir too`() {
        val input = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc","extract_dir":"myapp-1.0","bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        assertFalse(json.containsKey("url"))
        val bit64 = json["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app-x64.zip", bit64["url"]!!.jsonPrimitive.content)
        // extract_dir 也应在 64bit 内
        assertEquals("myapp-1.0", bit64["extract_dir"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enforceArchitectureWrap skips if architecture already exists`() {
        val input = """{"version":"1.0","architecture":{"64bit":{"url":"https://example.com/app.zip","hash":"abc"}}}"""
        val result = enforceArchitectureWrap(input)
        assertEquals(input, result)  // 不变
    }

    @Test
    fun `enforceArchitectureWrap handles manifest without url`() {
        val input = """{"version":"1.0","bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        assertEquals(input, result)  // 不变
    }

    @Test
    fun `enforceArchitectureWrap moves autoupdate flat url into architecture 64bit`() {
        val input = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","autoupdate":{"url":"https://example.com/app-${'$'}version-x64.zip","hash":{"url":"https://example.com/checksums-${'$'}version.txt"}},"bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        // autoupdate flat url -> architecture wrapper
        val autoupdate = json["autoupdate"]!!.jsonObject
        assertFalse(autoupdate.containsKey("url"), "autoupdate.url should NOT be flat")
        val autoArch = autoupdate["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals("https://example.com/app-${'$'}version-x64.zip", autoArch["url"]!!.jsonPrimitive.content)
        // autoupdate.hash should still be preserved (not part of 64bit)
        assertTrue(autoupdate.containsKey("hash"))
    }

    @Test
    fun `enforceArchitectureWrap skips autoupdate if autoupdate already has architecture`() {
        val input = """{"version":"1.0","url":"https://example.com/app.zip","hash":"abc","autoupdate":{"architecture":{"64bit":{"url":"https://example.com/app-${'$'}version.zip"}}},"bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        // autoupdate should remain unchanged
        val autoupdate = json["autoupdate"]!!.jsonObject
        assertTrue(autoupdate.containsKey("architecture"))
        assertFalse(autoupdate.containsKey("url"))
    }

    @Test
    fun `enforceArchitectureWrap handles autoupdate without url`() {
        val input = """{"version":"1.0","url":"https://example.com/app.zip","hash":"abc","autoupdate":{"hash":{"url":"https://example.com/checksums-${'$'}version.txt"}},"bin":["app.exe"]}"""
        val result = enforceArchitectureWrap(input)
        val json = parseJsonObject(result)
        val autoupdate = json["autoupdate"]!!.jsonObject
        // autoupdate has no url field, so it shouldn't be touched
        assertFalse(autoupdate.containsKey("architecture"), "autoupdate without url should not get architecture")
        assertTrue(autoupdate.containsKey("hash"))
    }

    // ---------------------------------------------------------------
    // assembleManifestSkeleton — noarch flat format
    // ---------------------------------------------------------------

    @Test
    fun `assembleManifestSkeleton single noarch entry uses flat format`() {
        val research = ResearchOutput(version = "1.8")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "", url = "https://example.com/audioswitcher-1.8.zip", hash = "abc123", filename = "audioswitcher-1.8.zip"),
            ),
            bin = listOf("AudioSwitcher.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertFalse(obj.containsKey("architecture"), "noarch should NOT have architecture block")
        assertEquals("https://example.com/audioswitcher-1.8.zip", obj["url"]?.jsonPrimitive?.content)
        assertEquals("abc123", obj["hash"]?.jsonPrimitive?.content)
        assertEquals("1.8", obj["version"]?.jsonPrimitive?.content)
        assertEquals("AudioSwitcher.exe", obj["bin"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `assembleManifestSkeleton keeps architecture block when architecture is explicitly set even with noarch URL`() {
        val research = ResearchOutput(version = "1.0")
        val analyze = AnalyzeOutput(
            entries = listOf(
                AssetEntry(architecture = "64bit", url = "https://example.com/app.zip", hash = "abc123", filename = "app.zip"),
            ),
            bin = listOf("app.exe"),
        )
        val obj = parseJsonObject(assembleManifestSkeleton(research, analyze))
        assertTrue(obj.containsKey("architecture"), "explicit 64bit should have architecture block")
        assertFalse(obj.containsKey("url"))
    }

    // ---------------------------------------------------------------
    // generateAutoupdateArchitectureBlock — noarch flat format
    // ---------------------------------------------------------------

    @Test
    fun `generateAutoupdateArchitectureBlock noarch single entry uses flat format`() {
        val entries = listOf(
            AssetEntry(architecture = "", url = "https://example.com/audioswitcher-1.8.zip", hash = "abc123"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "1.8")
        assertFalse(result.containsKey("architecture"), "noarch autoupdate should NOT have architecture wrapper")
        assertEquals(
            "https://example.com/audioswitcher-\$version.zip",
            result["url"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `generateAutoupdateArchitectureBlock single arch entry still wraps in architecture when arch is explicit`() {
        val entries = listOf(
            AssetEntry(architecture = "64bit", url = "https://github.com/owner/repo/releases/download/v1.0.0/app-v1.0.0.zip", hash = "h", extract_dir = "app-v1.0.0"),
        )
        val result = generateAutoupdateArchitectureBlock(entries, "1.0.0")
        assertTrue(result.containsKey("architecture"), "explicit 64bit should have architecture wrapper")
        val arch64 = result["architecture"]!!.jsonObject["64bit"]!!.jsonObject
        assertEquals(
            "https://github.com/owner/repo/releases/download/v\$version/app-v\$version.zip",
            arch64["url"]?.jsonPrimitive?.content
        )
    }
}

private fun parseJsonObject(json: String): JsonObject =
    Json.parseToJsonElement(json).jsonObject
