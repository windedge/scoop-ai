package com.scoopai.core.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceManifestsTest {

    @Test
    fun `tauri nsis reference uses correct extraction pattern`() {
        val manifest = ReferenceManifests.TAURI_NSIS_MANIFEST
        // Correct: #/dl.7z to tell Scoop to 7z-extract the NSIS payload
        assertTrue(manifest.contains("#/dl.7z"), "Must use #/dl.7z for NSIS extraction")
        // Correct: Remove-Item cleanup (glob pattern covers $PLUGINSDIR)
        assertTrue(manifest.contains("Remove-Item"), "Must have NSIS cleanup in pre_install")
        // MUST NOT have -t7z switch (wrong: treating NSIS as raw 7z)
        assertFalse(manifest.contains("-t7z"), "Must NOT use -t7z switch")
        // MUST NOT have Electron-style double extraction (Tauri has no app-64.7z)
        assertFalse(manifest.contains("app-64.7z"), "Must NOT use Electron-style double extraction")
        // MUST NOT have Expand-7zipArchive (Tauri does not need second extraction)
        assertFalse(manifest.contains("Expand-7zipArchive"), "Must NOT use Electron-style Expand-7zipArchive")
    }

    @Test
    fun `tauri nsis reference is in the ALL list`() {
        assertTrue(ReferenceManifests.ALL.contains("#/dl.7z"), "ALL must include a Tauri NSIS reference")
        assertTrue(ReferenceManifests.ALL.contains("PLUGINSDIR"), "ALL must include PLUGINSDIR cleanup pattern")
    }
}
