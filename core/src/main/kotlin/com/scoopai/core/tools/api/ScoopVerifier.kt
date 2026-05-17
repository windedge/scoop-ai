package com.scoopai.core.tools.api

interface ScoopVerifier {
    fun verifyInstall(manifestJson: String): String
    fun uninstall(appName: String): String
}
