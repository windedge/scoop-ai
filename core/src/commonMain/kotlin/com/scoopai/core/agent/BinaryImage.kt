package com.scoopai.core.agent

/**
 * Lightweight carrier for a user-supplied image in an assist session.
 *
 * @property bytes Raw image bytes (PNG/JPEG/WEBP).
 * @property mimeType MIME type (e.g. "image/png", "image/jpeg").
 */
public class BinaryImage(public val bytes: ByteArray, public val mimeType: String)
