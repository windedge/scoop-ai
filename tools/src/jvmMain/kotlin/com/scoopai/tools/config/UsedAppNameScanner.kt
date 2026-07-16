package com.scoopai.tools.config

import java.io.File

/**
 * Scans local Scoop bucket directories for existing manifest names.
 *
 * Used to detect app name conflicts before writing a new manifest,
 * preventing name collisions (e.g. LLM suggests `micro` but main
 * bucket already has `micro.json` from micro-editor).
 *
 * Designed as an `object` so it can be referenced as a function
 * reference (`UsedAppNameScanner::scanDefault`) when passed as a
 * `usedAppNamesProvider` to [com.scoopai.core.ScoopManifestGenerator].
 */
object UsedAppNameScanner {

    /**
     * Scans [bucketsDir] for `.json` files under `bucket/` subdirectories
     * and returns their names without extension.
     *
     * Structure scanned:
     * ```
     * bucketsDir/
     *   main/bucket/NAME.json
     *   extras/bucket/NAME.json
     *   ...
     * ```
     *
     * Returns an empty set if [bucketsDir] does not exist or contains no manifests.
     */
    fun scan(bucketsDir: File): Set<String> {
        if (!bucketsDir.isDirectory) return emptySet()

        val bucketDirs = bucketsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        return bucketDirs.flatMap { bucketDir ->
            val bucketSubdir = File(bucketDir, "bucket")
            bucketSubdir.listFiles()?.filter { it.extension == "json" }.orEmpty()
        }
            .map { it.nameWithoutExtension }
            .toSet()
    }

    /**
     * Scans the user's local Scoop installation for existing manifest names.
     *
     * Resolution order:
     * 1. `USERPROFILE` environment variable (Windows)
     * 2. `user.home` system property (Unix/macOS fallback)
     *
     * Returns an empty set if Scoop is not installed.
     */
    fun scanDefault(): Set<String> {
        val home = System.getenv("USERPROFILE")
            ?: System.getProperty("user.home")
            ?: return emptySet()
        val bucketsDir = File(home).resolve("scoop").resolve("buckets")
        return scan(bucketsDir)
    }
}
