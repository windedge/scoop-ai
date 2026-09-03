package com.scoopai.core.tools.api

interface ScoopVerifier {
    /**
     * Install the manifest locally with scoop, then clean up, and report the
     * outcome as JSON. appName must match ^[A-Za-z0-9._-]+$; it is used for
     * the pre/post uninstall and persist directory cleanup.
     */
    suspend fun verifyInstall(manifestJson: String, appName: String): String
}
