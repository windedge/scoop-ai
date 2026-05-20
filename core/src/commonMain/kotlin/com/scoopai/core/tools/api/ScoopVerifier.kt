package com.scoopai.core.tools.api

interface ScoopVerifier {
    fun verifyInstall(manifestJson: String? = null): String
    fun uninstall(appName: String? = null): String
}