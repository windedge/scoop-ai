package com.scoopai.core.tools.api

interface ManifestIo {
    fun validate(json: String? = null): String
    fun write(json: String? = null, path: String? = null): String
    fun read(path: String? = null): String
}
