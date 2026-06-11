package com.scoopai.core.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrefetchResultTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `PrefetchResult full round-trip serialization`() {
        val original = PrefetchResult(
            isGitHub = true, owner = "BurntSushi", repo = "ripgrep",
            originalUrl = "https://github.com/BurntSushi/ripgrep",
            repoInfo = RepoInfoSnapshot(
                fullName = "BurntSushi/ripgrep",
                description = "ripgrep recursively searches directories",
                homepage = "https://github.com/BurntSushi/ripgrep",
                license = "MIT",
                defaultBranch = "master",
                stars = 50000,
            ),
            latestRelease = ReleaseSnapshot(
                tagName = "14.1.1",
                prerelease = false,
                assets = listOf(
                    AssetSnapshot("ripgrep-14.1.1-x86_64-pc-windows-msvc.zip", "https://...zip", 3_000_000)
                ),
            ),
            windowsAssets = listOf(
                PrefetchedAsset("https://...zip", "ripgrep-14.1.1-x86_64-pc-windows-msvc.zip", "64bit", "zip", 3_000_000)
            ),
            detectedChecksumsFile = "ripgrep-14.1.1-checksums.txt",
            homepageCleaned = "https://github.com/BurntSushi/ripgrep",
            version = "14.1.1",
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PrefetchResult>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PrefetchResult minimal fields serialization`() {
        val original = PrefetchResult(
            isGitHub = false,
            originalUrl = "https://example.com/some-tool",
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PrefetchResult>(serialized)
        assertEquals(original, deserialized)
        assertTrue(deserialized.windowsAssets.isEmpty())
        assertTrue(deserialized.errors.isEmpty())
    }

    @Test
    fun `RepoInfoSnapshot round-trip`() {
        val original = RepoInfoSnapshot("owner/repo", license = "MIT")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<RepoInfoSnapshot>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `ReleaseSnapshot round-trip`() {
        val original = ReleaseSnapshot("v1.0.0", prerelease = true, assets = listOf(
            AssetSnapshot("app.zip", "https://example.com/app.zip", 1_000_000)
        ))
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ReleaseSnapshot>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PrefetchedAsset round-trip`() {
        val original = PrefetchedAsset("https://example.com/app.zip", "app.zip", "64bit", "zip", 1_000_000)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PrefetchedAsset>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PersistSearchSnapshot round-trip`() {
        val original = PersistSearchSnapshot(
            matches = listOf(
                PersistSearchMatch("src/main.rs", 42, "XDG_CONFIG_HOME", "XDG")
            ),
            filesScanned = 10,
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PersistSearchSnapshot>(serialized)
        assertEquals(original, deserialized)
    }
}
