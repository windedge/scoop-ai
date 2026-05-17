package com.scoopai.core.tools.api

interface ManifestIo {
    fun validate(json: String): String
    fun write(json: String, path: String? = null): String
    fun read(path: String): String
}
