package com.scoopai.tools.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Manages downloads in the Scoop cache directory.
 *
 * Two-phase caching:
 * 1. **Download phase** — files are cached as `{sha256(url)[:7]}{ext}` (URL-based key).
 *    All tools share the same cached file, so a URL is downloaded only once.
 * 2. **Finalize phase** — after the agent finishes and the app name + version are known,
 *    call [finalizeCacheEntry] to rename/link to Scoop's standard format:
 *    `{app}#{version}#{sha256(url)[:7]}{ext}`.
 *
 * This lets `scoop install` reuse the downloaded file directly.
 */
class ScoopCacheManager(
    private val keepCache: Boolean = true,
    private val maxDownloadBytes: Long = 200L * 1024 * 1024,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    customCacheDir: Path? = null,
) {
    companion object {
        /** Strip Scoop fragment (#/xxx) from URL for cache key normalization. */
        private fun normalizeUrl(url: String): String =
            url.substringBefore("#")

        /** Resolve Scoop cache directory from env / default. */
        fun scoopCacheDir(): Path {
            val envDir = System.getenv("SCOOP_CACHE")
            if (!envDir.isNullOrBlank()) return Path.of(envDir)

            // Try ~/scoop/cache/ (standard Scoop layout)
            val userHome = System.getProperty("user.home")
            val scoopDir = System.getenv("SCOOP")
            val cacheDir = if (!scoopDir.isNullOrBlank()) {
                Path.of(scoopDir).resolve("cache")
            } else {
                Path.of(userHome).resolve("scoop").resolve("cache")
            }
            Files.createDirectories(cacheDir)
            return cacheDir
        }

        /** Compute the short hash (first 7 hex chars) of a URL string.
         * Fragment (#/...) is stripped before hashing so that `url` and
         * `url#/dl.7z` produce the same cache key. */
        fun urlHash(url: String): String {
            val normalized = normalizeUrl(url)
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }.take(7)
        }

        /** Extract file extension from URL (e.g. ".msi", ".zip"). */
        fun urlExtension(url: String): String {
            val path = url.substringBefore("?").substringAfterLast("/")
            val dotIdx = path.lastIndexOf('.')
            return if (dotIdx >= 0) path.substring(dotIdx) else ""
        }

        /**
         * Build the standard Scoop cache filename: `{app}#{version}#{urlHash}{ext}`
         */
        fun scoopCacheName(appName: String, version: String, url: String): String {
            return "${appName}#${version}#${urlHash(url)}${urlExtension(url)}"
        }

        /**
         * Build the download-phase cache filename: `{urlHash}{ext}`
         */
        fun downloadCacheName(url: String): String {
            return "${urlHash(url)}${urlExtension(url)}"
        }
    }

    /** URL -> cached local file path (populated on download). */
    private val downloadCache = mutableMapOf<String, Path>()

    /** Files to delete on cleanup (only when keepCache = false). */
    private val tempFiles = mutableListOf<Path>()

    private val cacheDir: Path = customCacheDir ?: scoopCacheDir()

    /**
     * Get a file from cache, or download it.
     * Returns the local file path.
     */
    fun getOrDownload(url: String): Path {
        // 1. Check in-memory cache (same JVM run)
        downloadCache[url]?.let { cached ->
            if (Files.exists(cached)) {
                logger.info { "[cache] Memory hit: $url -> ${cached.fileName}" }
                return cached
            }
            downloadCache.remove(url)
        }

        // 2. Check disk cache (download-phase file)
        val downloadName = downloadCacheName(url)
        val diskCached = cacheDir.resolve(downloadName)
        if (Files.exists(diskCached) && Files.size(diskCached) > 0) {
            downloadCache[url] = diskCached
            logger.info { "[cache] Disk hit (download): $url -> ${diskCached.fileName}" }
            return diskCached
        }

        // 3. Check if a finalized Scoop cache file exists for this URL
        val hash = urlHash(url)
        val ext = urlExtension(url)
        if (Files.exists(cacheDir)) {
            val existing = Files.list(cacheDir).use { stream ->
                stream.filter { it.fileName.toString().contains("#$hash$ext") }
                    .findFirst()
                    .orElse(null)
            }
            if (existing != null) {
                downloadCache[url] = existing
                logger.info { "[cache] Disk hit (finalized): $url -> ${existing.fileName}" }
                return existing
            }
        }

        // 4. Download to cache directory
        Files.createDirectories(cacheDir)
        val targetFile = cacheDir.resolve(downloadName)
        logger.info { "[cache] Downloading: $url -> ${targetFile.fileName}" }
        download(url, targetFile)

        downloadCache[url] = targetFile
        if (!keepCache) {
            tempFiles.add(targetFile)
        }
        return targetFile
    }

    /**
     * Download only the first [maxBytes] of a URL using HTTP Range request.
     *
     * Useful for lightweight installer-type detection without downloading a
     * potentially large (200MB+) file entirely.
     *
     * The downloaded header is cached in a temp directory (not the Scoop cache).
     * Returns the bytes as a ByteArray.
     */
    fun peekHeader(url: String, maxBytes: Int = 1024 * 1024): ByteArray {
        logger.info { "[cache] Peeking header: $url (max $maxBytes bytes)" }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "scoop-ai/0.1.0")
            .header("Range", "bytes=0-${maxBytes - 1}")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val status = response.statusCode()
        if (status !in 200..206) {
            throw IllegalStateException("HTTP $status for Range request to $url")
        }
        val bytes = response.body()
        logger.info { "[cache] Peeked ${bytes.size} bytes from $url (status=$status)" }
        return bytes
    }

    // -----------------------------------------------------------------------
    // F1: Content-Length pre-check via HEAD
    // -----------------------------------------------------------------------

    /**
     * Send a HEAD request to check Content-Length before downloading.
     * Returns -1 if HEAD is not supported (e.g. 405) or Content-Length is unknown,
     * allowing the caller to fall back to streaming size check (F2).
     *
     * This is `internal` so that [ZipArchiveInspector] can also use it for
     * lightweight size checks before deciding to download full files.
     */
    internal fun getContentLength(url: String): Long {
        return try {
            val headRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "scoop-ai/0.1.0")
                .build()
            val response = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) {
                response.headers().firstValue("Content-Length")
                    .map { it.toLongOrNull() ?: -1 }
                    .orElse(-1)
            } else {
                -1  // HEAD not supported, fallback to streaming check
            }
        } catch (_: Exception) {
            -1  // Network error, fallback to streaming check
        }
    }

    private fun download(url: String, target: Path) {
        // F1: Content-Length pre-check via HEAD (before any data transfer)
        val contentLength = getContentLength(url)
        if (contentLength > 0 && contentLength > maxDownloadBytes) {
            throw IllegalStateException(
                "File too large: ${contentLength / 1024 / 1024}MB exceeds ${maxDownloadBytes / 1024 / 1024}MB limit"
            )
        }

        val tmpTarget = target.resolveSibling("${target.fileName}.downloading")
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "scoop-ai/0.1.0")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()} for $url")
            }

            // F2: Stream with byte counting — interrupt if exceeding limit
            response.body().use { input ->
                Files.newOutputStream(tmpTarget).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                        if (totalRead > maxDownloadBytes) {
                            throw IllegalStateException(
                                "File too large: exceeded ${maxDownloadBytes / 1024 / 1024}MB during download"
                            )
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            // Atomic move: .downloading -> final name
            Files.move(tmpTarget, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmpTarget)
            throw e
        }
    }

    /**
     * After the agent finishes, rename the downloaded file to Scoop's standard cache format.
     * If the target already exists, this is a no-op.
     *
     * @return the finalized cache path, or null if the URL was never downloaded
     */
    fun finalizeCacheEntry(url: String, appName: String, version: String): Path? {
        logger.info { "[cache] finalize: url=$url, app=$appName, ver=$version, cached-keys=${downloadCache.keys}" }
        var downloaded = downloadCache[url]
        // If not in memory cache, scan disk for any file with matching URL hash
        if (downloaded == null || !Files.exists(downloaded)) {
            val hash = urlHash(url)
            if (Files.exists(cacheDir)) {
                val existing = Files.list(cacheDir).use { stream ->
                    stream.filter { it.fileName.toString().contains(hash) }
                        .findFirst().orElse(null)
                }
                if (existing != null) {
                    logger.info { "[cache] Disk scan found: ${existing.fileName}" }
                    downloaded = existing
                    downloadCache[url] = existing
                }
            }
        }
        if (downloaded == null) {
            logger.warn { "[cache] finalize: no cached file for $url" }
            return null
        }
        if (!Files.exists(downloaded)) {
            logger.warn { "[cache] finalize: file gone: ${downloaded.fileName}" }
            return null
        }

        val finalName = scoopCacheName(appName, version, url)
        val finalPath = cacheDir.resolve(finalName)

        logger.info { "[cache] Finalize: ${downloaded.fileName} -> $finalName" }

        if (Files.exists(finalPath) && Files.size(finalPath) > 0) {
            logger.info { "[cache] Already finalized: $finalName" }
            downloadCache[url] = finalPath
            return finalPath
        }

        try {
            Files.move(downloaded, finalPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.copy(downloaded, finalPath, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(downloaded)
        }

        downloadCache[url] = finalPath
        logger.info { "[cache] Finalized: ${finalPath.toAbsolutePath()}" }
        return finalPath
    }

    /**
     * Clean up temporary files. Only deletes when keepCache = false.
     */
    fun cleanup() {
        tempFiles.forEach { Files.deleteIfExists(it) }
        tempFiles.clear()
        downloadCache.clear()
    }
}
